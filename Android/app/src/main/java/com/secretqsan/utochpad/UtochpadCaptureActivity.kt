package com.secretqsan.utochpad

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** QR scanner screen; only swaps in our layout. */
class UtochpadCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.utochpad_capture)
        return findViewById(R.id.utochpad_scanner)
    }
}
