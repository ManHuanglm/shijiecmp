/*
 * Crawler compat empty stubs (auto generated):
 * guarantee class resolution for crawler jars; add members on demand.
 */
package android.text

open class Layout
open class PrecomputedText
open class StaticLayout
open class TextDirectionHeuristic
open class TextDirectionHeuristics
open class TextPaint

/** 桌面 stub：TextWatcher 标记接口（pg Config 等爬虫实现该接口，仅满足类加载校验） */
interface TextWatcher {
    fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    fun afterTextChanged(s: Editable?) {}
}
