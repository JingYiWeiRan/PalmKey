package com.jywr.pcbuapk.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jywr.pcbuapk.data.preferences.UserPreferences
import com.jywr.pcbuapk.service.PairingService
import com.jywr.pcbuapk.service.QrCodeData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 配对界面 ViewModel
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingService: PairingService,
    userPreferences: UserPreferences
) : ViewModel() {

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState

    /**
     * 实际生效的 UDP 监听端口。
     *
     * 配对时上报给电脑的端口必须与监听端口一致：服务按这个端口监听 UDP 广播，
     * 若配对时写死 8888，用户改过端口后电脑就会往旧端口广播，自动检测随即失效。
     */
    val udpListenPort: StateFlow<Int> = userPreferences.preferences
        .map { it.udpListenPort }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences.DEFAULT_UDP_PORT)

    /**
     * 开始配对
     */
    fun startPairing(ip: String, port: Int, encKey: String, deviceName: String, udpPort: Int) {
        viewModelScope.launch {
            _pairingState.value = PairingState.Pairing

            val result = pairingService.pairViaTcp(ip, port, encKey, deviceName, udpPort)

            if (result == null) {
                _pairingState.value = PairingState.Success
            } else {
                _pairingState.value = PairingState.Error(result)
            }
        }
    }

    /**
     * 解析二维码内容
     */
    fun parseQrCode(qrContent: String): QrCodeData? {
        return pairingService.parseQrCode(qrContent)
    }

    /**
     * 设置错误信息
     */
    fun setErrorMessage(message: String) {
        _pairingState.value = PairingState.Error(message)
    }

    /**
     * 重置状态
     */
    fun resetState() {
        _pairingState.value = PairingState.Idle
    }

    /**
     * 配对状态
     */
    sealed class PairingState {
        object Idle : PairingState()
        object Pairing : PairingState()
        object Success : PairingState()
        data class Error(val message: String) : PairingState()
    }
}
