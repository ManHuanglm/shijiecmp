package android.content

/** 桌面 stub：找不到 Activity（仅类加载校验，桌面端不会走到） */
class ActivityNotFoundException : RuntimeException {

    constructor() : super()

    constructor(message: String?) : super(message)
}

/** 桌面 stub：剪贴板数据（静态构造存文本） */
class ClipData private constructor(private val text: String?) {

    fun getItemAt(index: Int): ClipData.Item = ClipData.Item(text)

    class Item private constructor(private val text: String?) {

        fun getText(): CharSequence = text.orEmpty()

        constructor(text: CharSequence?) : this(text?.toString())
    }

    companion object {

        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData =
            ClipData(text?.toString())
    }
}

/** 桌面 stub：剪贴板（桌面端写入为空操作） */
class ClipboardManager {

    fun setPrimaryClip(clip: ClipData?) = Unit

    fun getPrimaryClip(): ClipData? = null

    fun getText(): CharSequence? = null
}

/** 桌面 stub：组件名（存包名/类名字符串） */
class ComponentName {

    private val packageName: String?
    private val className: String?

    constructor(pkg: String?, cls: String?) {
        packageName = pkg
        className = cls
    }

    fun getPackageName(): String? = packageName

    fun getClassName(): String? = className

    override fun toString(): String = "ComponentName{$packageName/$className}"
}

/** 桌面 stub：Intent 最小实现（爬虫用于跳转提示类场景，桌面端不执行） */
class Intent {

    private var action: String? = null
    private var data: android.net.Uri? = null
    private var component: ComponentName? = null

    constructor()

    constructor(action: String?) {
        this.action = action
    }

    fun getAction(): String? = action

    fun setData(data: android.net.Uri?): Intent = apply { this.data = data }

    fun getData(): android.net.Uri? = data

    fun setComponent(component: ComponentName?): Intent = apply { this.component = component }

    fun getComponent(): ComponentName? = component

    fun setPackage(pkg: String?): Intent = this

    fun putExtra(name: String?, value: Any?): Intent = this

    companion object {

        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
    }
}

/** 桌面 stub：ContentProvider/ContentResolver（爬虫极少用，壳实现即可） */
abstract class ContentProvider {

    abstract fun onCreate(): Boolean
}

class ContentResolver {

    fun getType(uri: android.net.Uri?): String? = null

    fun insert(uri: android.net.Uri?, values: ContentValues?): android.net.Uri? = null
}

/** 桌面 stub：对话框回调接口（仅满足签名引用） */
interface DialogInterface {

    fun dismiss()

    interface OnCancelListener {
        fun onCancel(dialog: DialogInterface?)
    }

    interface OnClickListener {
        fun onClick(dialog: DialogInterface?, which: Int)
    }

    interface OnDismissListener {
        fun onDismiss(dialog: DialogInterface?)
    }

    interface OnShowListener {
        fun onShow(dialog: DialogInterface?)
    }
}
