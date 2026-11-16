package ptp

import (
	"github.com/Alia5/VIIPER/usb"
)

// contactReportItems returns the per-contact block of the Microsoft sample
// descriptor. Keep it bit-for-bit compatible: a packed 3-bit contact identifier
// still moves the pointer, but Windows never recognises multi-finger gestures.
func contactReportItems() []byte {
	return []byte{
		// The previous contact block ends on the Generic Desktop page (X/Y), so
		// the digitizer page must be restored here. Without it, later contacts
		// declare 0x01:0x47/0x42/0x51 instead of 0x0D:*, and Windows sees only
		// one valid contact (pointer moves, gestures never work).
		0x05, 0x0d, //           USAGE_PAGE (Digitizers)
		0x09, 0x22, //           USAGE (Finger)
		0xa1, 0x02, //           COLLECTION (Logical)
		0x15, 0x00, //             LOGICAL_MINIMUM (0)
		0x25, 0x01, //             LOGICAL_MAXIMUM (1)
		0x09, 0x47, //             USAGE (Confidence)
		0x09, 0x42, //             USAGE (Tip Switch)
		0x95, 0x02, //             REPORT_COUNT (2)
		0x75, 0x01, //             REPORT_SIZE (1)
		0x81, 0x02, //             INPUT (Data,Var,Abs)
		0x95, 0x01, //             REPORT_COUNT (1)
		0x75, 0x06, //             REPORT_SIZE (6)
		0x81, 0x03, //             INPUT (Const,Var,Abs)
		0x09, 0x51, //             USAGE (Contact Identifier)
		0x25, contactIDLogicalMax, // LOGICAL_MAXIMUM (127)
		0x75, 0x08, //             REPORT_SIZE (8)
		0x95, 0x01, //             REPORT_COUNT (1)
		0x81, 0x02, //             INPUT (Data,Var,Abs)
		0x05, 0x01, //             USAGE_PAGE (Generic Desktop)
		0x15, 0x00, //             LOGICAL_MINIMUM (0)
		0x26, 0xff, 0x0f, //       LOGICAL_MAXIMUM (4095)
		0x75, 0x10, //             REPORT_SIZE (16)
		0x55, 0x0e, //             UNIT_EXPONENT (-2)
		0x65, 0x13, //             UNIT (Inch, EngLinear)
		0x09, 0x30, //             USAGE (X)
		0x35, 0x00, //             PHYSICAL_MINIMUM (0)
		0x46, 0x80, 0x02, //       PHYSICAL_MAXIMUM (640)
		0x95, 0x01, //             REPORT_COUNT (1)
		0x81, 0x02, //             INPUT (Data,Var,Abs)
		0x46, 0x2c, 0x01, //       PHYSICAL_MAXIMUM (300)
		0x09, 0x31, //             USAGE (Y)
		0x81, 0x02, //             INPUT (Data,Var,Abs)
		0xc0, //                 END_COLLECTION
	}
}

// buildReportDescriptor assembles the complete report descriptor. A precision
// touchpad needs the Touch Pad and Configuration top-level collections; the
// mouse collection is present only because the sample descriptor declares it.
func buildReportDescriptor() usb.Data {
	d := usb.Data{
		0x05, 0x0d, // USAGE_PAGE (Digitizers)
		0x09, 0x05, // USAGE (Touch Pad)
		0xa1, 0x01, // COLLECTION (Application)
		0x85, ReportIDTouchpad, // REPORT_ID (Touch pad)
	}

	for i := 0; i < MaxContacts; i++ {
		d = append(d, contactReportItems()...)
	}

	d = append(d,
		// Scan Time: expresses contact timing so the host can compute velocity.
		0x55, 0x0c, //                   UNIT_EXPONENT (-4)
		0x66, 0x01, 0x10, //             UNIT (Seconds)
		0x47, 0xff, 0xff, 0x00, 0x00, // PHYSICAL_MAXIMUM (65535)
		0x27, 0xff, 0xff, 0x00, 0x00, // LOGICAL_MAXIMUM (65535)
		0x75, 0x10, //                   REPORT_SIZE (16)
		0x95, 0x01, //                   REPORT_COUNT (1)
		0x05, 0x0d, //                   USAGE_PAGE (Digitizers)
		0x09, 0x56, //                   USAGE (Scan Time)
		0x81, 0x02, //                   INPUT (Data,Var,Abs)

		// Total number of contacts present in this frame.
		0x09, 0x54, //                   USAGE (Contact Count)
		0x25, 0x0f, //                   LOGICAL_MAXIMUM (15)
		0x95, 0x01, //                   REPORT_COUNT (1)
		0x75, 0x08, //                   REPORT_SIZE (8)
		0x81, 0x02, //                   INPUT (Data,Var,Abs)

		// Physical buttons on the pad (click pad left/right/middle).
		0x05, 0x09, //                   USAGE_PAGE (Button)
		0x09, 0x01, //                   USAGE (Button 1)
		0x09, 0x02, //                   USAGE (Button 2)
		0x09, 0x03, //                   USAGE (Button 3)
		0x25, 0x01, //                   LOGICAL_MAXIMUM (1)
		0x75, 0x01, //                   REPORT_SIZE (1)
		0x95, 0x03, //                   REPORT_COUNT (3)
		0x81, 0x02, //                   INPUT (Data,Var,Abs)
		0x95, 0x05, //                   REPORT_COUNT (5)
		0x81, 0x03, //                   INPUT (Const,Var,Abs)

		// Device Capabilities feature report: Contact Count Maximum and
		// Pad Type, one nibble each.
		0x05, 0x0d, //                   USAGE_PAGE (Digitizer)
		0x85, ReportIDDeviceCaps, //     REPORT_ID (Device Capabilities)
		0x09, 0x55, //                   USAGE (Contact Count Maximum)
		0x09, 0x59, //                   USAGE (Pad Type)
		0x75, 0x04, //                   REPORT_SIZE (4)
		0x95, 0x02, //                   REPORT_COUNT (2)
		0x25, 0x0f, //                   LOGICAL_MAXIMUM (15)
		0xb1, 0x02, //                   FEATURE (Data,Var,Abs)

		// Device Certification Status (PTPHQA). Windows 10+ only requires the
		// usage and 256 bytes back; the blob need not be signed.
		0x06, 0x00, 0xff, //             USAGE_PAGE (Vendor Defined)
		0x85, ReportIDPTPHQA, //         REPORT_ID (PTPHQA)
		0x09, 0xc5, //                   USAGE (Vendor Usage 0xC5)
		0x15, 0x00, //                   LOGICAL_MINIMUM (0)
		0x26, 0xff, 0x00, //             LOGICAL_MAXIMUM (0xff)
		0x75, 0x08, //                   REPORT_SIZE (8)
		0x96, 0x00, 0x01, //             REPORT_COUNT (256)
		0xb1, 0x02, //                   FEATURE (Data,Var,Abs)
		0xc0, //                       END_COLLECTION (Touch Pad)
	)

	d = append(d,
		// Configuration collection: lets the host select which collection is
		// used for input reporting.
		0x05, 0x0d, //                 USAGE_PAGE (Digitizer)
		0x09, 0x0e, //                 USAGE (Configuration)
		0xa1, 0x01, //                 COLLECTION (Application)
		0x85, ReportIDInputMode, //    REPORT_ID (Input Mode)
		0x09, 0x22, //                 USAGE (Finger)
		0xa1, 0x02, //                 COLLECTION (Logical)
		0x09, 0x52, //                   USAGE (Input Mode)
		0x15, 0x00, //                   LOGICAL_MINIMUM (0)
		0x25, 0x0a, //                   LOGICAL_MAXIMUM (10)
		0x75, 0x08, //                   REPORT_SIZE (8)
		0x95, 0x01, //                   REPORT_COUNT (1)
		0xb1, 0x02, //                   FEATURE (Data,Var,Abs)
		0xc0,       //                       END_COLLECTION
		0x09, 0x22, //                 USAGE (Finger)
		0xa1, 0x00, //                 COLLECTION (Physical)
		0x85, ReportIDFunctionSwitch, // REPORT_ID (Function Switch)
		0x09, 0x57, //                   USAGE (Surface Switch)
		0x09, 0x58, //                   USAGE (Button Switch)
		0x75, 0x01, //                   REPORT_SIZE (1)
		0x95, 0x02, //                   REPORT_COUNT (2)
		0x25, 0x01, //                   LOGICAL_MAXIMUM (1)
		0xb1, 0x02, //                   FEATURE (Data,Var,Abs)
		0x95, 0x06, //                   REPORT_COUNT (6)
		0xb1, 0x03, //                   FEATURE (Const,Var,Abs)
		0xc0, //                       END_COLLECTION
		0xc0, //                     END_COLLECTION (Configuration)
	)

	d = append(d,
		// Basic mouse mode collection, exactly as in the Microsoft sample
		// descriptor. Windows never asks for reports through it while Input Mode
		// is 3, but a precision touchpad is expected to offer the alternative
		// collection it can switch to, so keep the descriptor complete.
		0x05, 0x01, //                 USAGE_PAGE (Generic Desktop)
		0x09, 0x02, //                 USAGE (Mouse)
		0xa1, 0x01, //                 COLLECTION (Application)
		0x85, ReportIDMouse, //        REPORT_ID (Mouse)
		0x09, 0x01, //                   USAGE (Pointer)
		0xa1, 0x00, //                   COLLECTION (Physical)
		0x05, 0x09, //                     USAGE_PAGE (Button)
		0x19, 0x01, //                     USAGE_MINIMUM (Button 1)
		0x29, 0x02, //                     USAGE_MAXIMUM (Button 2)
		0x25, 0x01, //                     LOGICAL_MAXIMUM (1)
		0x75, 0x01, //                     REPORT_SIZE (1)
		0x95, 0x02, //                     REPORT_COUNT (2)
		0x81, 0x02, //                     INPUT (Data,Var,Abs)
		0x95, 0x06, //                     REPORT_COUNT (6)
		0x81, 0x03, //                     INPUT (Const,Var,Abs)
		0x05, 0x01, //                     USAGE_PAGE (Generic Desktop)
		0x09, 0x30, //                     USAGE (X)
		0x09, 0x31, //                     USAGE (Y)
		0x75, 0x10, //                     REPORT_SIZE (16)
		0x95, 0x02, //                     REPORT_COUNT (2)
		0x25, 0x0a, //                     LOGICAL_MAXIMUM (10)
		0x81, 0x06, //                     INPUT (Data,Var,Rel)
		0xc0, //                         END_COLLECTION
		0xc0, //                       END_COLLECTION (Mouse)
	)

	return d
}

// MakeDescriptor returns the USB descriptor for a precision touchpad. HID
// subclass/protocol 0 avoids boot handling, which would bypass the report-ID
// based collections Windows needs.
func MakeDescriptor() usb.Descriptor {
	return usb.Descriptor{
		Device: usb.DeviceDescriptor{
			BcdUSB:             0x0200,
			BDeviceClass:       0x00,
			BDeviceSubClass:    0x00,
			BDeviceProtocol:    0x00,
			BMaxPacketSize0:    0x40, // 64 bytes
			IDVendor:           DefaultVID,
			IDProduct:          DefaultPID,
			BcdDevice:          0x0112, // bumped when the descriptor changes
			IManufacturer:      0x01,
			IProduct:           0x02,
			ISerialNumber:      0x03,
			BNumConfigurations: 0x01,
			Speed:              2, // Full speed
		},
		Interfaces: []usb.InterfaceConfig{
			{
				Descriptor: usb.InterfaceDescriptor{
					BInterfaceNumber:   0x00,
					BAlternateSetting:  0x00,
					BNumEndpoints:      0x01,
					BInterfaceClass:    0x03, // HID
					BInterfaceSubClass: 0x00, // no boot subclass
					BInterfaceProtocol: 0x00, // no boot protocol
					IInterface:         0x00,
				},
				HID: &usb.HIDFunction{
					Descriptor: usb.HIDDescriptor{
						BcdHID:       0x0111,
						BCountryCode: 0x00,
						Descriptors: []usb.HIDSubDescriptor{
							{Type: usb.ReportDescType},
						},
					},
					ReportDescriptorBytes: buildReportDescriptor(),
				},
				Endpoints: []usb.EndpointDescriptor{
					{
						BEndpointAddress: 0x81,   // EP1 IN
						BMAttributes:     0x03,   // Interrupt
						WMaxPacketSize:   0x0040, // report is 35 bytes; full-speed max is 64
						BInterval:        0x01,   // 1 ms poll interval (lowest pointer latency)
					},
				},
			},
		},
		Strings: map[uint8]string{
			0: "\u0409", // LangID: en-US (0x0409)
			1: "VIIPER",
			2: "Precision Touchpad",
			3: "1339", // new serial: Windows must read the new report descriptor
		},
	}
}
