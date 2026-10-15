package android.annotation

/** 安忆 easy 系 jar 引用面补充桩（2026-10-13 常量池扫描差集） */

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
annotation class SuppressLint(val value: Array<String> = [])

