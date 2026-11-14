package com.iam.bgm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Zone
import com.iam.bgm.platform.MapData
import com.iam.bgm.platform.RoutePoint
import kotlin.math.cos
import kotlin.math.hypot

/** 지도에 찍는 구간 하나. 다른 사람 노래는 개수만 가진다(곡명·한마디는 지도에 나가지 않는다). */
data class MapPin(
    val zoneId: String,
    val waiting: Int,
    val mine: Int,
    val mineReached: Boolean,
    val received: Boolean,
)

private val River = Color(0xFF1A3563)
private val RiverEdge = Color(0xFF24477F)
private val PinViolet = Color(0xFFC9A7FF)
private val PinBlue = Color(0xFF8FB7FF)
private val LabelDim = Color(0xFF7C879F)

/** 위경도 → 화면 좌표. 경도는 cos(위도)로 보정해 서울 위도에서 모양이 찌그러지지 않게 한다. */
private class Projector(points: List<GeoPoint>, private val w: Float, private val h: Float, pad: Float) {
    private val k = cos(Math.toRadians(points.map { it.lat }.average()))
    private val minX = points.minOf { it.lng * k }
    private val maxX = points.maxOf { it.lng * k }
    private val minY = points.minOf { it.lat }
    private val maxY = points.maxOf { it.lat }
    private val scale = minOf((w - 2 * pad) / (maxX - minX), (h - 2 * pad) / (maxY - minY))
    private val ox = (w - (maxX - minX) * scale) / 2
    private val oy = (h - (maxY - minY) * scale) / 2
    fun px(p: GeoPoint) = Offset((ox + (p.lng * k - minX) * scale).toFloat(), (oy + (maxY - p.lat) * scale).toFloat())
    fun meters(m: Double) = (m / 111_000.0 * scale).toFloat()
}

/**
 * 노래 지도. [detail]=false는 주행 중용 '흘끗 보는 지도'로 글자를 그리지 않고 터치도 받지 않는다.
 * [detail]=true는 주차 중용으로 지명·곡 수를 표시하고 구간을 눌러 고를 수 있다.
 */
@Composable
fun MusicMap(
    route: List<RoutePoint>,
    map: MapData,
    pins: List<MapPin>,
    currentZone: String?,
    offerZone: String?,
    detail: Boolean,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = rememberInfiniteTransition(label = "map")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    val breathe by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
    val measurer = rememberTextMeasurer()
    val extent = route.map { it.point } + map.landmarks.map { it.point }
    val privateZones = route.filter { it.privateZone }.associate { Zone.idOf(it.point) to it }

    var tapModifier = modifier
    if (detail) {
        tapModifier = modifier.pointerInput(pins, extent) {
            detectTapGestures { pos ->
                val pr = Projector(extent, size.width.toFloat(), size.height.toFloat(), 70.dpPx(density))
                val hit = pins.minByOrNull { val o = pr.px(Zone.centerOf(it.zoneId)); hypot(o.x - pos.x, o.y - pos.y) }
                val d = hit?.let { val o = pr.px(Zone.centerOf(it.zoneId)); hypot(o.x - pos.x, o.y - pos.y) }
                onSelect(if (hit != null && d != null && d < 60.dpPx(density)) hit.zoneId else null)
            }
        }
    }

    Canvas(tapModifier) {
        val pr = Projector(extent, size.width, size.height, 70.dpPx(density))
        val u = density

        // 한강
        if (map.river.size >= 2) {
            val river = Path().apply {
                map.river.forEachIndexed { i, (lat, lng) -> val o = pr.px(GeoPoint(lat, lng)); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            }
            drawPath(river, RiverEdge, style = Stroke(pr.meters(760.0), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(river, River, style = Stroke(pr.meters(640.0), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 지명(주차 중에만). 핀이 있는 구간의 지명은 핀 아래에 붙여 그리므로 여기서는 뺀다.
        // 집·회사 자리는 '집/회사'로만 표시한다.
        val pinZones = pins.map { it.zoneId }.toSet()
        if (detail) map.landmarks.filter { Zone.idOf(it.point) !in privateZones && nearestPin(it.point, pinZones) == null }.forEach { lm ->
            val o = pr.px(lm.point)
            val layout = measurer.measure(lm.label, TextStyle(color = LabelDim, fontSize = 15.sp))
            drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
        }

        // 출퇴근 경로
        val path = Path().apply {
            route.forEachIndexed { i, r -> val o = pr.px(r.point); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        }
        drawPath(path, PinBlue.copy(alpha = 0.18f), style = Stroke(14 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, PinBlue.copy(alpha = 0.55f), style = Stroke(4 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 집·회사
        privateZones.forEach { (_, r) ->
            val o = pr.px(r.point)
            drawCircle(Color(0xFF2A3550), radius = 9 * u, center = o)
            drawCircle(PinBlue.copy(alpha = 0.7f), radius = 9 * u, center = o, style = Stroke(2 * u))
            if (detail) {
                val label = if (r.id == "home") "집" else "회사"
                val layout = measurer.measure(label, TextStyle(color = PinBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - 34 * u))
            }
        }

        // 노래 핀
        pins.forEach { p ->
            val o = pr.px(Zone.centerOf(p.zoneId))
            val outer = (if (p.mine > 0 && p.waiting > 0) 26 else if (p.received) 22 else if (p.waiting > 0) 10 + 3 * minOf(p.waiting, 4) else 13) * u
            if (detail) {
                map.landmarks.firstOrNull { nearestPin(it.point, pinZones) == p.zoneId && Zone.idOf(it.point) !in privateZones }?.let { lm ->
                    val layout = measurer.measure(lm.label, TextStyle(color = LabelDim, fontSize = 15.sp))
                    drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y + outer + 6 * u))
                }
            }
            if (p.zoneId == offerZone) {
                drawCircle(PinViolet.copy(alpha = 0.45f * (1 - pulse)), radius = (18 + 40 * pulse) * u, center = o)
            }
            if (p.waiting > 0) {
                val r = (10 + 3 * minOf(p.waiting, 4)) * u
                drawCircle(Brush.radialGradient(listOf(PinViolet.copy(alpha = 0.45f * breathe), Color.Transparent), center = o, radius = r * 2.6f), radius = r * 2.6f, center = o)
                drawCircle(PinViolet, radius = r, center = o)
                if (detail) {
                    val layout = measurer.measure("${p.waiting}곡", TextStyle(color = Color(0xFF1B1430), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
                }
            }
            if (p.mine > 0) {
                val r = (if (p.waiting > 0) 26 else 13) * u
                drawCircle(PinBlue, radius = r, center = o, style = Stroke(3.5f * u))
                if (detail) {
                    val label = if (p.mineReached) "내 노래 · 닿음" else "내 노래"
                    val layout = measurer.measure(label, TextStyle(color = PinBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - r - layout.size.height - 6 * u))
                }
            }
            if (p.received) {
                drawCircle(PinViolet, radius = 22 * u, center = o, style = Stroke(2.5f * u))
            }
            if (detail && p.zoneId == selected) {
                drawCircle(Color.White, radius = 32 * u, center = o, style = Stroke(2.5f * u))
            }
        }

        // 현재 위치(구간 중심 — 지도도 500m 단위로만 위치를 다룬다)
        currentZone?.let { z ->
            val o = pr.px(Zone.centerOf(z))
            drawCircle(Color.White.copy(alpha = 0.35f * (1 - pulse)), radius = (10 + 22 * pulse) * u, center = o)
            car(o, u)
        }
    }
}

/** 지명이 속한 핀 구간(같은 구간이거나 맞닿은 구간). 없으면 null. */
private fun nearestPin(p: GeoPoint, pinZones: Set<String>): String? {
    val z = Zone.idOf(p)
    return if (z in pinZones) z else pinZones.firstOrNull { Zone.isNear(it, z) }
}

private fun DrawScope.car(o: Offset, u: Float) {
    drawCircle(Color(0xFF0A0F1C), radius = 11 * u, center = o)
    drawCircle(Color.White, radius = 8 * u, center = o)
}

private fun Int.dpPx(density: Float) = this * density
