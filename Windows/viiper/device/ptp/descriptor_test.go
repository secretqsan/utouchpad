package ptp

import (
	"bytes"
	"testing"
)

// TestDescriptorContactLayout pins the per-contact layout: a packed 3-bit
// identifier still moves the pointer, but gestures stop working.
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

	// Every finger collection must reopen on the digitizer page; otherwise a
	// contact's usages resolve to Generic Desktop and Windows sees one contact.
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
