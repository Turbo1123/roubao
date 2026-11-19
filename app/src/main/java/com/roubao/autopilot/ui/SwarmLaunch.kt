package com.roubao.autopilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.ui.theme.BaoziTheme
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 冷启动 swarm 光标动画（仿开源 Swarm 效果的原生实现，10 秒叙事版）：
 * 20 个带名字标签的彩色光标像协作者一样飞入，先蜂群盘旋，再有的拖选一段文字、
 * 有的框选中央标志、有的在输入框里打字并点发送，最后全体绕标志成环、散开淡出。
 * 只在 App 冷启动时播放一次，点击任意处跳过。
 * 纯装饰动画：内部状态都是普通变量（不在绘制阶段写快照状态），绘制异常会被吞掉并直接结束动画。
 */
private const val SWARM_TOTAL_MS = 10000L
private const val SWARM_GREETING = "嗨！今天想聊些什么？"

private class SwarmCursor(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Color,
    val name: String,
    val ringAngle: Float,
    val seed: Float,
    val orbitR: Float
)

/** 动画运行时状态（普通变量，不参与 Compose 快照） */
private class SwarmRuntime {
    val cursors = mutableListOf<SwarmCursor>()
    var prevFrameMs: Long = -1L
    var failed: Boolean = false
}

private val SWARM_COLORS = listOf(
    Color(0xFF4D6BFE), Color(0xFFF65A8C), Color(0xFFFF8A3D), Color(0xFF2FBF71),
    Color(0xFF8B5CF6), Color(0xFF14B8A6), Color(0xFFEF4444), Color(0xFFF5B83D),
    Color(0xFF38BDF8), Color(0xFFFB7185), Color(0xFFA3E635), Color(0xFFF472B6),
    Color(0xFF60A5FA), Color(0xFFFACC15)
)

private val SWARM_NAMES = listOf(
    "Saak", "Daap", "Nova", "Echo", "Pix", "Neo",
    "Luna", "Ivy", "Rex", "Milo", "Zoe", "Ada",
    "Leo", "Muse", "Kiki", "Omar", "Pipi", "Yuki", "Finn", "Gus"
)

private fun easeInOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun SwarmLaunchAnimation(
    darkTheme: Boolean,
    onFinished: () -> Unit
) {
    val colors = BaoziTheme.colors
    val localDensity = LocalDensity.current
    val dpPx = with(localDensity) { 1.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val runtime = remember { SwarmRuntime() }

    var startMs by remember { mutableStateOf(-1L) }
    var frameMs by remember { mutableStateOf(0L) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // 名字标签与打字文本的排版预先量好，避免每帧重复测量
    val tagStyle = remember {
        TextStyle(color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
    }
    val tagLayouts: List<TextLayoutResult> = remember {
        SWARM_NAMES.map { textMeasurer.measure(it, tagStyle) }
    }
    val typeStyle = remember {
        TextStyle(color = Color(0xFFE8E8E8), fontSize = 14.sp)
    }
    val typeLayouts: List<TextLayoutResult> = remember {
        (0..SWARM_GREETING.length).map { textMeasurer.measure(SWARM_GREETING.substring(0, it), typeStyle) }
    }

    // 尺寸确定后生成光标（从屏幕四边外飞入）
    LaunchedEffect(canvasSize) {
        val w = canvasSize.width
        val h = canvasSize.height
        if (w > 0f && h > 0f && runtime.cursors.isEmpty()) {
            val rnd = Random(42)
            for (i in SWARM_NAMES.indices) {
                val (sx, sy) = when (i % 4) {
                    0 -> Pair(rnd.nextFloat() * w, -40f)
                    1 -> Pair(w + 40f, rnd.nextFloat() * h)
                    2 -> Pair(rnd.nextFloat() * w, h + 40f)
                    else -> Pair(-40f, rnd.nextFloat() * h)
                }
                runtime.cursors.add(
                    SwarmCursor(
                        x = sx, y = sy, vx = 0f, vy = 0f,
                        color = SWARM_COLORS[i % SWARM_COLORS.size],
                        name = SWARM_NAMES[i],
                        ringAngle = (i.toFloat() / SWARM_NAMES.size) * 360f,
                        seed = rnd.nextFloat() * 100f,
                        orbitR = (64f + rnd.nextFloat() * 92f)
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { now ->
                if (startMs < 0L) {
                    startMs = now
                }
                frameMs = now
            }
            if (runtime.failed) {
                break
            }
            if (startMs > 0L && frameMs - startMs > SWARM_TOTAL_MS) {
                break
            }
        }
        onFinished()
    }

    val elapsed = if (startMs > 0L) frameMs - startMs else 0L
    val t = (elapsed.toFloat() / SWARM_TOTAL_MS).coerceIn(0f, 1f)
    // 最后 8% 整体淡出到主界面
    val overlayAlpha = when {
        t < 0.92f -> 1f
        else -> (1f - (t - 0.92f) / 0.08f).coerceIn(0f, 1f)
    }
    // 中央标志在被框选前淡入，成环阶段最亮
    val logoAlpha = easeInOut((t - 0.14f) / 0.10f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = overlayAlpha)
            .background(colors.background)
            .pointerInput(Unit) {
                detectTapGestures { onFinished() }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
        ) {
            try {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f || runtime.cursors.isEmpty()) {
                    return@Canvas
                }
                val cx = w / 2f
                val cy = h / 2f
                val cursors = runtime.cursors
                val primary = Color(0xFF4D6BFE)

                val dtMs = if (runtime.prevFrameMs < 0L) {
                    16f
                } else {
                    (frameMs - runtime.prevFrameMs).toFloat().coerceIn(1f, 50f)
                }
                runtime.prevFrameMs = frameMs
                val dtScale = dtMs / 16.7f
                val timeSec = elapsed / 1000f

                // ---------- 场景几何 ----------
                // 顶部"文档"：5 行文字条
                val docW = w * 0.56f
                val docX = cx - docW / 2f
                val docY = h * 0.235f
                val lineH = 9f * dpPx
                val lineGap = 10f * dpPx
                val lineFrac = listOf(1f, 0.92f, 0.97f, 0.85f, 0.58f)
                val docAlpha = easeInOut((t - 0.17f) / 0.05f) * (1f - easeInOut((t - 0.62f) / 0.06f))
                // 中央标志的框选范围
                val logoBoxW = 156f * dpPx
                val logoBoxH = 148f * dpPx
                val logoBoxX = cx - logoBoxW / 2f
                val logoBoxY = cy - 64f * dpPx
                val logoDragP = easeInOut((t - 0.21f) / 0.20f)
                val logoBoxAlpha = if (t < 0.62f) logoDragP else (1f - easeInOut((t - 0.62f) / 0.06f))
                // 底部输入框
                val barW = w * 0.74f
                val barH = 48f * dpPx
                val barX = cx - barW / 2f
                val barY = h * 0.775f
                val barAlpha = easeInOut((t - 0.42f) / 0.05f) * (1f - easeInOut((t - 0.80f) / 0.06f))
                val sendCenter = Offset(barX + barW - 25f * dpPx, barY + barH / 2f)
                // 打字进度
                val typeP = ((t - 0.45f) / 0.17f).coerceIn(0f, 1f)
                val typedCount = floor(typeP * SWARM_GREETING.length).toInt()
                val typedLayout = typeLayouts[typedCount]
                val caretX = barX + 16f * dpPx + typedLayout.size.width

                // ---------- 文档与文字拖选 ----------
                if (docAlpha > 0f) {
                    val barColor = Color(0xFF9A9A9A).copy(alpha = 0.35f * docAlpha)
                    for (i in lineFrac.indices) {
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(docX, docY + i * (lineH + lineGap)),
                            size = Size(docW * lineFrac[i], lineH),
                            cornerRadius = CornerRadius(lineH / 2f, lineH / 2f)
                        )
                    }
                    // 拖选高亮：逐行扫过去
                    val selP = easeInOut((t - 0.20f) / 0.24f)
                    if (selP > 0f) {
                        val pos = selP * lineFrac.size
                        val curLine = floor(pos).toInt().coerceAtMost(lineFrac.size - 1)
                        val curFrac = (pos - floor(pos)).coerceIn(0f, 1f)
                        for (i in 0 until curLine) {
                            drawRoundRect(
                                color = primary.copy(alpha = 0.30f * docAlpha),
                                topLeft = Offset(docX, docY + i * (lineH + lineGap)),
                                size = Size(docW * lineFrac[i], lineH),
                                cornerRadius = CornerRadius(lineH / 2f, lineH / 2f)
                            )
                        }
                        drawRoundRect(
                            color = primary.copy(alpha = 0.30f * docAlpha),
                            topLeft = Offset(docX, docY + curLine * (lineH + lineGap)),
                            size = Size(docW * lineFrac[curLine] * curFrac, lineH),
                            cornerRadius = CornerRadius(lineH / 2f, lineH / 2f)
                        )
                    }
                }

                // ---------- 标志框选 ----------
                if (logoBoxAlpha > 0f && logoDragP > 0f) {
                    val cornerX = logoBoxX + logoBoxW * logoDragP
                    val cornerY = logoBoxY + logoBoxH * logoDragP
                    drawRect(
                        color = primary.copy(alpha = 0.10f * logoBoxAlpha),
                        topLeft = Offset(logoBoxX, logoBoxY),
                        size = Size(cornerX - logoBoxX, cornerY - logoBoxY)
                    )
                    drawRect(
                        color = primary.copy(alpha = 0.9f * logoBoxAlpha),
                        topLeft = Offset(logoBoxX, logoBoxY),
                        size = Size(cornerX - logoBoxX, cornerY - logoBoxY),
                        style = Stroke(width = 1.4f * dpPx)
                    )
                }

                // ---------- 输入框与打字 ----------
                if (barAlpha > 0f) {
                    drawRoundRect(
                        color = Color(0xFF242424).copy(alpha = barAlpha),
                        topLeft = Offset(barX, barY),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barH / 2f, barH / 2f)
                    )
                    drawRoundRect(
                        color = Color(0xFF3A3A3A).copy(alpha = barAlpha),
                        topLeft = Offset(barX, barY),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barH / 2f, barH / 2f),
                        style = Stroke(width = 1f * dpPx)
                    )
                    if (typedCount > 0) {
                        drawText(
                            textLayoutResult = typedLayout,
                            topLeft = Offset(
                                barX + 16f * dpPx,
                                barY + (barH - typedLayout.size.height) / 2f
                            ),
                            alpha = barAlpha
                        )
                    }
                    // 光标竖线（打字阶段闪烁）
                    if (t in 0.44f..0.80f && (elapsed / 380L) % 2L == 0L) {
                        drawRect(
                            color = primary.copy(alpha = barAlpha),
                            topLeft = Offset(caretX + 2f * dpPx, barY + barH / 2f - 9f * dpPx),
                            size = Size(2f * dpPx, 18f * dpPx)
                        )
                    }
                    // 发送圆钮
                    drawCircle(
                        color = primary.copy(alpha = barAlpha),
                        radius = 15f * dpPx,
                        center = sendCenter
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = barAlpha),
                        radius = 5.5f * dpPx,
                        center = sendCenter
                    )
                }

                // ---------- 点击涟漪（都落在有意义的位置） ----------
                val ripples = listOf(
                    Triple(0.315f, Offset(docX + docW * 0.45f, docY + 2f * (lineH + lineGap)), SWARM_COLORS[2]),
                    Triple(0.635f, sendCenter, primary),
                    Triple(0.665f, Offset(cx, cy - 6f * dpPx), SWARM_COLORS[4])
                )
                for ((startT, pos, col) in ripples) {
                    val p = (t - startT) / 0.045f
                    if (p in 0f..1f) {
                        drawCircle(
                            color = col.copy(alpha = (1f - p) * 0.75f),
                            radius = (6f + p * 26f) * dpPx,
                            center = pos,
                            style = Stroke(width = 2f * dpPx)
                        )
                    }
                }

                // ---------- 光标目标分配 ----------
                val wanderX = cx + sin(timeSec * 0.9f) * 54f * dpPx
                val wanderY = cy + cos(timeSec * 1.2f) * 40f * dpPx
                val selPos = easeInOut((t - 0.20f) / 0.24f) * lineFrac.size
                val selLine = floor(selPos).toInt().coerceAtMost(lineFrac.size - 1)
                val selFrac = (selPos - floor(selPos)).coerceIn(0f, 1f)
                val selCursorPos = Offset(
                    docX + docW * lineFrac[selLine] * selFrac,
                    docY + selLine * (lineH + lineGap) + lineH
                )
                val logoCornerPos = Offset(
                    logoBoxX + logoBoxW * logoDragP,
                    logoBoxY + logoBoxH * logoDragP
                )
                val ringP = easeInOut((t - 0.65f) / 0.13f)
                val ringRadius = 122f * dpPx
                val ringRotation = timeSec * 26f

                for ((idx, c) in cursors.withIndex()) {
                    var steer = 0.055f
                    val target: Offset = when {
                        t >= 0.85f -> {
                            // 散开：沿远离中心方向飞出
                            val dx = c.x - cx
                            val dy = c.y - cy
                            val len = hypot(dx, dy).coerceAtLeast(1f)
                            Offset(c.x + dx / len * w * 0.8f, c.y + dy / len * h * 0.8f)
                        }
                        ringP > 0f -> {
                            val a = Math.toRadians((c.ringAngle + ringRotation).toDouble())
                            val ringPos = Offset(
                                cx + cos(a).toFloat() * ringRadius,
                                cy + sin(a).toFloat() * ringRadius
                            )
                            val flockA = Math.toRadians((c.ringAngle + timeSec * 55f + c.seed).toDouble())
                            val flockPos = Offset(
                                wanderX + cos(flockA).toFloat() * c.orbitR * dpPx,
                                wanderY + sin(flockA).toFloat() * c.orbitR * dpPx
                            )
                            Offset(
                                flockPos.x + (ringPos.x - flockPos.x) * ringP,
                                flockPos.y + (ringPos.y - flockPos.y) * ringP
                            )
                        }
                        idx == 0 && t in 0.20f..0.46f -> {
                            steer = 0.14f
                            selCursorPos
                        }
                        idx == 1 && t in 0.21f..0.46f -> {
                            steer = 0.14f
                            logoCornerPos
                        }
                        idx == 2 && t in 0.44f..0.66f -> {
                            steer = 0.16f
                            Offset(caretX + 3f * dpPx, barY + barH * 0.58f)
                        }
                        idx == 3 && t in 0.60f..0.68f -> {
                            steer = 0.16f
                            sendCenter
                        }
                        else -> {
                            val a = Math.toRadians((c.ringAngle + timeSec * 55f + c.seed).toDouble())
                            Offset(
                                wanderX + cos(a).toFloat() * c.orbitR * dpPx,
                                wanderY + sin(a).toFloat() * c.orbitR * dpPx
                            )
                        }
                    }
                    c.vx = (c.vx + (target.x - c.x) * steer * dtScale) * 0.86f
                    c.vy = (c.vy + (target.y - c.y) * steer * dtScale) * 0.86f
                    for (o in cursors) {
                        if (o === c) continue
                        val dx = c.x - o.x
                        val dy = c.y - o.y
                        val d2 = dx * dx + dy * dy
                        val minD = 30f * dpPx
                        if (d2 > 0.01f && d2 < minD * minD) {
                            val d = hypot(dx, dy)
                            val push = (minD - d) * 0.06f * dtScale
                            c.vx += dx / d * push
                            c.vy += dy / d * push
                        }
                    }
                    c.x += c.vx * dtScale
                    c.y += c.vy * dtScale
                }

                // ---------- 绘制光标（箭头 + 名字标签） ----------
                val arrowH = 21f * dpPx
                for ((idx, c) in cursors.withIndex()) {
                    val tilt = (c.vx * 0.05f).coerceIn(-13f, 13f)
                    rotate(degrees = tilt, pivot = Offset(c.x, c.y)) {
                        val path = Path().apply {
                            moveTo(c.x, c.y)
                            lineTo(c.x, c.y + arrowH * 0.72f)
                            lineTo(c.x + arrowH * 0.18f, c.y + arrowH * 0.56f)
                            lineTo(c.x + arrowH * 0.30f, c.y + arrowH * 0.88f)
                            lineTo(c.x + arrowH * 0.45f, c.y + arrowH * 0.79f)
                            lineTo(c.x + arrowH * 0.31f, c.y + arrowH * 0.50f)
                            lineTo(c.x + arrowH * 0.53f, c.y + arrowH * 0.50f)
                            close()
                        }
                        drawPath(path = path, color = Color.White)
                        val inset = 1.6f * dpPx
                        val inner = Path().apply {
                            moveTo(c.x + inset * 0.4f, c.y + inset)
                            lineTo(c.x + inset * 0.4f, c.y + arrowH * 0.72f - inset * 0.4f)
                            lineTo(c.x + arrowH * 0.18f, c.y + arrowH * 0.56f)
                            lineTo(c.x + arrowH * 0.30f, c.y + arrowH * 0.88f - inset * 0.2f)
                            lineTo(c.x + arrowH * 0.45f - inset * 0.3f, c.y + arrowH * 0.79f - inset * 0.3f)
                            lineTo(c.x + arrowH * 0.31f, c.y + arrowH * 0.50f + inset * 0.4f)
                            lineTo(c.x + arrowH * 0.53f - inset * 0.5f, c.y + arrowH * 0.50f + inset * 0.4f)
                            close()
                        }
                        drawPath(path = inner, color = c.color)
                    }
                    val layout = tagLayouts[idx % tagLayouts.size]
                    val tagW = layout.size.width + 13f * dpPx
                    val tagH = 16.5f * dpPx
                    val tagX = c.x + 11f * dpPx
                    val tagY = c.y + arrowH * 0.62f
                    drawRoundRect(
                        color = c.color,
                        topLeft = Offset(tagX, tagY),
                        size = Size(tagW, tagH),
                        cornerRadius = CornerRadius(tagH / 2f, tagH / 2f)
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            tagX + (tagW - layout.size.width) / 2f,
                            tagY + (tagH - layout.size.height) / 2f
                        )
                    )
                }
            } catch (e: Exception) {
                // 装饰动画绝不能拖垮 App：任何绘制异常都直接结束动画
                runtime.failed = true
            }
        }

        // 中央标志
        if (logoAlpha > 0f) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer(alpha = logoAlpha * overlayAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "伴语",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        }
    }
}
