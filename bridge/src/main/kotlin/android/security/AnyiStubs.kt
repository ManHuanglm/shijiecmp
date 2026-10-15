package android.security

/** 安忆 easy 系 jar 引用面补充桩 */

object NetworkSecurityPolicy {

    fun getInstance(): NetworkSecurityPolicy = this

    fun isCleartextTrafficPermitted(): Boolean = true
}

