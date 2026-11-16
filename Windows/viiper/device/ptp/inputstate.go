package ptp

import (
	"encoding/binary"
	"io"
)

// Contact is one finger on the touchpad surface.
type Contact struct {
	// ID must stay stable while the finger rests; Windows tracks contacts by it.
	ID uint8
	// Tip is true while the finger touches the surface.
	Tip bool
	// Confidence is true for a finger rather than a palm or stray touch.
	Confidence bool
	// X, Y are absolute coordinates in the 0..LogicalMax range.
	X, Y uint16
}

// InputState is one complete touchpad frame.
//
// viiper:wire ptp c2s contactCount:u8 buttons:u8 contacts:u8*(6)
type InputState struct {
	// Only the first ContactCount entries are transmitted.
	Contacts [MaxContacts]Contact
	// ContactCount must not exceed MaxContacts: Windows discards the whole frame.
	ContactCount uint8
	// Buttons: bit 0 = left, bit 1 = right, bit 2 = middle.
	Buttons uint8
	// ScanTime must advance between frames; Windows derives velocity from it.
	ScanTime uint16
}

// NewInputState returns a touchpad input state with no contacts.
func NewInputState() *InputState { return &InputState{} }

// buildContactBytes encodes one contact as flags(1) id(1) x(2) y(2); the flags
// byte carries Confidence in bit 0 and Tip in bit 1.
func buildContactBytes(c Contact) [hidContactSize]byte {
	var b [hidContactSize]byte
	if c.Confidence {
		b[0] |= FlagConfidence
	}
	if c.Tip {
		b[0] |= FlagTip
	}
	b[1] = c.ID
	binary.LittleEndian.PutUint16(b[2:4], c.X)
	binary.LittleEndian.PutUint16(b[4:6], c.Y)
	return b
}

// BuildReport encodes the state into the full 35-byte HID input report:
// reportID(1) contacts(N*6) scanTime(2) count(1) buttons(1).
func (m *InputState) BuildReport() []byte {
	b := make([]byte, reportSize)
	b[0] = ReportIDTouchpad

	off := 1
	for i := 0; i < MaxContacts; i++ {
		var c Contact
		if i < int(m.ContactCount) {
			c = m.Contacts[i]
		}
		cb := buildContactBytes(c)
		copy(b[off:off+hidContactSize], cb[:])
		off += hidContactSize
	}

	// Scan Time: relative timing of this frame.
	binary.LittleEndian.PutUint16(b[off:off+2], m.ScanTime)
	off += 2
	b[off] = m.ContactCount
	off++
	// Three data bits plus five padding bits.
	b[off] = m.Buttons & 0x07

	return b
}

// MarshalBinary encodes InputState as contactCount(1) buttons(1) then contact
// slots of id(1) flags(1) x(2) y(2), little-endian.
func (m *InputState) MarshalBinary() ([]byte, error) {
	b := make([]byte, wireSize)
	b[0] = m.ContactCount
	b[1] = m.Buttons
	off := 2
	for i := 0; i < MaxContacts; i++ {
		c := m.Contacts[i]
		b[off] = c.ID
		var flags uint8
		if c.Tip {
			flags |= FlagTip
		}
		if c.Confidence {
			flags |= FlagConfidence
		}
		b[off+1] = flags
		binary.LittleEndian.PutUint16(b[off+2:off+4], c.X)
		binary.LittleEndian.PutUint16(b[off+4:off+6], c.Y)
		off += contactWireSize
	}
	return b, nil
}

// UnmarshalBinary decodes the wire representation into InputState.
func (m *InputState) UnmarshalBinary(data []byte) error {
	if len(data) < wireSize {
		return io.ErrUnexpectedEOF
	}
	m.ContactCount = data[0]
	if m.ContactCount > MaxContacts {
		return ErrTooManyContacts
	}
	m.Buttons = data[1]
	off := 2
	for i := 0; i < MaxContacts; i++ {
		var c Contact
		c.ID = data[off]
		flags := data[off+1]
		c.Tip = flags&FlagTip != 0
		c.Confidence = flags&FlagConfidence != 0
		c.X = binary.LittleEndian.Uint16(data[off+2 : off+4])
		c.Y = binary.LittleEndian.Uint16(data[off+4 : off+6])
		m.Contacts[i] = c
		off += contactWireSize
	}
	return nil
}
