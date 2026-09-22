package android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 桌面 stub：Adapter 标记接口（仅类加载校验） */
interface Adapter {

    fun getCount(): Int

    fun getItem(position: Int): Any?

    fun getItemId(position: Int): Long

    fun getView(position: Int, convertView: View?, parent: ViewGroup?): View
}

/** 桌面 stub：ListAdapter 标记接口（仅类加载校验） */
interface ListAdapter : Adapter
