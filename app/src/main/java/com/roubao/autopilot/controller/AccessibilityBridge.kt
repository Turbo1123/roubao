package com.roubao.autopilot.controller

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import com.roubao.autopilot.service.RoubaoAccessibilityService
import com.roubao.autopilot.service.UiElementInfo

/**
 * 无障碍控制桥 - 持有无障碍服务实例，供 DeviceController 调用
 *
 * 当用户在设置中把「控制方式」切换为无障碍时，DeviceController 通过本桥
 * 执行点击、滑动、系统按键、文字输入与截图，不再依赖 Shizuku。
 */
object AccessibilityBridge {

    @Volatile
    private var service: RoubaoAccessibilityService? = null

    fun attach(service: RoubaoAccessibilityService) {
        this.service = service
    }

    fun detach(service: RoubaoAccessibilityService) {
        if (this.service === service) {
            this.service = null
        }
    }

    /**
     * 无障碍服务是否已连接（已开启且系统已绑定服务）
     */
    fun isConnected(): Boolean = service != null

    /**
     * 用户是否已在系统设置中开启本应用的无障碍服务
     */
    fun isEnabled(context: Context): Boolean {
        return try {
            if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                return false
            }
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.split(':').any { entry ->
                val component = ComponentName.unflattenFromString(entry)
                component != null &&
                    component.packageName == context.packageName &&
                    component.className.endsWith("RoubaoAccessibilityService")
            }
        } catch (e: Exception) {
            false
        }
    }

    fun tap(x: Int, y: Int): Boolean = service?.tap(x, y) ?: false

    fun longPress(x: Int, y: Int, durationMs: Int = 1000): Boolean =
        service?.longPress(x, y, durationMs) ?: false

    fun doubleTap(x: Int, y: Int): Boolean = service?.doubleTap(x, y) ?: false

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500): Boolean =
        service?.swipe(x1, y1, x2, y2, durationMs) ?: false

    fun back(): Boolean = service?.back() ?: false

    fun home(): Boolean = service?.home() ?: false

    fun enter(): Boolean = service?.enter() ?: false

    fun typeText(text: String): Boolean = service?.typeText(text) ?: false

    fun captureScreenshot(): Bitmap? = service?.captureScreenshot()

    fun screenSize(): Pair<Int, Int>? = service?.screenSize()

    // ===== 元素树感知与节点直达（借鉴 droidrun / dsh-go 的树优先感知） =====

    fun snapshotElements(): List<UiElementInfo> = service?.snapshotElements() ?: emptyList()

    fun clickElement(index: Int): Boolean = service?.clickElement(index) ?: false

    fun longClickElement(index: Int): Boolean = service?.longClickElement(index) ?: false

    fun doubleTapElement(index: Int): Boolean = service?.doubleTapElement(index) ?: false

    fun setTextOnElement(index: Int, text: String): Boolean =
        service?.setTextOnElement(index, text) ?: false
}
