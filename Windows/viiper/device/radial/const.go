// Package radial provides a Windows Radial Controller (Surface Dial style)
// HID device implementation.
//
// Windows recognises a device as a radial controller / wheel when it exposes a
// top-level collection of Generic Desktop / System Multi-Axis Controller
// (0x01/0x0E) containing a mandatory Button 1 and a mandatory relative Dial.
// See:
// https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/radial-controller-protocol-implementation
package radial

// Button bit masks. The radial controller protocol defines a single mandatory
// button; additional buttons are not part of the Windows wheel contract.
const (
	// BtnPrimary is the press/click button on the radial controller.
	BtnPrimary uint8 = 0x01
)

// Dial limits, in tenths of a degree, matching the LOGICAL_MINIMUM /
// LOGICAL_MAXIMUM declared in the report descriptor.
const (
	DialMin int16 = -3600
	DialMax int16 = 3600
)

// Report IDs declared in the report descriptor.
const (
	// ReportIDRadial is the ID of the sole input report.
	ReportIDRadial uint8 = 0x01
)

// wireSize is the byte length of one client-to-server input state packet.
const wireSize = 3

// Default USB identifiers. 0x2E8A (Raspberry Pi) is used for local experiments
// only; ship with your own assigned VID/PID.
const (
	DefaultVID uint16 = 0x2E8A
	DefaultPID uint16 = 0x0020
)
