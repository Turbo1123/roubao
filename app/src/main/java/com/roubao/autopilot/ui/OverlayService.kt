package com.roubao.autopilot.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roubao.autopilot.MainActivity
import com.roubao.autopilot.R

/**
 * 七彩悬浮窗服务 - 显示当前执行步骤
 * 放在屏幕顶部状态栏下方，不影响截图识别
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var textView: TextView? = null
    private var actionButton: TextView? = null
    private var cancelButton: TextView? = null  // 确认模式下的取消按钮
    private var divider: View? = null
    private var divider2: View? = null  // 确认模式下第二个分隔线
    private var animator: ValueAnimator? = null

    companion object {
        private var instance: OverlayService? = null
        private var stopCallback: (() -> Unit)? = null
        private var continueCallback: (() -> Unit)? = null
        private var confirmCallback: ((Boolean) -> Unit)? = null  // 敏感操作确认回调
        private var isTakeOverMode = false
        private var isConfirmMode = false  // 敏感操作确认模式

        // 等待 instance 回调队列
        private val pendingCallbacks = mutableListOf<() -> Unit>()

        fun show(context: Context, text: String, onStop: (() -> Unit)? = null) {
            stopCallback = onStop
            isTakeOverMode = false
            isConfirmMode = false
            instance?.updateText(text) ?: run {
                val intent = Intent(context, OverlayService::class.java).apply {
                    putExtra("text", text)
                }
                ContextCompat.startForegroundService(context, intent)
            }
            instance?.setNormalMode()
        }

        fun hide(context: Context) {
            stopCallback = null
            continueCallback = null
            confirmCallback = null
            isTakeOverMode = false
            isConfirmMode = false
            pendingCallbacks.clear()
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun update(text: String) {
            instance?.updateText(text)
        }

        /** 截图时临时隐藏悬浮窗 */
        fun setVisible(visible: Boolean) {
            instance?.overlayView?.post {
                instance?.overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
        }

        /** 显示人机协作模式 - 等待用户手动完成操作 */
        fun showTakeOver(message: String, onContinue: () -> Unit) {
            val action: () -> Unit = {
                println("[OverlayService] showTakeOver: $message")
                continueCallback = onContinue
                isTakeOverMode = true
                isConfirmMode = false
                instance?.setTakeOverMode(message)
                Unit
            }

            if (instance != null) {
                action()
            } else {
                // 悬浮窗尚未启动，加入等待队列
                println("[OverlayService] showTakeOver: instance is null, queuing...")
                pendingCallbacks.add(action)
            }
        }

        /** 显示敏感操作确认模式 - 用户确认或取消 */
        fun showConfirm(message: String, onConfirm: (Boolean) -> Unit) {
            val action: () -> Unit = {
                println("[OverlayService] showConfirm: $message")
                confirmCallback = onConfirm
                isConfirmMode = true
                isTakeOverMode = false
                instance?.setConfirmMode(message)
                Unit
            }

            if (instance != null) {
                action()
            } else {
                // 悬浮窗尚未启动，加入等待队列
                println("[OverlayService] showConfirm: instance is null, queuing...")
                pendingCallbacks.add(action)
            }
        }

        /** 当 instance 可用时执行等待中的回调 */
        private fun processPendingCallbacks() {
            println("[OverlayService] processPendingCallbacks: ${pendingCallbacks.size} pending")
            pendingCallbacks.forEach { it.invoke() }
            pendingCallbacks.clear()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 必须第一时间调用 startForeground，否则会崩溃
        startForegroundNotification()

        // 创建悬浮窗（可能因权限问题失败）
        try {
            createOverlayView()
        } catch (e: Exception) {
            println("[OverlayService] createOverlayView failed: ${e.message}")
        }

        // 处理在 service 启动前排队的回调
        processPendingCallbacks()
    }

    private fun startForegroundNotification() {
        val channelId = "baozi_overlay"
        val channelName = "肉包状态"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示肉包执行状态"
                    setShowBadge(false)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("肉包运行中")
                .setContentText("正在执行自动化任务...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(1001, notification)
        } catch (e: Exception) {
            println("[OverlayService] startForegroundNotification error: ${e.message}")
            // 降级：使用最简单的通知确保 startForeground 被调用
            try {
                val fallbackNotification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle("肉包")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build()
                startForeground(1001, fallbackNotification)
            } catch (e2: Exception) {
                println("[OverlayService] fallback startForeground also failed: ${e2.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "AutoPilot"
        updateText(text)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        animator?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        // 容器
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
        }

        // 七彩渐变背景
        val gradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
            setStroke(2, Color.WHITE)
        }
        container.background = gradientDrawable

        // 状态文字
        textView = TextView(this).apply {
            text = "肉包"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 4)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(textView)

        // 分隔线
        divider = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0.5f
        }
        val dividerParams = LinearLayout.LayoutParams(2, 36).apply {
            setMargins(12, 0, 12, 0)
        }
        container.addView(divider, dividerParams)

        // 动作按钮（停止/继续/确认）
        actionButton = TextView(this).apply {
            text = "⏹ 停止"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 4)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                when {
                    isConfirmMode -> {
                        // 确认模式：点击确认
                        confirmCallback?.invoke(true)
                        confirmCallback = null
                        isConfirmMode = false
                        setNormalMode()
                    }
                    isTakeOverMode -> {
                        // 人机协作模式：点击继续
                        continueCallback?.invoke()
                        continueCallback = null
                        isTakeOverMode = false
                        setNormalMode()
                    }
                    else -> {
                        // 正常模式：点击停止
                        stopCallback?.invoke()
                        hide(this@OverlayService)
                    }
                }
            }
        }
        container.addView(actionButton)

        // 第二个分隔线（确认模式用）
        divider2 = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0.5f
            visibility = View.GONE
        }
        val divider2Params = LinearLayout.LayoutParams(2, 36).apply {
            setMargins(12, 0, 12, 0)
        }
        container.addView(divider2, divider2Params)

        // 取消按钮（确认模式用）
        cancelButton = TextView(this).apply {
            text = "❌ 取消"
            textSize = 13f
            setTextColor(Color.parseColor("#FF6B6B"))  // 红色
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 4)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setOnClickListener {
                if (isConfirmMode) {
                    confirmCallback?.invoke(false)
                    confirmCallback = null
                    isConfirmMode = false
                    setNormalMode()
                }
            }
        }
        container.addView(cancelButton)

        // 动画：七彩渐变流动效果
        startRainbowAnimation(gradientDrawable)

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  // 保持屏幕常亮
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // 添加拖动功能
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 10f

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (kotlin.math.abs(deltaX) > dragThreshold || kotlin.math.abs(deltaY) > dragThreshold) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 如果不是拖动，传递点击事件给子 View
                        false
                    } else {
                        true
                    }
                }
                else -> false
            }
        }

        overlayView = container
        windowManager?.addView(overlayView, params)
    }

    private fun startRainbowAnimation(drawable: GradientDrawable) {
        val colors = intArrayOf(
            Color.parseColor("#FF6B6B"), // 红
            Color.parseColor("#FFA94D"), // 橙
            Color.parseColor("#FFE066"), // 黄
            Color.parseColor("#69DB7C"), // 绿
            Color.parseColor("#4DABF7"), // 蓝
            Color.parseColor("#9775FA"), // 紫
            Color.parseColor("#F783AC"), // 粉
            Color.parseColor("#FF6B6B")  // 回到红
        )

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val index = (fraction * (colors.size - 1)).toInt()
                val nextIndex = minOf(index + 1, colors.size - 1)
                val localFraction = (fraction * (colors.size - 1)) - index

                val color1 = interpolateColor(colors[index], colors[nextIndex], localFraction)
                val color2 = interpolateColor(
                    colors[(index + 2) % colors.size],
                    colors[(nextIndex + 2) % colors.size],
                    localFraction
                )
                val color3 = interpolateColor(
                    colors[(index + 4) % colors.size],
                    colors[(nextIndex + 4) % colors.size],
                    localFraction
                )

                drawable.colors = intArrayOf(color1, color2, color3)
                drawable.orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            start()
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)

        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)

        return Color.argb(
            (startA + (endA - startA) * fraction).toInt(),
            (startR + (endR - startR) * fraction).toInt(),
            (startG + (endG - startG) * fraction).toInt(),
            (startB + (endB - startB) * fraction).toInt()
        )
    }

    private fun updateText(text: String) {
        textView?.post {
            textView?.text = text
        }
    }

    /** 切换到人机协作模式 */
    private fun setTakeOverMode(message: String) {
        println("[OverlayService] setTakeOverMode: $message")
        overlayView?.post {
            // 确保悬浮窗可见
            overlayView?.visibility = View.VISIBLE
            textView?.text = "🖐 $message"
            actionButton?.text = "✅ 继续"
            actionButton?.setTextColor(Color.parseColor("#90EE90")) // 浅绿色
            // 隐藏取消按钮（人机协作只有继续按钮）
            divider2?.visibility = View.GONE
            cancelButton?.visibility = View.GONE
        }
    }

    /** 切换到正常模式 */
    private fun setNormalMode() {
        println("[OverlayService] setNormalMode")
        overlayView?.post {
            actionButton?.text = "⏹ 停止"
            actionButton?.setTextColor(Color.WHITE)
            // 隐藏取消按钮和第二分隔线
            divider2?.visibility = View.GONE
            cancelButton?.visibility = View.GONE
        }
    }

    /** 切换到敏感操作确认模式 */
    private fun setConfirmMode(message: String) {
        println("[OverlayService] setConfirmMode: $message")
        overlayView?.post {
            // 确保悬浮窗可见
            overlayView?.visibility = View.VISIBLE
            textView?.text = "⚠️ $message"
            actionButton?.text = "✅ 确认"
            actionButton?.setTextColor(Color.parseColor("#90EE90"))  // 浅绿色
            // 显示取消按钮和第二分隔线
            divider2?.visibility = View.VISIBLE
            cancelButton?.visibility = View.VISIBLE
        }
    }
}
