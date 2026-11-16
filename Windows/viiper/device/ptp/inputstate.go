package ptp

import (
	"encoding/binary"
	"io"
)

// Contact is a single finger on the touchpad surface.
type Contact struct {
	// ID identifies the contact across consecutive frames. Windows uses it to
	// track a finger, so it must stay stable while the finger rests. The report
	// descriptor declares an 8-bit field; this device only emits IDs 0..4, one
	// per concurrent contact.
	ID uint8
	// Tip is true while the finger is in contact with the surface.
	Tip bool
	// Confidence is true for a finger (as opposed to a palm or stray touch).
	Confidence bool
	// X, Y are absolute coordinates in the 0..LogicalMax logical range.
	X, Y uint16
}

// InputState is one complete touchpad frame.
//
// viiper:wire ptp c2s contactCount:u8 buttons:u8 contacts:u8*(6)
type InputState struct {
	// Contacts holds the active contacts; only the first ContactCount entries
	// are transmitted.
	Contacts [MaxContacts]Contact
	// ContactCount is the number of valid entries in Contacts. It must not
	// exceed MaxContacts: Windows discards an entire frame that reports more
	// contacts than the declared maximum.
	ContactCount uint8
	// Buttons is a bitfield for the physical pad buttons:
	// bit 0 = left, bit 1 = right, bit 2 = middle.
	Buttons uint8
	// ScanTime is a monotonically increasing 16-bit scan timestamp. Windows
	// derives contact velocity from it, so it must advance between frames.
	ScanTime uint16
}

// NewInputState returns a touchpad input state with no contacts.
func NewInputState() *InputState { return &InputState{} }

// buildContactBytes encodes one contact into its 6 HID report bytes.
//
// Layout: flags(1) contactID(1) x(2) y(2). The flags byte carries Confidence in
// bit 0 and Tip Switch in bit 1; bits 2..7 are padding.
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

// BuildReport encodes the state into the full HID input report.
//
// Report layout (35 bytes, report ID included):
//
//	[0]      Report ID (ReportIDTouchpad)
//	[1..31]  MaxContacts * 6 contact bytes; the offsets are computed below.
//	[31..33] Scan Time
//	[33]     Contact Count
//	[34]     Buttons
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
	// Contact Count: number of contacts reported in this frame.
	b[off] = m.ContactCount
	off++
	// Buttons (3 bits data + 5 bits padding).
	b[off] = m.Buttons & 0x07

	return b
}

// MarshalBinary encodes InputState into its wire representation.
//
// Layout: contactCount(1) buttons(1) then MaxContacts slots of
// id(1) flags(1) x(2) y(2), little-endian.
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
