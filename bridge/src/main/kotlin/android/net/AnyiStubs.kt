package android.net

/** 安忆 easy 系 jar 引用面补充桩 */

open class NetworkInfo {

    fun isConnected(): Boolean = false

    fun isAvailable(): Boolean = false
}


open class ConnectivityManager {

    fun getActiveNetworkInfo(): NetworkInfo? = NetworkInfo()
}
