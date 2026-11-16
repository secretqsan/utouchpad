package radial

import (
	"io"
)

// InputState is one radial controller update.
//
// Dial is a relative delta in tenths of a degree: a HID relative field is
// consumed by exactly one report, so repeating it makes Windows accumulate
// rotation the user never performed.
//
// viiper:wire radial c2s dial:i16 buttons:u8
type InputState struct {
	// Dial is the relative rotation delta in tenths of a degree (-3600..3600).
	Dial int16
	// Buttons: bit 0 is the primary button.
	Buttons uint8
}

// BuildReport encodes the state as reportID(1) + a 15-bit dial and a 1-bit
// button packed into 16 bits. HID packs fields LSB-first in declaration order,
// so the button occupies bit 0.
func (m *InputState) BuildReport() []byte {
	dial := m.Dial
	if dial > DialMax {
		dial = DialMax
	} else if dial < DialMin {
		dial = DialMin
	}
	// Shift the dial up one bit and OR in the button.
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
