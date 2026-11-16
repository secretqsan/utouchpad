package ptp

import (
	"context"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/Alia5/VIIPER/device"
	"github.com/Alia5/VIIPER/usb"
	"github.com/Alia5/VIIPER/usbip"
)

// Touchpad is a Windows precision touchpad HID device.
type Touchpad struct {
	// notify wakes a waiting interrupt transfer when a state is queued.
	notify chan struct{}
	// queueMu guards pending. Frames with the same contact layout merge;
	// a frame that adds or removes a contact (or toggles Tip/Confidence) never
	// does, because Windows only turns a lift into a click by seeing that one
	// transition frame.
	queueMu    sync.Mutex
	pending    []InputState
	descriptor usb.Descriptor

	mu sync.RWMutex
	// lastState is re-emitted on poll timeouts instead of an empty frame.
	lastState InputState
	// scanTime advances on every report so the host can derive contact velocity.
	scanTime uint16
	// lastScan provides wall-clock time for Scan Time; a fixed increment would
	// make velocity wrong whenever the feeder or host rate varies.
	lastScan time.Time
	// inputMode is the mode last set by the host; InputModePTP means Windows
	// accepted the device as a precision touchpad.
	inputMode uint8
	// padType is reported in the Device Capabilities feature report.
	padType uint8
	// scanPeriod paces interrupt reports to a real touchpad rate. USBIP completes
	// a transfer as soon as the device answers, so without pacing the host sees
	// thousands of reports per second and drops taps. Zero disables pacing.
	scanPeriod time.Duration
	// scanMu guards nextScan, the earliest time the next report may be sent.
	scanMu   sync.Mutex
	nextScan time.Time
}

// DefaultScanPeriod is a 250 Hz touchpad: the phone samples slower than that,
// so pacing adds at most one slot of latency.
const DefaultScanPeriod = 4 * time.Millisecond

// scanPeriodFromEnv reads the pacing interval in milliseconds from
// VIIPER_PTP_SCAN_MS. Unset or invalid means DefaultScanPeriod; 0 disables pacing.
func scanPeriodFromEnv() time.Duration {
	v := os.Getenv("VIIPER_PTP_SCAN_MS")
	if v == "" {
		return DefaultScanPeriod
	}
	ms, err := strconv.Atoi(v)
	if err != nil || ms < 0 {
		return DefaultScanPeriod
	}
	return time.Duration(ms) * time.Millisecond
}

// waitScanSlot blocks until the next scan slot is due and reserves it, so the
// host sees one report per period however fast it re-submits URBs.
func (t *Touchpad) waitScanSlot() {
	if t.scanPeriod <= 0 {
		return
	}

	t.scanMu.Lock()
	now := time.Now()
	if t.nextScan.IsZero() || now.After(t.nextScan) {
		t.nextScan = now
	}
	wait := t.nextScan.Sub(now)
	t.nextScan = t.nextScan.Add(t.scanPeriod)
	t.scanMu.Unlock()

	if wait > 0 {
		time.Sleep(wait)
	}
}

// New returns a new precision touchpad device.
func New(o *device.CreateOptions) (*Touchpad, error) {
	t := &Touchpad{
		descriptor: MakeDescriptor(),
		padType:    PadTypeClickPad,
		scanPeriod: scanPeriodFromEnv(),
	}
	if o != nil {
		if o.IDVendor != nil {
			t.descriptor.Device.IDVendor = *o.IDVendor
		}
		if o.IDProduct != nil {
			t.descriptor.Device.IDProduct = *o.IDProduct
		}
	}
	t.notify = make(chan struct{}, 1)
	st := NewInputState()
	t.lastState = *st
	// Seed a frame so the first host poll gets a well-formed report.
	t.pending = append(t.pending, *st)
	return t, nil
}

// UpdateInputState pushes a new frame. It never blocks.
//
// The newest position wins, but a frame that changes the contact set is kept
// even while older ones are pending: collapsing a tap (down, Tip=0, removed)
// into its last frame makes Windows miss the click.
func (t *Touchpad) UpdateInputState(state InputState) {
	t.queueMu.Lock()
	if n := len(t.pending); n > 0 && sameContactLayout(&t.pending[n-1], &state) {
		t.pending[n-1] = state
	} else if len(t.pending) < maxPendingStates {
		t.pending = append(t.pending, state)
	} else {
		// Only transitions can fill the queue; keep the newest ones.
		copy(t.pending, t.pending[1:])
		t.pending[len(t.pending)-1] = state
	}
	t.queueMu.Unlock()

	select {
	case t.notify <- struct{}{}:
	default:
	}
}

// popPending removes and returns the oldest undelivered state.
func (t *Touchpad) popPending() (InputState, bool) {
	t.queueMu.Lock()
	defer t.queueMu.Unlock()

	if len(t.pending) == 0 {
		return InputState{}, false
	}

	state := t.pending[0]
	copy(t.pending, t.pending[1:])
	t.pending = t.pending[:len(t.pending)-1]
	return state, true
}

// sameContactLayout reports whether two states differ only in finger position,
// so one may replace the other without losing an event.
func sameContactLayout(a, b *InputState) bool {
	if a.ContactCount != b.ContactCount || a.Buttons != b.Buttons {
		return false
	}

	for i := 0; i < int(a.ContactCount) && i < MaxContacts; i++ {
		if a.Contacts[i].ID != b.Contacts[i].ID ||
			a.Contacts[i].Tip != b.Contacts[i].Tip ||
			a.Contacts[i].Confidence != b.Contacts[i].Confidence {
			return false
		}
	}

	return true
}

// InputMode returns the mode last requested by the host.
func (t *Touchpad) InputMode() uint8 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.inputMode
}

// HandleTransfer implements interrupt IN transfers. A poll timeout re-emits the
// current contact set: an empty frame would tell Windows every finger lifted,
// producing spurious taps whenever the host polls faster than the feeder.
func (t *Touchpad) HandleTransfer(ctx context.Context, ep uint32, dir uint32, out []byte) []byte {
	if dir != usbip.DirIn || ep != 1 {
		return nil
	}

	for {
		if st, ok := t.popPending(); ok {
			t.waitScanSlot()
			t.mu.Lock()
			t.lastState = st
			st.ScanTime = t.advanceScanLocked()
			t.mu.Unlock()
			return st.BuildReport()
		}

		select {
		case <-ctx.Done():
			if ctx.Err() == context.DeadlineExceeded {
				t.waitScanSlot()
				return t.repeatLastState()
			}
			return nil
		case <-t.notify:
		}
	}
}

// repeatLastState re-emits the last frame with a fresh scan time.
func (t *Touchpad) repeatLastState() []byte {
	t.mu.Lock()
	st := t.lastState
	st.ScanTime = t.advanceScanLocked()
	t.mu.Unlock()
	return st.BuildReport()
}

// advanceScanLocked advances Scan Time by the real elapsed time in 100 µs
// units. Windows derives velocity from this field, so a fixed increment would
// misreport speed whenever the phone batches frames. The caller holds t.mu.
func (t *Touchpad) advanceScanLocked() uint16 {
	now := time.Now()
	if t.lastScan.IsZero() {
		t.lastScan = now
		t.scanTime++
		return t.scanTime
	}

	ticks := uint32(now.Sub(t.lastScan) / (100 * time.Microsecond))
	t.lastScan = now

	// Reports must never share a timestamp, and a long stall (suspend,
	// breakpoint) must not look like a teleport to the host.
	if ticks == 0 {
		ticks = 1
	} else if ticks > 1000 {
		ticks = 1000
	}

	t.scanTime += uint16(ticks)
	return t.scanTime
}

// GetDescriptor returns the device's USB descriptor.
func (t *Touchpad) GetDescriptor() *usb.Descriptor {
	return &t.descriptor
}

// GetDeviceSpecificArgs exposes the state the service polls while attaching the
// device. ptpActive becomes true once Windows accepts the collection.
func (t *Touchpad) GetDeviceSpecificArgs() map[string]any {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return map[string]any{
		"inputMode":       t.inputMode,
		"ptpActive":       t.inputMode == InputModePTP,
		"contactCountMax": MaxContacts,
		"padType":         t.padType,
		"scanMs":          int(t.scanPeriod / time.Millisecond),
	}
}

// HandleControl answers HID class requests on endpoint 0. The server's built-in
// fallback returns zero-length reports, but Windows must read Device
// Capabilities and Certification Status and sends Input Mode via SET_REPORT.
func (t *Touchpad) HandleControl(bmRequestType, bRequest uint8, wValue, wIndex, wLength uint16, data []byte) ([]byte, bool) {
	const (
		hidGetReport uint8 = 0x01
		hidSetReport uint8 = 0x09
	)

	// Bit 5 marks a class request; bit 7 is the direction (1 = device to host).
	if bmRequestType&0x60 != 0x20 {
		return nil, false
	}
	reportType := uint8(wValue >> 8)
	reportID := uint8(wValue & 0xff)

	switch {
	case bmRequestType&0x80 != 0 && bRequest == hidGetReport:
		switch reportType {
		case reportTypeFeature:
			return t.getFeatureReport(reportID, wLength), true
		case reportTypeInput:
			// Windows may probe the current input report; answer with the last
			// frame so the reply is well formed.
			return t.repeatLastState()[:minInt(int(wLength), reportSize)], true
		}
	case bmRequestType&0x80 == 0 && bRequest == hidSetReport:
		if reportType == reportTypeFeature {
			t.setFeatureReport(reportID, data)
			return nil, true
		}
	}

	return nil, false
}

// getFeatureReport builds the payload of a feature report, report ID included.
func (t *Touchpad) getFeatureReport(reportID uint8, wLength uint16) []byte {
	switch reportID {
	case ReportIDDeviceCaps:
		// One nibble per usage, in declaration order: Contact Count Maximum
		// first, then Pad Type.
		t.mu.RLock()
		padType := t.padType
		t.mu.RUnlock()
		out := []byte{
			ReportIDDeviceCaps,
			uint8(MaxContacts)&0x0f | (padType&0x0f)<<4,
		}
		return truncate(out, wLength)

	case ReportIDPTPHQA:
		// Windows 10+ only checks that 256 bytes come back, not their signature.
		out := make([]byte, 1+len(samplePTPHQABlob))
		out[0] = ReportIDPTPHQA
		copy(out[1:], samplePTPHQABlob[:])
		return truncate(out, wLength)

	case ReportIDInputMode:
		t.mu.RLock()
		mode := t.inputMode
		t.mu.RUnlock()
		return truncate([]byte{ReportIDInputMode, mode}, wLength)

	case ReportIDFunctionSwitch:
		// Surface switch and button switch both reported as present.
		out := []byte{ReportIDFunctionSwitch, 0x03, 0x00}
		return truncate(out, wLength)
	}

	return nil
}

// setFeatureReport stores the payload of a feature report sent by the host.
func (t *Touchpad) setFeatureReport(reportID uint8, data []byte) {
	switch reportID {
	case ReportIDInputMode:
		if len(data) < 2 {
			// Some hosts omit the report ID when the collection has one report.
			if len(data) == 1 {
				t.setInputMode(data[0])
			}
			return
		}
		t.setInputMode(data[1])
	}
}

func (t *Touchpad) setInputMode(mode uint8) {
	t.mu.Lock()
	t.inputMode = mode
	t.mu.Unlock()
}

func truncate(b []byte, wLength uint16) []byte {
	if wLength == 0 || int(wLength) >= len(b) {
		return b
	}
	return b[:wLength]
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
