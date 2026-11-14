package com.homekey.tv.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

/**
 * Coordinates reactive accessibility service, learn mode, foreground app,
 * and Settings foreground state across UI, receivers, and [RemoteButtonRemapService].
 */
interface RemoteInputCoordinator {
    val isServiceRunning: StateFlow<Boolean>
    val isSettingsForeground: StateFlow<Boolean>
    val isLearnModeActive: StateFlow<Boolean>
    val learnedKey: StateFlow<Pair<Int, String>?>
    val currentForegroundPackage: StateFlow<String?>

    fun setServiceRunning(running: Boolean)
    fun setSettingsForeground(inForeground: Boolean)
    fun setLearnMode(active: Boolean)
    fun updateLearnedKey(key: Pair<Int, String>?)
    fun clearLearnedKey()
    fun setForegroundPackage(packageName: String?)
    fun attachInputEngine(engine: RemoteInputEngine)
    fun detachInputEngine()

    companion object {
        @Volatile
        private var instance: RemoteInputCoordinator? = null

        fun getInstance(): RemoteInputCoordinator {
            return instance ?: synchronized(this) {
                instance ?: DefaultRemoteInputCoordinator().also { instance = it }
            }
        }

        fun initialize(coordinator: RemoteInputCoordinator) {
            instance = coordinator
        }
    }
}

class DefaultRemoteInputCoordinator : RemoteInputCoordinator {
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

    @Volatile
    private var inputEngineRef: WeakReference<RemoteInputEngine>? = null

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
            inputEngineRef?.get()?.clearLearnedKey()
        }
    }

    override fun updateLearnedKey(key: Pair<Int, String>?) {
        _learnedKey.update { key }
    }

    override fun clearLearnedKey() {
        _learnedKey.update { null }
        inputEngineRef?.get()?.clearLearnedKey()
    }

    override fun setForegroundPackage(packageName: String?) {
        _currentForegroundPackage.update { packageName }
    }

    override fun attachInputEngine(engine: RemoteInputEngine) {
        inputEngineRef = WeakReference(engine)
    }

    override fun detachInputEngine() {
        inputEngineRef = null
    }
}
