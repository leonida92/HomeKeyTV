package com.homekey.tv

import android.content.Context
import com.homekey.tv.data.models.CameraPipAlert
import com.homekey.tv.service.CameraPipOverlayController

class FakeCameraPipOverlayController : CameraPipOverlayController {
    var lastAlert: CameraPipAlert? = null
    var showCount = 0
    var dismissCount = 0

    override fun showPipAlert(context: Context, alert: CameraPipAlert) {
        showCount++
        lastAlert = alert
    }

    override fun dismissPip() {
        dismissCount++
        lastAlert = null
    }
}
