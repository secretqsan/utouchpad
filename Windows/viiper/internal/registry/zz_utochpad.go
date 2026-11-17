package registry

// Register the utochpad-specific devices. This overlay file avoids patching
// upstream VIIPER, so pulling a newer upstream revision cannot conflict.
import (
	_ "github.com/Alia5/VIIPER/device/ptp"
	_ "github.com/Alia5/VIIPER/device/radial"
)
