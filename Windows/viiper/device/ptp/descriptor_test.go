package ptp

import (
	"bytes"
	"testing"
)

// TestDescriptorContactLayout pins the per-contact HID field layout.
//
// Windows accepted the device even when the contact identifier was packed into
// the three spare bits after Tip/Confidence, but gesture processing did not
// work. The Microsoft sample descriptor (and every shipping precision
// touchpad) uses a one-byte identifier, so keep the byte sequence explicit.
func TestDescriptorContactLayout(t *testing.T) {
	desc := MakeDescriptor()
	if desc.Interfaces == nil || len(desc.Interfaces) != 1 {
		t.Fatalf("expected one interface")
	}

	report := desc.Interfaces[0].HID.ReportDescriptorBytes
	want := []byte{
		0x95, 0x02, // Report Count: Confidence + Tip Switch
		0x75, 0x01, // Report Size: 1 bit each
		0x81, 0x02, // Input
		0x95, 0x01, // Report Count: one padding field
		0x75, 0x06, // Report Size: 6 bits -> byte aligned
		0x81, 0x03, // Input (constant)
		0x09, 0x51, // Usage: Contact Identifier
		0x25, contactIDLogicalMax,
		0x75, 0x08, // Report Size: 8 bits
		0x95, 0x01, // Report Count: one identifier
		0x81, 0x02, // Input
	}
	if !bytes.Contains(report, want) {
		t.Fatalf("report descriptor does not declare the standard 8-bit contact layout")
	}

	// Every finger collection has to reopen on the digitizer usage page.
	//
	// The block for one contact ends with its X/Y fields, which switch the
	// current usage page to Generic Desktop. If the next contact block does not
	// switch back, its Confidence/Tip/Contact Identifier usages are declared as
	// 0x01:0x47, 0x01:0x42 and 0x01:0x51 - not precision touchpad usages at all.
	// Windows then recognises exactly one contact: the pointer moves and no
	// multi-finger gesture ever works.
	finger := []byte{0x05, 0x0d, 0x09, 0x22, 0xa1, 0x02}
	if n := bytes.Count(report, finger); n != MaxContacts {
		t.Fatalf("%d finger collections open on the digitizer page, want %d", n, MaxContacts)
	}

	// The declared stride must match the encoder: 1 flags + 1 id + 2 x + 2 y.
	if hidContactSize != 6 {
		t.Fatalf("hidContactSize = %d, want 6", hidContactSize)
	}
	ep := desc.Interfaces[0].Endpoints[0]
	if int(ep.WMaxPacketSize) < reportSize {
		t.Fatalf("endpoint max packet %d cannot carry a %d byte report", ep.WMaxPacketSize, reportSize)
	}
	if ep.BInterval != 1 {
		t.Fatalf("BInterval = %d, want 1 ms for minimum pointer latency", ep.BInterval)
	}
}
