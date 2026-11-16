package com.secretqsan.utochpad

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * QR scanner screen.
 *
 * zxing's [CaptureActivity] already owns the camera, the decode loop and the
 * runtime camera-permission request; this subclass only swaps in our layout
 * through the supported [CaptureActivity.initializeContent] hook.
 */
class UtochpadCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.utochpad_capture)
        return findViewById(R.id.utochpad_scanner)
    }
}
