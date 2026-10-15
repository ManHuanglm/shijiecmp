package android.widget

import android.view.ViewGroup

/** 安忆 easy 系 jar 引用面补充桩 */

open class AdapterView<T> : ViewGroup() {

    /** pg Config 等爬虫实现该监听（桌面端不触发，仅满足类加载校验） */
    interface OnItemClickListener {

        fun onItemClick(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long)
    }

    interface OnItemSelectedListener {

        fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long)

        fun onNothingSelected(parent: AdapterView<*>?)
    }
}

open class ListView : AdapterView<Any>()

