package android.provider

/** 桌面 stub：系统设置读取（桌面端无系统库，一律 null） */
object Settings {

    object Secure {

        @JvmStatic
        fun getString(resolver: android.content.ContentResolver?, name: String?): String? = null

        @JvmStatic
        fun getInt(resolver: android.content.ContentResolver?, name: String?): Int = 0
    }

    object Global {

        @JvmStatic
        fun getString(resolver: android.content.ContentResolver?, name: String?): String? = null
    }
}
