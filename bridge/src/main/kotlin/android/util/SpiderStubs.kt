/*
 * Crawler compat empty stubs (auto generated):
 * guarantee class resolution for crawler jars; add members on demand.
 */
package android.util

open class AtomicFile
open class AttributeSet
open class Half
open class Range
open class Size
open class SizeF
open class SparseArray
open class SparseIntArray
open class SparseLongArray
open class StateSet
open class Xml

/** 桌面 stub：LRU 缓存（pg Config 图片缓存等用；sizeOf 可被子类覆写） */
open class LruCache<K, V>(private val maxSize: Int) {

    open fun sizeOf(key: K?, value: V?): Int = 1

    open fun entryRemoved(evicted: Boolean, key: K?, oldValue: V?, newValue: V?) = Unit

    fun get(key: K?): V? = null

    fun put(key: K?, value: V?): V? = null

    fun remove(key: K?): V? = null

    fun size(): Int = 0

    fun evictAll() = Unit
}