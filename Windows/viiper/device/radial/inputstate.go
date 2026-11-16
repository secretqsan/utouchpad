package radial

import (
	"io"
)

// InputState represents one radial controller update.
//
// Dial is a *relative* rotation delta expressed in tenths of a degree, matching
// the unit and logical range declared in the report descriptor. Because the HID
// field is relative, a delta is consumed by exactly one input report; it must
// never be repeated or Windows will keep accumulating the rotation.
//
// viiper:wire radial c2s dial:i16 buttons:u8
type InputState struct {
	// Dial is the relative rotation delta in tenths of a degree (-3600..3600).
	Dial int16
	// Buttons is a bitfield; bit 0 is the primary button (BtnPrimary).
	Buttons uint8
}

// BuildReport encodes the state into the 2-byte HID input report.
//
// Report layout (2 bytes):
//
//	Bit 0:       Button 1 (0 = released, 1 = pressed)
//	Bits 1..15:  Dial, signed 15-bit two's complement, tenths of a degree
//
// HID packs fields LSB-first in declaration order, and the report descriptor
// declares the 1-bit button before the 15-bit dial, so the button occupies
// bit 0 and the dial the remaining 15 bits.
func (m *InputState) BuildReport() []byte {
	dial := m.Dial
	if dial > DialMax {
		dial = DialMax
	} else if dial < DialMin {
		dial = DialMin
	}
	// Shift the dial up by one bit, then OR in the button at bit 0.
	v := (uint16(dial) << 1) & 0xFFFE
	if m.Buttons&BtnPrimary != 0 {
		v |= 0x0001
	}
	return []byte{ReportIDRadial, byte(v), byte(v >> 8)}
}

// MarshalBinary encodes InputState into its 3-byte wire representation.
func (m *InputState) MarshalBinary() ([]byte, error) {
	return []byte{byte(m.Dial), byte(m.Dial >> 8), m.Buttons}, nil
}

// UnmarshalBinary decodes the 3-byte wire representation into InputState.
func (m *InputState) UnmarshalBinary(data []byte) error {
	if len(data) < wireSize {
		return io.ErrUnexpectedEOF
	}
	m.Dial = int16(data[0]) | int16(data[1])<<8
	m.Buttons = data[2]
	return nil
}
