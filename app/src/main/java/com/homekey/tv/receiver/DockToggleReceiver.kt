package com.homekey.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.homekey.tv.service.DockOverlayController
import com.homekey.tv.ui.MainActivity

class DockToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received broadcast intent: $action")

        val controller = DockOverlayController.getInstance()
        if (controller.isServiceAttached) {
            when (action) {
                ACTION_TOGGLE_DOCK, ACTION_TOGGLE_OVERLAY -> controller.toggle()
                ACTION_SHOW_DOCK, ACTION_SHOW_OVERLAY -> controller.show()
                ACTION_HIDE_DOCK, ACTION_HIDE_OVERLAY -> controller.hide()
            }
        } else {
            // Service not bound or inactive; launch MainActivity to present overlay
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launchIntent)
        }
    }

    companion object {
        private const val TAG = "DockToggleReceiver"
        const val ACTION_TOGGLE_OVERLAY = "com.homekey.tv.ACTION_TOGGLE_OVERLAY"
        const val ACTION_TOGGLE_DOCK = "com.homekey.tv.ACTION_TOGGLE_DOCK"
        const val ACTION_SHOW_OVERLAY = "com.homekey.tv.ACTION_SHOW_OVERLAY"
        const val ACTION_SHOW_DOCK = "com.homekey.tv.ACTION_SHOW_DOCK"
        const val ACTION_HIDE_OVERLAY = "com.homekey.tv.ACTION_HIDE_OVERLAY"
        const val ACTION_HIDE_DOCK = "com.homekey.tv.ACTION_HIDE_DOCK"
    }
}
