package com.iam.bgm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

/** 3D 추적 시점: 확대 배율, 화면에서 내 차가 놓이는 위치(가로 중앙, 세로 비율), 지도 기울기. */
private const val FOLLOW_ZOOM = 4.2f
private const val FOLLOW_ANCHOR_Y = 0.78f
private const val FOLLOW_TILT_DEG = 52f

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

/** 추적 카메라: 지도 좌표(px) → 화면. 차를 anchor에 두고, 진행 방향(heading)이 위를 향하게 회전, zoom배 확대. */
private class Camera(val car: Offset, val headingDeg: Float, val zoom: Float, val anchor: Offset) {
    private val r = Math.toRadians(-headingDeg.toDouble())
    private val c = cos(r).toFloat()
    private val s = sin(r).toFloat()
    fun screen(p: Offset): Offset {
        val dx = (p.x - car.x) * zoom
        val dy = (p.y - car.y) * zoom
        return Offset(dx * c - dy * s + anchor.x, dx * s + dy * c + anchor.y)
    }
}

/** 카메라 입력(그리기 단계에서만 읽어서, 차가 움직일 때 바탕 경로를 다시 만들지 않는다). */
private data class CamInput(val follow: Boolean, val car: GeoPoint?, val headingDeg: Float)

/**
 * 노래 지도. 바탕(실제 도로·한강·공원, OpenStreetMap)은 데이터·크기가 바뀔 때만 경로를 만들고(캐시),
 * 움직이는 핀만 매 프레임 그린다.
 * [detail]=true: 주차 중 2D 전체 지도. 지명·곡 수를 표시하고 구간을 눌러 고를 수 있다.
 * [detail]=false: 주행 중 '흘끗 보는 지도'. 실제 내비처럼 내 차를 따라가며 진행 방향이 위로 오고 지도를 기울인 3D 시점.
 *   글자를 그리지 않고 터치도 받지 않는다.
 */
@Composable
fun MusicMap(
    route: List<RoutePoint>,
    map: MapData,
    osm: OsmMap?,
    track: List<GeoPoint>,
    position: GeoPoint?,
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
    val extent = remember(route, map) { route.map { it.point } + map.landmarks.map { it.point } }
    val privateZones = remember(route) { route.filter { it.privateZone }.associate { Zone.idOf(it.point) to it } }
    val pinZones = pins.map { it.zoneId }.toSet()

    // ---- 추적 카메라 입력: 차 위치는 틱 사이를 부드럽게, 방향은 가까운 쪽으로 돌며 부드럽게 ----
    val carGeo = position ?: currentZone?.let { Zone.centerOf(it) }
    val follow = !detail && carGeo != null && track.size >= 2
    val lat by animateFloatAsState((carGeo?.lat ?: 0.0).toFloat(), tween(120, easing = LinearEasing), label = "lat")
    val lng by animateFloatAsState((carGeo?.lng ?: 0.0).toFloat(), tween(120, easing = LinearEasing), label = "lng")
    val lastHeading = remember { FloatArray(1) } // 화면에 쓰지 않는 보관값(상태 아님)
    val targetHeading = if (follow) headingAlong(track, carGeo!!)?.let { raw ->
        val prev = lastHeading[0]
        val delta = ((raw - prev) % 360f + 540f) % 360f - 180f
        (prev + delta).also { lastHeading[0] = it }
    } ?: lastHeading[0] else lastHeading[0]
    val heading by animateFloatAsState(targetHeading, tween(700), label = "heading")
    val cam = rememberUpdatedState(CamInput(follow, if (carGeo != null) GeoPoint(lat.toDouble(), lng.toDouble()) else null, heading))

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
    // 3D: 지도를 앞으로 눕혀 원근감을 준다. 눕히면 위쪽 모서리가 비므로 조금 크게 그려 덮는다.
    val layer = if (follow) Modifier.graphicsLayer {
        rotationX = FOLLOW_TILT_DEG
        cameraDistance = 9f * density
        scaleX = 1.7f; scaleY = 1.7f
        transformOrigin = TransformOrigin(0.5f, FOLLOW_ANCHOR_Y)
    } else Modifier

    // 바탕 그리기 블록은 데이터가 바뀔 때만 새로 만든다(차가 움직일 때마다 경로 1,600개를 다시 만들지 않게).
    val baseBlock: CacheDrawScope.() -> DrawResult = remember(osm, map, track, route, detail, pinZones) {
        {
            val u = density
            val pr = Projector(extent, size.width, size.height, 70 * u)
            val parks = osm?.parks.orEmpty().map { pr.path(it.pts, close = true) }
            val waters = if (osm != null) osm.water.map { pr.path(it.pts) to it.w } else {
                val flat = map.river.flatMap { it }
                if (flat.size >= 4) listOf(pr.path(flat) to 700.0) else emptyList()
            }
            val trunks = osm?.roads.orEmpty().filter { it.c != "p" }.map { pr.path(it.pts) }
            val primaries = osm?.roads.orEmpty().filter { it.c == "p" }.map { pr.path(it.pts) }
            // 출퇴근 경로: 실제 도로 경로(track)가 있으면 그것을, 없으면 시나리오 지점을 잇는다
            val routePts = track.ifEmpty { route.map { it.point } }
            val routePath = Path().apply {
                routePts.forEachIndexed { i, p -> val o = pr.px(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            }
            val labels = if (!detail) emptyList() else
                map.landmarks.filter { Zone.idOf(it.point) !in privateZones && nearestPin(it.point, pinZones) == null }
                    .map { lm -> pr.px(lm.point) to measurer.measure(lm.label, TextStyle(color = LabelDim, fontSize = 15.sp)) }
            val attribution = osm?.let { measurer.measure(it.attribution, TextStyle(color = LabelDim.copy(alpha = 0.8f), fontSize = 11.sp)) }

            onDrawBehind {
                val ci = cam.value
                val camera = cameraOf(ci, pr, size.width, size.height)
                // 확대해도 선이 너무 굵어지지 않게(내비처럼 조금만 굵어짐)
                val k = if (camera != null) 1.8f / camera.zoom else 1f
                val drawBase: DrawScope.() -> Unit = {
                    parks.forEach { drawPath(it, Park) }
                    waters.forEach { (p, w) ->
                        val px = maxOf(pr.meters(w), 1.6f * u * k)
                        if (w >= 500) drawPath(p, RiverEdge, style = Stroke(px * 1.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(p, River, style = Stroke(px, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    primaries.forEach { drawPath(it, RoadPrimary, style = Stroke(1.4f * u * k, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                    trunks.forEach { drawPath(it, RoadTrunk, style = Stroke(3f * u * k, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                    drawPath(routePath, PinBlue.copy(alpha = 0.18f), style = Stroke(14 * u * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(routePath, PinBlue.copy(alpha = 0.6f), style = Stroke(4 * u * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (camera != null) {
                    withTransform({
                        translate(camera.anchor.x, camera.anchor.y)
                        rotate(-camera.headingDeg, pivot = Offset.Zero)
                        scale(camera.zoom, camera.zoom, pivot = Offset.Zero)
                        translate(-camera.car.x, -camera.car.y)
                    }) { drawBase() }
                } else {
                    drawBase()
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
                }
                // OpenStreetMap 출처 표시(ODbL)
                if (camera == null) attribution?.let { drawText(it, topLeft = Offset(size.width - it.size.width - 14 * u, 10 * u)) }
            }
        }
    }

    Box(boxModifier) {
        Box(Modifier.fillMaxSize().then(layer)) {
            // ---- 바탕: 캐시 ----
            Spacer(Modifier.fillMaxSize().drawWithCache(baseBlock))

            // ---- 핀·내 차: 애니메이션 (화면 좌표로 그려 확대해도 크기가 같다) ----
            Canvas(Modifier.fillMaxSize()) {
                val u = density
                val pr = Projector(extent, size.width, size.height, 70 * u)
                val camera = cameraOf(cam.value, pr, size.width, size.height)
                val at: (GeoPoint) -> Offset = { g -> camera?.screen(pr.px(g)) ?: pr.px(g) }
                if (camera != null) privateZones.forEach { (_, r) ->
                    val o = at(r.point)
                    drawCircle(Color(0xFF2A3550), radius = 9 * u, center = o)
                    drawCircle(PinBlue.copy(alpha = 0.7f), radius = 9 * u, center = o, style = Stroke(2 * u))
                }
                pins.forEach { p ->
                    val o = at(Zone.centerOf(p.zoneId))
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
                // 내 차: 추적 시점에서는 화면 아래 중앙에 진행 방향 화살표, 2D에서는 점(실제 위치, 저장·공유하지 않음)
                if (camera != null) {
                    drawCircle(Color.White.copy(alpha = 0.25f * (1 - pulse)), radius = (16 + 26 * pulse) * u, center = camera.anchor)
                    carArrow(camera.anchor, u)
                } else cam.value.car?.let { g ->
                    val o = pr.px(g)
                    drawCircle(Color.White.copy(alpha = 0.35f * (1 - pulse)), radius = (10 + 22 * pulse) * u, center = o)
                    carDot(o, u)
                }
            }
        }
        // 출처 표시는 기울이지 않은 화면에(3D에서도 읽을 수 있게)
        if (follow) Canvas(Modifier.fillMaxSize()) {
            osm?.let {
                val l = measurer.measure(it.attribution, TextStyle(color = LabelDim.copy(alpha = 0.8f), fontSize = 11.sp))
                drawText(l, topLeft = Offset(size.width - l.size.width - 14 * density, 10 * density))
            }
        }
    }
}

private fun cameraOf(ci: CamInput, pr: Projector, w: Float, h: Float): Camera? {
    if (!ci.follow || ci.car == null) return null
    return Camera(pr.px(ci.car), ci.headingDeg, FOLLOW_ZOOM, Offset(w / 2f, h * FOLLOW_ANCHOR_Y))
}

/** 차가 놓인 경로 구간의 진행 방향(도, 0=북쪽·위, 90=동쪽·오른쪽). 화면 좌표와 같은 경도 보정을 쓴다. */
private fun headingAlong(track: List<GeoPoint>, p: GeoPoint): Float? {
    if (track.size < 2) return null
    val k = cos(Math.toRadians(p.lat))
    var best = Double.MAX_VALUE
    var bi = 0
    for (i in 0 until track.size - 1) {
        val a = track[i]; val b = track[i + 1]
        val ax = a.lng * k; val ay = a.lat; val bx = b.lng * k; val by = b.lat; val px = p.lng * k; val py = p.lat
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        val tt = if (l2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0)
        val d = hypot(px - (ax + tt * vx), py - (ay + tt * vy))
        if (d < best) { best = d; bi = i }
    }
    val a = track[bi]; val b = track[bi + 1]
    val dx = (b.lng - a.lng) * k
    val dy = b.lat - a.lat
    if (dx == 0.0 && dy == 0.0) return null
    return Math.toDegrees(atan2(dx, dy)).toFloat()
}

/** 지명이 속한 핀 구간(같은 구간이거나 맞닿은 구간). 없으면 null. */
private fun nearestPin(p: GeoPoint, pinZones: Set<String>): String? {
    val z = Zone.idOf(p)
    return if (z in pinZones) z else pinZones.firstOrNull { Zone.isNear(it, z) }
}

private fun DrawScope.carDot(o: Offset, u: Float) {
    drawCircle(Color(0xFF0A0F1C), radius = 11 * u, center = o)
    drawCircle(Color.White, radius = 8 * u, center = o)
}

/** 진행 방향(화면 위)을 가리키는 화살표. */
private fun DrawScope.carArrow(o: Offset, u: Float) {
    val s = 22 * u
    val arrow = Path().apply {
        moveTo(o.x, o.y - s); lineTo(o.x + s * 0.72f, o.y + s * 0.7f); lineTo(o.x, o.y + s * 0.32f); lineTo(o.x - s * 0.72f, o.y + s * 0.7f); close()
    }
    drawPath(arrow, Color(0xFF0A0F1C), style = Stroke(6 * u, join = StrokeJoin.Round))
    drawPath(arrow, Color.White)
}
