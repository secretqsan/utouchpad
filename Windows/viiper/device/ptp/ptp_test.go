package ptp_test

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"testing"
	"time"

	viiperTesting "github.com/Alia5/VIIPER/_testing"
	"github.com/Alia5/VIIPER/device/ptp"
	"github.com/Alia5/VIIPER/internal/server/api"
	"github.com/Alia5/VIIPER/internal/server/api/handler"
	"github.com/Alia5/VIIPER/usbip"
	"github.com/Alia5/VIIPER/viiperclient"
	"github.com/Alia5/VIIPER/virtualbus"
	"github.com/stretchr/testify/assert"

	_ "github.com/Alia5/VIIPER/internal/registry" // Register devices
)

// TestInputReports drives frames through the full USBIP path.
func TestInputReports(t *testing.T) {
	singleContact := ptp.NewInputState()
	singleContact.ContactCount = 1
	singleContact.ScanTime = 100
	singleContact.Contacts[0] = ptp.Contact{ID: 0, Tip: true, Confidence: true, X: 2048, Y: 1024}

	twoContacts := ptp.NewInputState()
	twoContacts.ContactCount = 2
	twoContacts.ScanTime = 200
	twoContacts.Contacts[0] = ptp.Contact{ID: 0, Tip: true, Confidence: true, X: 1000, Y: 500}
	twoContacts.Contacts[1] = ptp.Contact{ID: 1, Tip: true, Confidence: true, X: 3000, Y: 1500}

	withButton := ptp.NewInputState()
	withButton.ContactCount = 1
	withButton.ScanTime = 300
	withButton.Buttons = 0x01 // left pad button
	withButton.Contacts[0] = ptp.Contact{ID: 0, Tip: true, Confidence: true, X: 100, Y: 100}

	cases := []struct {
		name  string
		state *ptp.InputState
	}{
		{"Single contact", singleContact},
		{"Two contacts", twoContacts},
		{"Contact plus button", withButton},
	}

	s := viiperTesting.NewTestServer(t)
	defer s.UsbServer.Close() //nolint:errcheck
	defer s.ApiServer.Close() //nolint:errcheck

	r := s.ApiServer.Router()
	r.Register("bus/{id}/add", handler.BusDeviceAdd(s.UsbServer, s.ApiServer))
	r.RegisterStream("bus/{busId}/{deviceid}", api.DeviceStreamHandler(s.UsbServer))

	if err := s.ApiServer.Start(); err != nil {
		t.Fatalf("Failed to start API server: %v", err)
	}

	b, err := virtualbus.NewWithBusID(1)
	if err != nil {
		t.Fatalf("Failed to create virtual bus: %v", err)
	}
	defer b.Close() //nolint:errcheck
	_ = s.UsbServer.AddBus(b)

	client := viiperclient.New(s.ApiServer.Addr())
	stream, _, err := client.AddDeviceAndConnect(context.Background(), b.BusID(), "ptp", nil)
	if !assert.NoError(t, err) {
		return
	}
	defer stream.Close() //nolint:errcheck

	usbipClient := viiperTesting.NewUsbIpClient(t, s.UsbServer.Addr())
	devs, err := usbipClient.ListDevices()
	if !assert.NoError(t, err) {
		return
	}
	if !assert.Len(t, devs, 1) {
		return
	}
	imp, err := usbipClient.AttachDevice(devs[0].BusID)
	if !assert.NoError(t, err) {
		return
	}
	if imp != nil && imp.Conn != nil {
		defer imp.Conn.Close() //nolint:errcheck
	}

	var (
		previousScan uint16
		havePrevious bool
	)

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			want := tc.state.BuildReport()
			assert.Len(t, want, ptp.InputReportSize, "PTP input report length")

			if !assert.NoError(t, stream.WriteBinary(tc.state)) {
				return
			}

			got, err := pollReportIgnoringScanTime(usbipClient, imp.Conn, want, 750*time.Millisecond)
			if !assert.NoError(t, err) {
				return
			}

			assertReportsEqualIgnoringScanTime(t, want, got)

			scan := binary.LittleEndian.Uint16(got[scanTimeOffset:])
			if havePrevious {
				assert.NotEqual(t, previousScan, scan, "scan time must advance between reports")
			}
			previousScan = scan
			havePrevious = true
		})
	}
}

const scanTimeOffset = 1 + ptp.MaxContacts*6

// The device stamps each report with wall-clock time, so the caller cannot
// predict the Scan Time bytes. Poll until everything else matches.
func pollReportIgnoringScanTime(
	client *viiperTesting.TestUsbIpClient,
	conn net.Conn,
	want []byte,
	timeout time.Duration,
) ([]byte, error) {
	deadline := time.Now().Add(timeout)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return nil, fmt.Errorf("no matching report within %s", timeout)
		}

		got, err := client.ReadInputReportWithTimeout(conn, remaining)
		if err != nil {
			return nil, err
		}

		if reportsEqualIgnoringScanTime(want, got) {
			return got, nil
		}
	}
}

func reportsEqualIgnoringScanTime(want, got []byte) bool {
	if len(want) != len(got) {
		return false
	}
	for i := range want {
		if i >= scanTimeOffset && i < scanTimeOffset+2 {
			continue
		}
		if want[i] != got[i] {
			return false
		}
	}
	return true
}

func assertReportsEqualIgnoringScanTime(t *testing.T, want, got []byte) {
	t.Helper()
	if !assert.Len(t, got, len(want)) {
		return
	}
	for i := range want {
		if i >= scanTimeOffset && i < scanTimeOffset+2 {
			continue
		}
		assert.Equal(t, want[i], got[i], "report byte %d", i)
	}
}

// TestReportLayout verifies the byte layout of a single-contact frame, which is
// what the host parses field by field.
func TestReportLayout(t *testing.T) {
	st := ptp.NewInputState()
	st.ContactCount = 1
	st.ScanTime = 0x1234
	st.Buttons = 0x02 // right button
	st.Contacts[0] = ptp.Contact{ID: 2, Tip: true, Confidence: true, X: 0x0ABC, Y: 0x0DEF}

	got := st.BuildReport()
	if !assert.Len(t, got, ptp.InputReportSize) {
		return
	}

	// Report ID.
	assert.Equal(t, ptp.ReportIDTouchpad, got[0], "report ID")

	// Contact 0: a flags byte (Confidence bit 0, Tip bit 1, six padding bits)
	// followed by a full 8-bit Contact Identifier, matching the Microsoft
	// sample descriptor.
	flags := got[1]
	assert.Equal(t, byte(0x03), flags&0x03, "confidence and tip set")
	assert.Equal(t, byte(0x00), flags&0xFC, "padding must be zero")
	assert.Equal(t, byte(2), got[2], "contact id")

	// X and Y little-endian.
	assert.Equal(t, []byte{0xBC, 0x0A}, got[3:5], "X little-endian")
	assert.Equal(t, []byte{0xEF, 0x0D}, got[5:7], "Y little-endian")

	// Unused contact slots must be all zero so the host does not see phantom
	// contacts.
	for i := 1; i < ptp.MaxContacts; i++ {
		off := 1 + i*6
		assert.Equal(t, []byte{0, 0, 0, 0, 0, 0}, got[off:off+6], "unused contact slot %d", i)
	}

	// Scan Time, Contact Count, Buttons.
	tail := 1 + ptp.MaxContacts*6
	assert.Equal(t, []byte{0x34, 0x12}, got[tail:tail+2], "scan time little-endian")
	assert.Equal(t, byte(1), got[tail+2], "contact count")
	assert.Equal(t, byte(0x02), got[tail+3], "buttons")
}

// TestWireRoundTrip verifies the feeder wire format is symmetric.
func TestWireRoundTrip(t *testing.T) {
	want := ptp.InputState{
		ContactCount: 2,
		Buttons:      0x01,
		Contacts: [ptp.MaxContacts]ptp.Contact{
			{ID: 0, Tip: true, Confidence: true, X: 100, Y: 200},
			{ID: ptp.MaxContactID, Tip: true, Confidence: false, X: 4000, Y: 3000},
		},
	}

	enc, err := want.MarshalBinary()
	if !assert.NoError(t, err) {
		return
	}
	assert.Len(t, enc, 32, "wire packet is 2 + 5*6 bytes")

	var got ptp.InputState
	if !assert.NoError(t, got.UnmarshalBinary(enc)) {
		return
	}
	assert.Equal(t, want.ContactCount, got.ContactCount)
	assert.Equal(t, want.Buttons, got.Buttons)
	assert.Equal(t, want.Contacts[0], got.Contacts[0])
	assert.Equal(t, want.Contacts[1], got.Contacts[1])
}

// TestWireRejectsTooManyContacts ensures a frame the host would discard is
// rejected at the boundary instead of being forwarded.
func TestWireRejectsTooManyContacts(t *testing.T) {
	buf := make([]byte, 32)
	buf[0] = ptp.MaxContacts + 1

	var st ptp.InputState
	assert.ErrorIs(t, st.UnmarshalBinary(buf), ptp.ErrTooManyContacts)
}

// TestWireTooShort ensures a truncated packet is rejected.
func TestWireTooShort(t *testing.T) {
	var st ptp.InputState
	assert.Error(t, st.UnmarshalBinary([]byte{0x00, 0x00}))
}

// TestHandleControlFeatureReports covers the reports Windows must be able to
// read for the device to be treated as a precision touchpad.
func TestHandleControlFeatureReports(t *testing.T) {
	dev, err := ptp.New(nil)
	if !assert.NoError(t, err) {
		return
	}

	const (
		bmGetClassFeature uint8 = 0xA1 // device-to-host, class, interface recipient
		bmSetClassFeature uint8 = 0x21 // host-to-device, class, interface recipient
		hidGetReport      uint8 = 0x01
		hidSetReport      uint8 = 0x09
	)

	t.Run("Device capabilities", func(t *testing.T) {
		// wValue: report type 3 (feature) in the high byte, report ID low.
		resp, handled := dev.HandleControl(bmGetClassFeature, hidGetReport,
			uint16(3)<<8|uint16(ptp.ReportIDDeviceCaps), 0, 2, nil)
		if !assert.True(t, handled) {
			return
		}
		if !assert.Len(t, resp, 2) {
			return
		}
		assert.Equal(t, ptp.ReportIDDeviceCaps, resp[0], "report ID must lead")
		assert.Equal(t, uint8(ptp.MaxContacts), resp[1]&0x0f, "contact count maximum")
		assert.Equal(t, uint8(ptp.PadTypeClickPad), (resp[1]>>4)&0x0f, "pad type")
	})

	t.Run("Certification blob is 256 bytes", func(t *testing.T) {
		resp, handled := dev.HandleControl(bmGetClassFeature, hidGetReport,
			uint16(3)<<8|uint16(ptp.ReportIDPTPHQA), 0, 257, nil)
		if !assert.True(t, handled) {
			return
		}
		assert.Len(t, resp, 257, "report ID plus 256 byte blob")
		assert.Equal(t, ptp.ReportIDPTPHQA, resp[0])
	})

	t.Run("Certification blob is truncated to wLength", func(t *testing.T) {
		resp, _ := dev.HandleControl(bmGetClassFeature, hidGetReport,
			uint16(3)<<8|uint16(ptp.ReportIDPTPHQA), 0, 16, nil)
		assert.Len(t, resp, 16)
	})

	t.Run("Input mode starts in mouse mode", func(t *testing.T) {
		resp, handled := dev.HandleControl(bmGetClassFeature, hidGetReport,
			uint16(3)<<8|uint16(ptp.ReportIDInputMode), 0, 2, nil)
		if !assert.True(t, handled) {
			return
		}
		if !assert.Len(t, resp, 2) {
			return
		}
		assert.Equal(t, ptp.ReportIDInputMode, resp[0])
		assert.Equal(t, ptp.InputModeMouse, resp[1])
		assert.Equal(t, ptp.InputModeMouse, dev.InputMode())
	})

	t.Run("Host selects precision touchpad mode", func(t *testing.T) {
		_, handled := dev.HandleControl(bmSetClassFeature, hidSetReport,
			uint16(3)<<8|uint16(ptp.ReportIDInputMode), 0, 2,
			[]byte{ptp.ReportIDInputMode, ptp.InputModePTP})
		assert.True(t, handled)
		assert.Equal(t, ptp.InputModePTP, dev.InputMode(),
			"receiving Input Mode = 3 is the definitive PTP acceptance signal")
	})

	t.Run("Input mode echoed back after selection", func(t *testing.T) {
		resp, _ := dev.HandleControl(bmGetClassFeature, hidGetReport,
			uint16(3)<<8|uint16(ptp.ReportIDInputMode), 0, 2, nil)
		if !assert.Len(t, resp, 2) {
			return
		}
		assert.Equal(t, ptp.InputModePTP, resp[1])
	})

	t.Run("Set report without report ID byte is tolerated", func(t *testing.T) {
		dev2, err := ptp.New(nil)
		if !assert.NoError(t, err) {
			return
		}
		dev2.HandleControl(bmSetClassFeature, hidSetReport,
			uint16(3)<<8|uint16(ptp.ReportIDInputMode), 0, 1,
			[]byte{ptp.InputModePTP})
		assert.Equal(t, ptp.InputModePTP, dev2.InputMode())
	})

	t.Run("Non class request is not handled", func(t *testing.T) {
		_, handled := dev.HandleControl(0x80, 0x06, 0x0100, 0, 18, nil)
		assert.False(t, handled, "standard GET_DESCRIPTOR must fall through to the server")
	})
}

// TestReportPacing checks that reports are spaced by the scan period: without
// pacing the host sees 1200-2000 reports per second and drops tap transitions.
func TestReportPacing(t *testing.T) {
	t.Setenv("VIIPER_PTP_SCAN_MS", "")

	dev, err := ptp.New(nil)
	if !assert.NoError(t, err) {
		return
	}

	for i := 0; i < 3; i++ {
		st := ptp.NewInputState()
		st.ContactCount = 1
		st.Contacts[0] = ptp.Contact{
			ID: uint8(i), Tip: true, Confidence: true,
			X: uint16(100 + i), Y: 100,
		}
		dev.UpdateInputState(*st)
	}

	ctx := context.Background()
	start := time.Now()
	for i := 0; i < 3; i++ {
		if report := dev.HandleTransfer(ctx, 1, usbip.DirIn, nil); report == nil {
			t.Fatalf("transfer %d returned no report", i)
		}
	}
	elapsed := time.Since(start)

	// The first transfer is served immediately; the other two wait one scan
	// period each.
	want := 2 * ptp.DefaultScanPeriod
	if elapsed < want {
		t.Fatalf("three reports took %v, want at least %v", elapsed, want)
	}
}

// TestReportPacingDisabled checks that a zero interval answers immediately.
func TestReportPacingDisabled(t *testing.T) {
	t.Setenv("VIIPER_PTP_SCAN_MS", "0")

	dev, err := ptp.New(nil)
	if !assert.NoError(t, err) {
		return
	}

	for i := 0; i < 3; i++ {
		st := ptp.NewInputState()
		st.ContactCount = 1
		st.Contacts[0] = ptp.Contact{ID: uint8(i), Tip: true, Confidence: true}
		dev.UpdateInputState(*st)
	}

	ctx := context.Background()
	start := time.Now()
	for i := 0; i < 3; i++ {
		if report := dev.HandleTransfer(ctx, 1, usbip.DirIn, nil); report == nil {
			t.Fatalf("transfer %d returned no report", i)
		}
	}
	if elapsed := time.Since(start); elapsed > ptp.DefaultScanPeriod {
		t.Fatalf("pacing should be disabled, three reports took %v", elapsed)
	}
}
