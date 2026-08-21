package com.ccompile.lite

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {

    private val _navigateToPath = MutableLiveData<String?>()
    val navigateToPath: LiveData<String?> get() = _navigateToPath

    private val _installFinished = MutableLiveData<Boolean?>()
    val installFinished: LiveData<Boolean?> get() = _installFinished

    private val _installProgress = MutableLiveData<String?>()
    val installProgress: LiveData<String?> get() = _installProgress

    private val _installPercent = MutableLiveData(0)
    val installPercent: LiveData<Int> get() = _installPercent

    private val _sessionChanged = MutableLiveData<Long>()
    val sessionChanged: LiveData<Long> get() = _sessionChanged

    fun notifySessionChanged() { _sessionChanged.postValue(System.currentTimeMillis()) }

    fun requestNavigate(path: String) { _navigateToPath.postValue(path) }
    fun clearNavigate() { _navigateToPath.value = null }

    fun postInstallProgress(msg: String, percent: Int = -1) {
        _installProgress.postValue(msg)
        if (percent >= 0) _installPercent.postValue(percent)
    }

    fun postInstallFinished(success: Boolean) {
        _installProgress.postValue(null)
        _installPercent.postValue(if (success) 100 else 0)
        _installFinished.postValue(success)
    }

    fun clearInstallFinished() {
        _installFinished.value = null
        _installPercent.value = 0
    }
}