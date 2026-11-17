package radial_test

import (
	"context"
	"io"
	"testing"
	"time"

	viiperTesting "github.com/Alia5/VIIPER/_testing"
	"github.com/Alia5/VIIPER/device/radial"
	"github.com/Alia5/VIIPER/internal/server/api"
	"github.com/Alia5/VIIPER/internal/server/api/handler"
	"github.com/Alia5/VIIPER/viiperclient"
	"github.com/Alia5/VIIPER/virtualbus"
	"github.com/stretchr/testify/assert"

	_ "github.com/Alia5/VIIPER/internal/registry" // Register devices
)

func TestInputReports(t *testing.T) {
	type testCase struct {
		name           string
		inputState     radial.InputState
		expectedReport []byte
	}

	cases := []testCase{
		{
			name:           "Neutral",
			inputState:     radial.InputState{},
			expectedReport: []byte{0x01, 0x00, 0x00},
		},
		{
			name:           "Button pressed",
			inputState:     radial.InputState{Buttons: radial.BtnPrimary},
			expectedReport: []byte{0x01, 0x01, 0x00},
		},
		{
			// 1 tenth of a degree clockwise: dial bits sit at 1..15, so the
			// value 1 shifts to 0x0002.
			name:           "Dial +1",
			inputState:     radial.InputState{Dial: 1},
			expectedReport: []byte{0x01, 0x02, 0x00},
		},
		{
			// -1 in 15-bit two's complement is 0x7FFF, shifted to 0xFFFE.
			name:           "Dial -1",
			inputState:     radial.InputState{Dial: -1},
			expectedReport: []byte{0x01, 0xfe, 0xff},
		},
		{
			name:           "Dial +3600 (max)",
			inputState:     radial.InputState{Dial: 3600},
			expectedReport: []byte{0x01, 0x20, 0x1c},
		},
		{
			name:           "Dial -3600 (min)",
			inputState:     radial.InputState{Dial: -3600},
			expectedReport: []byte{0x01, 0xe0, 0xe3},
		},
		{
			name:           "Dial clamped above max",
			inputState:     radial.InputState{Dial: 9000},
			expectedReport: []byte{0x01, 0x20, 0x1c},
		},
		{
			name:           "Dial clamped below min",
			inputState:     radial.InputState{Dial: -9000},
			expectedReport: []byte{0x01, 0xe0, 0xe3},
		},
		{
			name:           "Dial +1 with button",
			inputState:     radial.InputState{Dial: 1, Buttons: radial.BtnPrimary},
			expectedReport: []byte{0x01, 0x03, 0x00},
		},
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
	stream, _, err := client.AddDeviceAndConnect(context.Background(), b.BusID(), radial.TypeName, nil)
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

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.expectedReport, tc.inputState.BuildReport())
			if !assert.NoError(t, stream.WriteBinary(&tc.inputState)) {
				return
			}
			got, err := usbipClient.PollInputReport(imp.Conn, tc.expectedReport, 750*time.Millisecond)
			if !assert.NoError(t, err) {
				return
			}
			assert.Equal(t, tc.expectedReport, got)
		})
	}
}

// TestWireRoundTrip verifies the 3-byte feeder wire format is symmetric.
func TestWireRoundTrip(t *testing.T) {
	cases := []radial.InputState{
		{},
		{Dial: 1},
		{Dial: -1},
		{Dial: 32767, Buttons: radial.BtnPrimary},
		{Dial: -32768},
	}

	for _, want := range cases {
		enc, err := want.MarshalBinary()
		if !assert.NoError(t, err) {
			continue
		}
		if !assert.Len(t, enc, 3) {
			continue
		}
		var got radial.InputState
		if !assert.NoError(t, got.UnmarshalBinary(enc)) {
			continue
		}
		assert.Equal(t, want, got)
	}
}

// TestWireTooShort ensures a truncated packet is rejected rather than silently
// decoded into a partial state.
func TestWireTooShort(t *testing.T) {
	var st radial.InputState
	assert.ErrorIs(t, st.UnmarshalBinary([]byte{0x00, 0x01}), io.ErrUnexpectedEOF)
}
