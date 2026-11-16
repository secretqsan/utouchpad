package registry

// Register the utochpad-specific devices.
//
// This file is part of the overlay, not of upstream VIIPER: keeping the
// imports here means the build never has to patch an upstream file, so
// bumping VIIPER_VERSION cannot conflict with these two lines.
import (
	_ "github.com/Alia5/VIIPER/device/ptp"
	_ "github.com/Alia5/VIIPER/device/radial"
)
