package com.roubao.autopilot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.roubao.autopilot.controller.AccessibilityBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务 - 作为 Shizuku 之外的另一种设备控制方式
 *
 * 在设置中将「控制方式」切换为无障碍后，DeviceController 的点击、滑动、
 * 返回/Home、文字输入与截图（Android 11+）都由本服务完成，无需 Shizuku。
 * 服务实例通过 [AccessibilityBridge] 暴露给控制器使用。
 */
class RoubaoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
        println("[AccessibilityService] 无障碍服务已连接")
    }

    override fun onDestroy() {
        recycleSnapshot()
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理无障碍事件，仅使用手势与全局操作能力
    }

    override fun onInterrupt() {
    }

    /**
     * 派发一段手势并等待执行完成
     */
    private fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        return try {
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val latch = CountDownLatch(1)
            var completed = false
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!accepted) {
                return false
            }
            latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
            completed
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchStroke(path, 50)
    }

    fun longPress(x: Int, y: Int, durationMs: Int = 1000): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchStroke(path, durationMs.toLong())
    }

    fun doubleTap(x: Int, y: Int): Boolean {
        val first = tap(x, y)
        try {
            Thread.sleep(120)
        } catch (e: InterruptedException) {
            // 忽略中断，继续第二次点击
        }
        return tap(x, y) && first
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatchStroke(path, durationMs.toLong())
    }

    fun back(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun home(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 回车 - 对当前输入焦点节点发送 IME 回车动作（Android 11+）
     */
    fun enter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return try {
            val target = findInputTarget() ?: return false
            val supported = target.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            }
            val result = supported && target.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
            target.recycle()
            result
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 输入文本 - 优先剪贴板粘贴（在光标处追加），失败时用 ACTION_SET_TEXT 兜底
     */
    fun typeText(text: String): Boolean {
        return try {
            val target = findInputTarget() ?: return false
            if (!target.isFocused) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }

            // 方法1: 剪贴板 + 粘贴
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("baozi_input", text))
                if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    target.recycle()
                    println("[AccessibilityService] ✅ 已通过粘贴输入: $text")
                    return true
                }
            }

            // 方法2: 直接设置文本（在已有内容后追加）
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    (target.text?.toString() ?: "") + text
                )
            }
            val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            target.recycle()
            result
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 截图（Android 11+，使用无障碍 takeScreenshot）
     */
    fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return try {
            val latch = CountDownLatch(1)
            var result: Bitmap? = null
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            result = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware?.recycle()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            screenshot.hardwareBuffer.close()
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        println("[AccessibilityService] 截图失败, errorCode: $errorCode")
                        latch.countDown()
                    }
                }
            )
            latch.await(3, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 屏幕尺寸（宽, 高）
     */
    fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    // ================= 元素树感知与节点直达操作 =================
    // 借鉴 droidrun/mobilerun 与 dsh-go 的「无障碍树优先」感知：把当前窗口的节点
    // 整理成带序号的元素清单给模型，模型按序号点选时直接对真实节点执行动作，
    // 不再依赖截图坐标猜测，从根本上消除坐标换算带来的点击偏移。

    /** 最近一次快照的元素节点（与返回清单的序号一一对应），下次快照时统一回收 */
    private var snapshotNodes: List<AccessibilityNodeInfo> = emptyList()

    /**
     * 抓取当前窗口的元素清单：只保留可见且「可交互或带文字」的节点，最多 150 个。
     * 同时保存节点引用，供 clickElement 等按序号直达。
     */
    fun snapshotElements(): List<UiElementInfo> {
        recycleSnapshot()
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<UiElementInfo>()
        val keptNodes = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            // 先取出子节点（父节点回收后子引用仍有效）
            val children = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { children.add(it) }
            }
            // 逆序入栈，保证遍历顺序接近界面文档顺序
            for (i in children.size - 1 downTo 0) {
                stack.addLast(children[i])
            }

            var keep = false
            if (result.size < 150) {
                try {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val label = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    val interactive = node.isClickable || node.isLongClickable ||
                        node.isScrollable || node.isEditable
                    if (node.isVisibleToUser && !bounds.isEmpty && (interactive || label != null)) {
                        val classShort = node.className?.toString()?.substringAfterLast('.') ?: "View"
                        val key = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|$label|$classShort"
                        if (seen.add(key)) {
                            result.add(
                                UiElementInfo(
                                    index = result.size,
                                    label = label ?: "",
                                    className = classShort,
                                    bounds = Rect(bounds),
                                    clickable = node.isClickable,
                                    longClickable = node.isLongClickable,
                                    editable = node.isEditable,
                                    scrollable = node.isScrollable
                                )
                            )
                            keep = true
                        }
                    }
                } catch (e: Exception) {
                    // 单个节点异常不影响整棵树
                }
            }
            if (keep) {
                keptNodes.add(node)
            } else {
                try {
                    node.recycle()
                } catch (e: Exception) {
                    // 忽略回收异常
                }
            }
        }
        snapshotNodes = keptNodes
        return result
    }

    private fun recycleSnapshot() {
        snapshotNodes.forEach {
            try {
                it.recycle()
            } catch (e: Exception) {
                // 忽略回收异常
            }
        }
        snapshotNodes = emptyList()
    }

    /**
     * 按序号点击元素：优先对节点（或最近的可点击祖先）执行 ACTION_CLICK，
     * 系统层面的真实点击，不经过坐标；失败再用元素中心坐标手势兜底。
     */
    fun clickElement(index: Int): Boolean = elementAction(index, longClick = false)

    fun longClickElement(index: Int): Boolean = elementAction(index, longClick = true)

    private fun elementAction(index: Int, longClick: Boolean): Boolean {
        val node = snapshotNodes.getOrNull(index) ?: return false
        return try {
            val obtained = mutableListOf<AccessibilityNodeInfo>()
            var target: AccessibilityNodeInfo? = node
            var acted = false
            var hops = 0
            while (target != null && hops < 5) {
                val canAct = if (longClick) {
                    target.isLongClickable || target.isClickable
                } else {
                    target.isClickable
                }
                if (canAct && target.isEnabled) {
                    val actionId = if (longClick && target.isLongClickable) {
                        AccessibilityNodeInfo.ACTION_LONG_CLICK
                    } else {
                        AccessibilityNodeInfo.ACTION_CLICK
                    }
                    acted = target.performAction(actionId)
                    break
                }
                val parent = target.parent ?: break
                obtained.add(parent)
                target = parent
                hops++
            }
            obtained.forEach {
                try {
                    it.recycle()
                } catch (e: Exception) {
                    // 忽略回收异常
                }
            }
            if (acted) {
                true
            } else {
                // 节点动作失败：用元素在屏幕上的真实边界中心点手势兜底（边界来自系统，仍然精确）
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) {
                    false
                } else if (longClick) {
                    longPress(bounds.centerX(), bounds.centerY())
                } else {
                    tap(bounds.centerX(), bounds.centerY())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 按序号双击元素：用元素中心坐标双击。
     */
    fun doubleTapElement(index: Int): Boolean {
        val node = snapshotNodes.getOrNull(index) ?: return false
        return try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) false else doubleTap(bounds.centerX(), bounds.centerY())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 按序号给输入框直接设置文本（ACTION_SET_TEXT，整段替换，不依赖键盘与剪贴板）。
     */
    fun setTextOnElement(index: Int, text: String): Boolean {
        val node = snapshotNodes.getOrNull(index) ?: return false
        return try {
            if (!node.isEditable) {
                return false
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 查找当前可输入的节点（输入焦点优先，其次任意获得焦点的可编辑节点）
     */
    private fun findInputTarget(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val inputFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocus != null && inputFocus.isEditable) {
            return inputFocus
        }
        inputFocus?.recycle()
        val found = findEditableFocused(root)
        if (found == null) {
            root.recycle()
        }
        return found
    }

    private fun findEditableFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableFocused(child)
            if (result != null) {
                return result
            }
            child.recycle()
        }
        return null
    }
}

/**
 * 元素树清单中的一个元素（序号与快照时的节点顺序一致）
 */
data class UiElementInfo(
    val index: Int,
    val label: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean
)
