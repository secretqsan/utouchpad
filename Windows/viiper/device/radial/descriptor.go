package radial

import (
	"github.com/Alia5/VIIPER/usb"
)

// reportDescriptorBytes is the HID report descriptor for a Windows radial
// controller, taken verbatim from the Microsoft sample "Integrated Radial
// Controller TLC":
// https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/radial-controller-sample-report-descriptors
//
// A top-level collection of Generic Desktop / System Multi-Axis Controller
// (0x01/0x0E) containing Button 1 and a relative Dial is what makes Windows
// classify the device as a wheel (Surface Dial style) device.
//
// The X / Y / Width padding fields from the Microsoft sample are omitted: they
// only describe an on-screen puck position (Surface Studio style), are optional,
// and would make every report 10 bytes instead of 2.
var reportDescriptorBytes = usb.Data{
	0x05, 0x01, //       USAGE_PAGE (Generic Desktop)
	0x09, 0x0e, //       USAGE (System Multi-Axis Controller)
	0xa1, 0x01, //       COLLECTION (Application)
	0x85, ReportIDRadial, // REPORT_ID (1)
	0x05, 0x0d, //         USAGE_PAGE (Digitizers)
	0x09, 0x21, //         USAGE (Puck)
	0xa1, 0x00, //         COLLECTION (Physical)
	0x05, 0x09, //           USAGE_PAGE (Buttons)
	0x09, 0x01, //           USAGE (Button 1)
	0x95, 0x01, //           REPORT_COUNT (1)
	0x75, 0x01, //           REPORT_SIZE (1)
	0x15, 0x00, //           LOGICAL_MINIMUM (0)
	0x25, 0x01, //           LOGICAL_MAXIMUM (1)
	0x81, 0x02, //           INPUT (Data,Var,Abs)
	0x05, 0x01, //           USAGE_PAGE (Generic Desktop)
	0x09, 0x37, //           USAGE (Dial)
	0x95, 0x01, //           REPORT_COUNT (1)
	0x75, 0x0f, //           REPORT_SIZE (15)
	0x55, 0x0f, //           UNIT_EXPONENT (-1)
	0x65, 0x14, //           UNIT (Degrees, English Rotation)
	0x36, 0xf0, 0xf1, //     PHYSICAL_MINIMUM (-3600)
	0x46, 0x10, 0x0e, //     PHYSICAL_MAXIMUM (3600)
	0x16, 0xf0, 0xf1, //     LOGICAL_MINIMUM (-3600)
	0x26, 0x10, 0x0e, //     LOGICAL_MAXIMUM (3600)
	0x81, 0x06, //           INPUT (Data,Var,Rel)
	0xc0, //               END_COLLECTION
	0xc0, //             END_COLLECTION
}

// MakeDescriptor returns the USB descriptor for a Windows radial controller.
//
// The interface deliberately uses HID subclass 0 and protocol 0 (no boot
// subclass): boot-protocol handling would interfere with the report ID and the
// vendor-defined top-level collection Windows expects for a wheel device.
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
			BcdDevice:          0x0100,
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
					ReportDescriptorBytes: reportDescriptorBytes,
				},
				Endpoints: []usb.EndpointDescriptor{
					{
						BEndpointAddress: 0x81,   // EP1 IN
						BMAttributes:     0x03,   // Interrupt
						WMaxPacketSize:   0x0008, // 2-byte report, rounded up to 8
						BInterval:        0x01,   // 1 ms poll interval
					},
				},
			},
		},
		Strings: map[uint8]string{
			0: "\u0409", // LangID: en-US (0x0409)
			1: "VIIPER",
			2: "Radial Controller",
			3: "1337",
		},
	}
}
