// Package ptp implements a Windows Precision Touchpad HID device.
// The device model and host handshake are described in docs/architecture.md.
package ptp

// MaxContacts must match Contact Count Maximum in the Device Capabilities
// report: Windows discards a frame that reports more contacts than declared.
// Windows requires at least 3 and accepts at most 5.
const MaxContacts = 5

// MaxContactID is the largest ID this device emits; the descriptor's field is 8-bit.
const MaxContactID = MaxContacts - 1

// contactIDLogicalMax matches the Microsoft sample descriptor.
const contactIDLogicalMax = 0x7f

// Contact flags, in report descriptor declaration order.
const (
	FlagConfidence uint8 = 0x01
	FlagTip        uint8 = 0x02
)

// PadTypeClickPad is the depressible (click-pad) surface type.
const PadTypeClickPad uint8 = 0

// Input mode values used by the host in the Configuration collection.
const (
	InputModeMouse uint8 = 0
	// InputModePTP confirms Windows accepted the device as a precision touchpad.
	InputModePTP uint8 = 3
)

// Report IDs declared in the report descriptor.
const (
	ReportIDTouchpad       uint8 = 0x05
	ReportIDDeviceCaps     uint8 = 0x07
	ReportIDPTPHQA         uint8 = 0x08
	ReportIDInputMode      uint8 = 0x04
	ReportIDFunctionSwitch uint8 = 0x06
	ReportIDMouse          uint8 = 0x09
)

// HID report types carried in the high byte of wValue on GET_REPORT / SET_REPORT.
const (
	reportTypeInput   uint8 = 1
	reportTypeOutput  uint8 = 2
	reportTypeFeature uint8 = 3
)

// The logical range maps to the physical surface Windows uses for pointer
// motion and gesture thresholds: a landscape phone, roughly 6.4 x 3.0 inch.
// A portrait or laptop aspect would make one axis register shorter travel.
const (
	LogicalMax uint16 = 4095
	// PhysicalMaxX / PhysicalMaxY are in hundredths of an inch (Unit Exponent -2).
	PhysicalMaxX uint16 = 640
	PhysicalMaxY uint16 = 300
)

// maxPendingStates bounds frames waiting for the next host poll.
const maxPendingStates = 16

// One contact on the wire: id(1) + flags(1) + x(2) + y(2).
const contactWireSize = 6

// wireSize is contactCount(1) + buttons(1) + contacts.
const wireSize = 2 + MaxContacts*contactWireSize

// One contact in the HID report: flags+padding(1) + contactID(1) + x(2) + y(2).
const hidContactSize = 6

// reportSize includes the report ID: id(1) + contacts + scanTime(2) + count(1) + buttons(1).
const reportSize = 1 + MaxContacts*hidContactSize + 2 + 1 + 1

// InputReportSize is exported so callers do not hard-code the report length.
const InputReportSize = reportSize

// DefaultUSB is only for local experiments; ship with your own assigned VID/PID.
const (
	DefaultVID uint16 = 0x2E8A
	DefaultPID uint16 = 0x0021
)
