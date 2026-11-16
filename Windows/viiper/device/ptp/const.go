// Package ptp provides a Windows Precision Touchpad (PTP) HID device
// implementation.
//
// Windows recognises a device as a precision touchpad when it exposes two HID
// top-level collections inside a single report descriptor:
//
//	Digitizer / Touch Pad     (0x0D / 0x05) - multitouch input + feature reports
//	Digitizer / Configuration (0x0D / 0x0E) - input mode selection
//
// and answers the mandatory feature reports described in
// https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/touchpad-required-hid-top-level-collections
//
// Once Windows has read the report descriptor and successfully fetched the
// device capabilities and certification status reports, it issues a SET_REPORT
// on the Configuration collection with Input Mode = 3, which is the definitive
// signal that the device has been accepted as a precision touchpad.
package ptp

// Maximum number of concurrent contacts. Windows requires at least 3 and
// accepts up to 5; an input frame reporting more contacts than the declared
// maximum is discarded entirely by the host, so this value must stay in sync
// with the Contact Count Maximum feature report.
const MaxContacts = 5

// MaxContactID is the largest contact identifier this device emits. The wire
// protocol and the report descriptor use an 8-bit Contact Identifier (like the
// Microsoft sample descriptor and every shipping precision touchpad),
// so the field itself can hold 0..255; five contacts only need 0..4.
const MaxContactID = MaxContacts - 1

// contactIDLogicalMax is declared in the report descriptor. 0x7f matches the
// Microsoft sample descriptor; the host only receives MaxContactID.
const contactIDLogicalMax = 0x7f

// Contact flags. These occupy bits 0 and 1 of each per-contact byte, matching
// the declaration order (Confidence first, then Tip switch) in the report
// descriptor.
const (
	FlagConfidence uint8 = 0x01
	FlagTip        uint8 = 0x02
)

// PadTypeClickPad is the pad type reported in the Device Capabilities feature
// report: a depressible (click-pad) surface.
const PadTypeClickPad uint8 = 0

// Input mode values used by the host in the Configuration collection.
const (
	// InputModeMouse means the host wants reports from the mouse collection.
	InputModeMouse uint8 = 0
	// InputModePTP means the host wants reports from the precision touchpad
	// collection. Receiving this value confirms the device was recognised.
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

// HID report types carried in the high byte of wValue on GET_REPORT /
// SET_REPORT requests.
const (
	reportTypeInput   uint8 = 1
	reportTypeOutput  uint8 = 2
	reportTypeFeature uint8 = 3
)

// Coordinate resolution. Logical range 0..4095 maps to the physical surface
// size, which is what Windows uses to compute pointer motion and gesture
// thresholds. The phone is used in landscape and the pad spans most of the
// screen, so the declared surface is the landscape phone: roughly 6.4 x 3.0
// inch. Declaring a laptop-pad or portrait aspect would make one axis of finger
// travel count for less than its real distance, and Windows' multi-finger swipe
// thresholds would be hard to reach.
const (
	LogicalMax uint16 = 4095
	// PhysicalMaxX / PhysicalMaxY are in hundredths of an inch (Unit Exponent
	// -2), so 640 = 6.40 inch and 300 = 3.00 inch.
	PhysicalMaxX uint16 = 640
	PhysicalMaxY uint16 = 300
)

// maxPendingStates bounds the frames waiting for the next host poll. Position
// updates are merged while queued, so only contact-set transitions can fill it.
const maxPendingStates = 16

// contactWireSize is the number of wire bytes used by one contact:
// id(1) + flags(1) + x(2) + y(2).
const contactWireSize = 6

// wireSize is the byte length of one client-to-server input state packet:
// contactCount(1) + buttons(1) + contacts(MaxContacts * 6).
const wireSize = 2 + MaxContacts*contactWireSize

// hidContactSize is the number of HID report bytes per contact:
// flags+padding(1) + contactID(1) + x(2) + y(2).
const hidContactSize = 6

// reportSize is the full length of a PTP input report including the report ID:
// reportID(1) + contacts(MaxContacts * 6) + scanTime(2) + contactCount(1) + buttons(1).
const reportSize = 1 + MaxContacts*hidContactSize + 2 + 1 + 1

// InputReportSize is the byte length of one HID input report, report ID
// included. Exported so callers and tests do not hard-code the value.
const InputReportSize = reportSize

// Default USB identifiers. 0x2E8A (Raspberry Pi) is used for local experiments
// only; ship with your own assigned VID/PID.
const (
	DefaultVID uint16 = 0x2E8A
	DefaultPID uint16 = 0x0021
)
