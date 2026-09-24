package com.shiping.app.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.ReaderException
import com.google.zxing.LuminanceSource
import com.shiping.app.ShipingGraph
import com.shiping.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TVBox 配置扫码导入页：CameraX 取景 + zxing 解码 QR_CODE。
 * 识别成功页内直接导入（多仓配置自动导入第一个子仓），Toast 结果后关闭。
 */
class ScanActivity : ComponentActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var hintText: TextView
    private lateinit var settingsButton: Button

    /** 单次识别防抖：成功即停止处理后续帧 */
    @Volatile
    private var handled = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContentView()
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 纯代码布局：取景面 + 底部提示（无 XML 资源依赖） */
    private fun buildContentView() {
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        previewView = androidx.camera.view.PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        hintText = TextView(this).apply {
            text = "将 TVBox 配置二维码对准镜头，识别后自动导入"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL }
            setPadding(0, 0, 0, 96)
        }
        settingsButton = Button(this).apply {
            text = "去系统设置开启相机权限"
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.gravity = android.view.Gravity.CENTER }
        }
        settingsButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
        root.addView(previewView)
        root.addView(hintText)
        root.addView(settingsButton)
        setContentView(root)
    }

    private fun showPermissionDenied() {
        hintText.text = "未授予相机权限，无法扫码"
        settingsButton.visibility = android.view.View.VISIBLE
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            ContextCompat.getMainExecutor(this),
                            QrCodeAnalyzer(::onQrDecoded),
                        )
                    }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { AppLog.e(TAG, "相机启动失败: ${it.message}", it) }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 识别成功：导入 TVBox 配置（多仓取第一个子仓），Toast 结果后关闭 */
    private fun onQrDecoded(text: String) {
        if (handled) return
        handled = true
        Toast.makeText(this, "识别成功，正在导入配置…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val message = runCatching {
                val result = ShipingGraph.tvBoxImporterService.import(text, "扫码导入")
                val first = result.warehouses.firstOrNull()
                if (first != null) {
                    ShipingGraph.tvBoxImporterService.import(first.second, first.first).message
                } else {
                    result.message
                }
            }.getOrElse { "导入失败：${it.message ?: "未知错误"}" }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ScanActivity, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private companion object {
        const val TAG = "Scan"
    }
}

/**
 * CameraX 图像分析器：YUV_420_888 亮度平面 → PlanarYUVLuminanceSource
 * → HybridBinarizer → MultiFormatReader 解码 QR_CODE。
 */
private class QrCodeAnalyzer(
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader()

    init {
        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(buildLuminanceSource(image)))
            val result = reader.decodeWithState(bitmap)
            onResult(result.text)
        } catch (_: ReaderException) {
            // 当前帧未识别出二维码，忽略并继续下一帧
        } finally {
            reader.reset()
            image.close()
        }
    }

    /** 提取 Y 平面亮度数据（兼容 rowStride/pixelStride），并按相机旋转角校正方向 */
    private fun buildLuminanceSource(image: ImageProxy): LuminanceSource {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val data = ByteArray(width * height)
        var index = 0
        for (row in 0 until height) {
            var offset = row * rowStride
            repeat(width) {
                data[index++] = buffer.get(offset)
                offset += pixelStride
            }
        }
        // 逆时针旋转补偿相机传感器角度，保证 zxing 读到正向图像。
        // PlanarYUVLuminanceSource 不支持 rotateCounterClockwise（会抛
        // UnsupportedOperationException），需手动旋转 Y 平面字节后重建源
        var rotatedData = data
        var rotatedWidth = width
        var rotatedHeight = height
        repeat(image.imageInfo.rotationDegrees / 90) {
            val result = ByteArray(rotatedData.size)
            for (y in 0 until rotatedHeight) {
                for (x in 0 until rotatedWidth) {
                    result[(rotatedWidth - 1 - x) * rotatedHeight + y] =
                        rotatedData[y * rotatedWidth + x]
                }
            }
            val tmp = rotatedWidth
            rotatedWidth = rotatedHeight
            rotatedHeight = tmp
            rotatedData = result
        }
        return PlanarYUVLuminanceSource(
            rotatedData, rotatedWidth, rotatedHeight, 0, 0, rotatedWidth, rotatedHeight, false,
        )
    }
}
