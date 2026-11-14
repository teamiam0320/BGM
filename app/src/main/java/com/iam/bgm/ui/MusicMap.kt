package com.iam.bgm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import com.iam.bgm.platform.OsmMap
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
private val Park = Color(0xFF14302A)
private val RoadTrunk = Color(0xFF3A4766)
private val RoadPrimary = Color(0xFF263049)
private val PinViolet = Color(0xFFC9A7FF)
private val PinBlue = Color(0xFF8FB7FF)
private val LabelDim = Color(0xFF8A95AD)

/** 위경도 → 화면 좌표. 경도는 cos(위도)로 보정해 서울 위도에서 모양이 찌그러지지 않게 한다. */
private class Projector(points: List<GeoPoint>, w: Float, h: Float, pad: Float) {
    private val k = cos(Math.toRadians(points.map { it.lat }.average()))
    private val minX = points.minOf { it.lng * k }
    private val maxX = points.maxOf { it.lng * k }
    private val minY = points.minOf { it.lat }
    private val maxY = points.maxOf { it.lat }
    private val scale = minOf((w - 2 * pad) / (maxX - minX), (h - 2 * pad) / (maxY - minY))
    private val ox = (w - (maxX - minX) * scale) / 2
    private val oy = (h - (maxY - minY) * scale) / 2
    fun px(lat: Double, lng: Double) = Offset((ox + (lng * k - minX) * scale).toFloat(), (oy + (maxY - lat) * scale).toFloat())
    fun px(p: GeoPoint) = px(p.lat, p.lng)
    fun meters(m: Double) = (m / 111_000.0 * scale).toFloat()

    /** [lat, lng, lat, lng, ...] → Path */
    fun path(flat: List<Double>, close: Boolean = false) = Path().apply {
        var i = 0
        while (i + 1 < flat.size) {
            val o = px(flat[i], flat[i + 1])
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            i += 2
        }
        if (close) close()
    }
}

/**
 * 노래 지도. 바탕(실제 도로·한강·공원, OpenStreetMap)은 크기·데이터가 바뀔 때만 다시 그리도록 캐시하고,
 * 움직이는 핀만 매 프레임 그린다.
 * [detail]=false는 주행 중용 '흘끗 보는 지도'로 지명·곡 수를 그리지 않고 터치도 받지 않는다.
 * [detail]=true는 주차 중용으로 지명·곡 수를 표시하고 구간을 눌러 고를 수 있다.
 */
@Composable
fun MusicMap(
    route: List<RoutePoint>,
    map: MapData,
    osm: OsmMap?,
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
    val pinZones = pins.map { it.zoneId }.toSet()

    var boxModifier = modifier
    if (detail) {
        boxModifier = modifier.pointerInput(pins, extent) {
            detectTapGestures { pos ->
                val pr = Projector(extent, size.width.toFloat(), size.height.toFloat(), 70 * density)
                val hit = pins.minByOrNull { val o = pr.px(Zone.centerOf(it.zoneId)); hypot(o.x - pos.x, o.y - pos.y) }
                val d = hit?.let { val o = pr.px(Zone.centerOf(it.zoneId)); hypot(o.x - pos.x, o.y - pos.y) }
                onSelect(if (hit != null && d != null && d < 60 * density) hit.zoneId else null)
            }
        }
    }

    Box(boxModifier) {
        // ---- 바탕: 캐시 ----
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val u = density
                val pr = Projector(extent, size.width, size.height, 70 * u)
                val parks = osm?.parks.orEmpty().map { pr.path(it.pts, close = true) }
                val waters = if (osm != null) osm.water.map { pr.path(it.pts) to it.w } else {
                    val flat = map.river.flatMap { it }
                    if (flat.size >= 4) listOf(pr.path(flat) to 700.0) else emptyList()
                }
                val trunks = osm?.roads.orEmpty().filter { it.c != "p" }.map { pr.path(it.pts) }
                val primaries = osm?.roads.orEmpty().filter { it.c == "p" }.map { pr.path(it.pts) }
                val routePath = Path().apply {
                    route.forEachIndexed { i, r -> val o = pr.px(r.point); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                }
                val labels = if (!detail) emptyList() else
                    map.landmarks.filter { Zone.idOf(it.point) !in privateZones && nearestPin(it.point, pinZones) == null }
                        .map { lm -> pr.px(lm.point) to measurer.measure(lm.label, TextStyle(color = LabelDim, fontSize = 15.sp)) }
                val attribution = osm?.let { measurer.measure(it.attribution, TextStyle(color = LabelDim.copy(alpha = 0.8f), fontSize = 11.sp)) }

                onDrawBehind {
                    parks.forEach { drawPath(it, Park) }
                    waters.forEach { (p, w) ->
                        val px = maxOf(pr.meters(w), 1.6f * u)
                        if (w >= 500) drawPath(p, RiverEdge, style = Stroke(px * 1.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(p, River, style = Stroke(px, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    primaries.forEach { drawPath(it, RoadPrimary, style = Stroke(1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                    trunks.forEach { drawPath(it, RoadTrunk, style = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)) }

                    // 출퇴근 경로
                    drawPath(routePath, PinBlue.copy(alpha = 0.18f), style = Stroke(14 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(routePath, PinBlue.copy(alpha = 0.6f), style = Stroke(4 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))

                    // 핀이 없는 곳의 지명(주차 중에만). 핀이 있는 구간의 지명은 핀 아래에 붙인다.
                    labels.forEach { (o, l) -> drawText(l, topLeft = Offset(o.x - l.size.width / 2f, o.y - l.size.height / 2f)) }

                    // 집·회사
                    privateZones.forEach { (_, r) ->
                        val o = pr.px(r.point)
                        drawCircle(Color(0xFF2A3550), radius = 9 * u, center = o)
                        drawCircle(PinBlue.copy(alpha = 0.7f), radius = 9 * u, center = o, style = Stroke(2 * u))
                        if (detail) {
                            val l = measurer.measure(if (r.id == "home") "집" else "회사", TextStyle(color = PinBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            drawText(l, topLeft = Offset(o.x - l.size.width / 2f, o.y - 34 * u))
                        }
                    }
                    // OpenStreetMap 출처 표시(ODbL)
                    attribution?.let { drawText(it, topLeft = Offset(size.width - it.size.width - 14 * u, 10 * u)) }
                }
            },
        )

        // ---- 핀·현재 위치: 애니메이션 ----
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            val pr = Projector(extent, size.width, size.height, 70 * u)
            pins.forEach { p ->
                val o = pr.px(Zone.centerOf(p.zoneId))
                val outer = (if (p.mine > 0 && p.waiting > 0) 26 else if (p.received) 22 else if (p.waiting > 0) 10 + 3 * minOf(p.waiting, 4) else 13) * u
                if (detail) {
                    map.landmarks.firstOrNull { nearestPin(it.point, pinZones) == p.zoneId && Zone.idOf(it.point) !in privateZones }?.let { lm ->
                        val l = measurer.measure(lm.label, TextStyle(color = LabelDim, fontSize = 15.sp))
                        drawText(l, topLeft = Offset(o.x - l.size.width / 2f, o.y + outer + 6 * u))
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
                        val l = measurer.measure("${p.waiting}곡", TextStyle(color = Color(0xFF1B1430), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        drawText(l, topLeft = Offset(o.x - l.size.width / 2f, o.y - l.size.height / 2f))
                    }
                }
                if (p.mine > 0) {
                    val r = (if (p.waiting > 0) 26 else 13) * u
                    drawCircle(PinBlue, radius = r, center = o, style = Stroke(3.5f * u))
                    if (detail) {
                        val l = measurer.measure(if (p.mineReached) "내 노래 · 닿음" else "내 노래", TextStyle(color = PinBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                        drawText(l, topLeft = Offset(o.x - l.size.width / 2f, o.y - r - l.size.height - 6 * u))
                    }
                }
                if (p.received) drawCircle(PinViolet, radius = 22 * u, center = o, style = Stroke(2.5f * u))
                if (detail && p.zoneId == selected) drawCircle(Color.White, radius = 32 * u, center = o, style = Stroke(2.5f * u))
            }
            // 현재 위치(구간 중심 — 지도도 500m 단위로만 위치를 다룬다)
            currentZone?.let { z ->
                val o = pr.px(Zone.centerOf(z))
                drawCircle(Color.White.copy(alpha = 0.35f * (1 - pulse)), radius = (10 + 22 * pulse) * u, center = o)
                car(o, u)
            }
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
