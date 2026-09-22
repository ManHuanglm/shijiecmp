package android.net.wifi

/** 桌面 stub：Wi-Fi 信息（桌面端无 Wi-Fi，返回空值） */
class WifiInfo {

    fun getSSID(): String? = null

    fun getBSSID(): String? = null

    fun getMacAddress(): String? = null

    fun getIpAddress(): Int = 0
}

/** 桌面 stub：Wi-Fi 管理器（连接状态固定为已断开） */
class WifiManager {

    fun getConnectionInfo(): WifiInfo = WifiInfo()

    fun isWifiEnabled(): Boolean = false

    fun setWifiEnabled(enabled: Boolean): Boolean = false

    companion object {

        const val WIFI_STATE_ENABLED = 3
        const val WIFI_STATE_DISABLED = 1
    }
}
