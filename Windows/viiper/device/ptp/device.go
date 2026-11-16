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

// Touchpad implements the usb.Device and usb.ControlDevice interfaces for a
// Windows precision touchpad.
type Touchpad struct {
	// notify wakes a waiting interrupt transfer when a state is queued.
	notify chan struct{}
	// queueMu guards pending, a short FIFO of undelivered states. Consecutive
	// states with the same contact layout are merged, but a state that adds or
	// removes a contact (or toggles Tip/Confidence) is never dropped: Windows
	// only produces a tap when it observes Tip fall while the contact is still
	// reported, and that transition exists in exactly one frame.
	queueMu    sync.Mutex
	pending    []InputState
	descriptor usb.Descriptor

	mu sync.RWMutex
	// lastState is the most recent state pushed by the feeder. It is re-emitted
	// on poll timeouts so the host keeps seeing the current contact set instead
	// of falling back to an empty frame.
	lastState InputState
	// scanTime is the monotonically increasing scan timestamp. It advances on
	// every report so the host can derive contact velocity.
	scanTime uint16
	// lastScan is the wall-clock time of the previous report. Scan Time is
	// derived from it instead of a fixed increment so velocity stays correct
	// regardless of how fast the phone sends frames or how often the host polls.
	lastScan time.Time
	// inputMode is the mode last set by the host through the Configuration
	// collection. InputModePTP means the device was accepted as a precision
	// touchpad.
	inputMode uint8
	// padType is reported in the Device Capabilities feature report.
	padType uint8
	// scanPeriod paces interrupt reports to a real touchpad's scan rate. The
	// USBIP server completes a transfer as soon as the device answers, and the
	// host re-submits immediately, so without pacing the host sees thousands of
	// reports per second — a rate no precision touchpad can produce and one
	// Windows' tap detection is not tuned for. Zero disables pacing.
	scanPeriod time.Duration
	// scanMu guards nextScan, the earliest time the next report may be sent.
	scanMu   sync.Mutex
	nextScan time.Time
}

// DefaultScanPeriod is the report cadence used when VIIPER_PTP_SCAN_MS is not
// set: 4 ms, i.e. a 250 Hz touchpad. The phone produces a frame roughly every
// 8 ms, so 250 Hz leaves the pacing loop twice the headroom it needs and adds
// at most 4 ms to a frame that is already on its way.
const DefaultScanPeriod = 4 * time.Millisecond

// scanPeriodFromEnv reads the report pacing interval from
// VIIPER_PTP_SCAN_MS, in milliseconds. Unset means DefaultScanPeriod; 0 or a
// negative value disables pacing.
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

// waitScanSlot blocks until the next scan slot is due, then reserves it.
//
// Every interrupt transfer goes through here, so the host sees reports at the
// configured cadence no matter how fast it re-submits URBs. Concurrent
// transfers queue up and are served one per slot instead of all at once.
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
	// Seed a neutral frame so the first host poll gets a well-formed report
	// instead of an empty completion.
	t.pending = append(t.pending, *st)
	return t, nil
}

// UpdateInputState pushes a new frame to the device. It never blocks.
//
// The newest position always wins, but a frame that changes the contact set is
// kept even while an older one is still waiting for the host to poll it. A
// frame burst (Wi-Fi buffering, a stalled reader) used to collapse a whole tap
// — contact down, Tip=0, contact removed — into its last frame, which Windows
// then never saw as a click.
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

// sameContactLayout reports whether two states differ only in finger position
// (and scan time), so one may replace the other without losing an event.
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

// InputMode returns the input mode last requested by the host. A value of
// InputModePTP confirms Windows accepted the device as a precision touchpad.
func (t *Touchpad) InputMode() uint8 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.inputMode
}

// HandleTransfer implements interrupt IN transfers.
//
// On a poll timeout the current contact set is re-emitted with an advanced scan
// time rather than a blank frame: dropping to zero contacts would tell Windows
// every finger was lifted, which produces spurious taps and breaks gesture
// tracking whenever the host polls faster than the feeder produces frames.
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

// advanceScanLocked advances the 16-bit Scan Time by the real elapsed time
// since the previous report, in units of 100 microseconds.
//
// Windows computes contact velocity from Scan Time. A fixed per-report
// increment assumes one report per 400 microseconds, which is wrong for a
// 250 Hz USB endpoint and even more wrong when the phone batches frames:
// Windows then believes every movement happened several times faster than it
// really did, so swipe/fling recognition and pointer acceleration misbehave.
// Using wall-clock time makes the timestamp match the physical motion. The
// caller must hold t.mu.
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

// GetDeviceSpecificArgs exposes the state the service polls while attaching
// the device. ptpActive becomes true once Windows accepts the precision
// touchpad collection and sets Input Mode.
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

// HandleControl answers HID class requests on endpoint 0.
//
// The server's built-in fallback answers GET_REPORT / SET_REPORT with a
// zero-length reply, which is not enough for a precision touchpad: Windows
// must be able to read the Device Capabilities and Device Certification Status
// feature reports, and it sends Input Mode through SET_REPORT. Implementing
// usb.ControlDevice takes precedence over that fallback.
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

// getFeatureReport builds the payload of a feature report.
//
// Reports declared with a report ID must include that ID as their first byte.
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
		// The 256-byte certification blob. Windows 10+ only requires that the
		// usage exists and that 256 bytes come back; the contents do not need
		// to be signed. The sample values published by Microsoft are used.
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
			// Some hosts omit the report ID when only one report of that type
			// exists on the collection.
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
