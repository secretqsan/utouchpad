// Package radial implements a Windows Radial Controller (Surface Dial style)
// HID device. The device model is described in docs/architecture.md.
package radial

// BtnPrimary is the single mandatory button of the Windows wheel contract.
const (
	BtnPrimary uint8 = 0x01
)

// Dial limits in tenths of a degree, matching the report descriptor.
const (
	DialMin int16 = -3600
	DialMax int16 = 3600
)

// Report IDs declared in the report descriptor.
const (
	ReportIDRadial uint8 = 0x01
)

// wireSize is dial(2) + buttons(1).
const wireSize = 3

// Default USB identifiers, only for local experiments; use your own VID/PID.
const (
	DefaultVID uint16 = 0x2E8A
	DefaultPID uint16 = 0x0020
)
