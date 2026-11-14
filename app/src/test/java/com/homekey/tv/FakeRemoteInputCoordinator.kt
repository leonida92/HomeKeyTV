package com.homekey.tv

import com.homekey.tv.service.RemoteInputCoordinator
import com.homekey.tv.service.RemoteInputEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeRemoteInputCoordinator : RemoteInputCoordinator {
    private val _isServiceRunning = MutableStateFlow(false)
    override val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isSettingsForeground = MutableStateFlow(false)
    override val isSettingsForeground: StateFlow<Boolean> = _isSettingsForeground.asStateFlow()

    private val _isLearnModeActive = MutableStateFlow(false)
    override val isLearnModeActive: StateFlow<Boolean> = _isLearnModeActive.asStateFlow()

    private val _learnedKey = MutableStateFlow<Pair<Int, String>?>(null)
    override val learnedKey: StateFlow<Pair<Int, String>?> = _learnedKey.asStateFlow()

    private val _currentForegroundPackage = MutableStateFlow<String?>(null)
    override val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()

    var attachedEngine: RemoteInputEngine? = null
        private set
    var clearLearnedKeyCount = 0
        private set

    override fun setServiceRunning(running: Boolean) {
        _isServiceRunning.update { running }
    }

    override fun setSettingsForeground(inForeground: Boolean) {
        _isSettingsForeground.update { inForeground }
    }

    override fun setLearnMode(active: Boolean) {
        _isLearnModeActive.update { active }
        if (active) {
            _learnedKey.update { null }
            attachedEngine?.clearLearnedKey()
        }
    }

    override fun updateLearnedKey(key: Pair<Int, String>?) {
        _learnedKey.update { key }
    }

    override fun clearLearnedKey() {
        clearLearnedKeyCount++
        _learnedKey.update { null }
        attachedEngine?.clearLearnedKey()
    }

    override fun setForegroundPackage(packageName: String?) {
        _currentForegroundPackage.update { packageName }
    }

    override fun attachInputEngine(engine: RemoteInputEngine) {
        attachedEngine = engine
    }

    override fun detachInputEngine() {
        attachedEngine = null
    }
}
