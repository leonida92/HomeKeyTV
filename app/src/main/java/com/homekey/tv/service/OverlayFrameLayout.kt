package com.homekey.tv.service

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout

class OverlayFrameLayout(context: Context) : FrameLayout(context) {
    var onBackAction: (() -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                return onBackAction?.invoke() ?: true
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
