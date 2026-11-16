package radial

import (
	"context"
	"errors"
	"sync"

	"github.com/Alia5/VIIPER/device"
	"github.com/Alia5/VIIPER/usb"
	"github.com/Alia5/VIIPER/usbip"
)

// Radial implements the usb.Device interface for a Windows radial controller
// (Surface Dial style wheel device).
type Radial struct {
	// notify wakes a waiting interrupt transfer when a state is queued.
	notify chan struct{}
	// queueMu guards pending. Dial deltas are relative, so a frame waiting for
	// the host poll cannot simply be replaced by a newer one: that would lose
	// rotation the user actually performed. Deltas of consecutive frames are
	// accumulated instead.
	queueMu    sync.Mutex
	pending    []InputState
	descriptor usb.Descriptor

	stateMu sync.Mutex
	// lastState is the most recent state pushed by the feeder. It is used to
	// re-emit button state on poll timeouts while making sure a relative dial
	// delta is only ever reported once.
	lastState InputState
}

// New returns a new radial controller device.
func New(o *device.CreateOptions) (*Radial, error) {
	r := &Radial{
		descriptor: MakeDescriptor(),
	}
	if o != nil {
		if o.IDVendor != nil {
			r.descriptor.Device.IDVendor = *o.IDVendor
		}
		if o.IDProduct != nil {
			r.descriptor.Device.IDProduct = *o.IDProduct
		}
	}
	r.notify = make(chan struct{}, 1)
	return r, nil
}

// UpdateInputState pushes a new state to the device. It never blocks.
//
// Because the dial is relative, a state that is still waiting for the host to
// poll it is merged with the new one instead of being discarded: dropping it
// would silently lose rotation.
func (r *Radial) UpdateInputState(state InputState) {
	r.queueMu.Lock()
	if n := len(r.pending); n > 0 {
		last := &r.pending[n-1]
		last.Dial = clampDial(int(last.Dial) + int(state.Dial))
		last.Buttons = state.Buttons
	} else {
		r.pending = append(r.pending, state)
	}
	r.queueMu.Unlock()

	select {
	case r.notify <- struct{}{}:
	default:
	}
}

func clampDial(value int) int16 {
	if value > int(DialMax) {
		return DialMax
	}
	if value < int(DialMin) {
		return DialMin
	}
	return int16(value)
}

// popPending removes and returns the oldest undelivered state.
func (r *Radial) popPending() (InputState, bool) {
	r.queueMu.Lock()
	defer r.queueMu.Unlock()

	if len(r.pending) == 0 {
		return InputState{}, false
	}

	state := r.pending[0]
	copy(r.pending, r.pending[1:])
	r.pending = r.pending[:len(r.pending)-1]
	return state, true
}

// HandleTransfer implements interrupt IN transfers for the radial controller.
//
// The dial field is relative, so the report returned on a poll timeout must not
// carry the previously reported rotation: the server re-issues the last cache
// on timeout for some paths, and repeating a relative delta would make Windows
// accumulate rotation the user never performed. A timeout therefore yields a
// neutral frame that preserves only the button state.
func (r *Radial) HandleTransfer(ctx context.Context, ep uint32, dir uint32, out []byte) []byte {
	if dir != usbip.DirIn || ep != 1 {
		return nil
	}

	for {
		if st, ok := r.popPending(); ok {
			r.stateMu.Lock()
			r.lastState = st
			r.stateMu.Unlock()
			return st.BuildReport()
		}

		select {
		case <-ctx.Done():
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				r.stateMu.Lock()
				st := InputState{Buttons: r.lastState.Buttons}
				r.stateMu.Unlock()
				return st.BuildReport()
			}
			return nil
		case <-r.notify:
		}
	}
}

// GetDescriptor returns the device's USB descriptor.
func (r *Radial) GetDescriptor() *usb.Descriptor {
	return &r.descriptor
}

// GetDeviceSpecificArgs returns device-specific creation arguments. The radial
// controller has none.
func (r *Radial) GetDeviceSpecificArgs() map[string]any {
	return map[string]any{}
}
