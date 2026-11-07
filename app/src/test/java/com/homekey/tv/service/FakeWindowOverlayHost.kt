package com.homekey.tv.service

class FakeWindowOverlayHost : WindowOverlayHost {
    var showWindowResult = true
    var hideWindowResult = true
    var prewarmWindowResult = true
    var dismissActiveModalResult = false

    var showWindowCalls = 0
    var hideWindowCalls = 0
    var prewarmWindowCalls = 0
    var teardownWindowCalls = 0
    var dismissActiveModalCalls = 0

    override fun showWindow(): Boolean {
        showWindowCalls++
        return showWindowResult
    }

    override fun hideWindow(): Boolean {
        hideWindowCalls++
        return hideWindowResult
    }

    override fun prewarmWindow(): Boolean {
        prewarmWindowCalls++
        return prewarmWindowResult
    }

    override fun teardownWindow() {
        teardownWindowCalls++
    }

    override fun dismissActiveModal(): Boolean {
        dismissActiveModalCalls++
        return dismissActiveModalResult
    }
}
