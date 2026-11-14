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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Size
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

/** 추적 시점: 확대 배율, 화면에서 내 차가 놓이는 위치(가로 중앙, 세로 비율). */
private const val FOLLOW_ZOOM = 3.4f
private const val FOLLOW_ANCHOR_Y = 0.80f

/**
 * 3D 원근: 바닥(추적 카메라를 거친 평면 지도) → 화면의 사영 변환.
 * 바닥의 먼 쪽(위)은 지평선으로 모이고 가까운 쪽(아래)은 그대로다. 노래 구는 이 변환으로 위치만 옮기고
 * 모양은 기울이지 않아 둥글게 보인다.
 */
private class Perspective(w: Float, h: Float) {
    val horizon = h * 0.10f
    private val farY = -h * 1.6f
    val m = android.graphics.Matrix().apply {
        val src = floatArrayOf(-0.5f * w, farY, 1.5f * w, farY, 1.5f * w, h, -0.5f * w, h)
        val dst = floatArrayOf(0.08f * w, horizon, 0.92f * w, horizon, 1.5f * w, h, -0.5f * w, h)
        setPolyToPoly(src, 0, dst, 0, 4)
    }
    private val buf = FloatArray(4)
    /** 바닥 점이 지평선 너머(화면에 그릴 수 없는 쪽)인가 */
    private val cullY = farY * 0.92f
    fun visible(g: Offset) = g.y > cullY
    fun map(g: Offset): Offset { buf[0] = g.x; buf[1] = g.y; m.mapPoints(buf, 0, buf, 0, 1); return Offset(buf[0], buf[1]) }
    private val inv = android.graphics.Matrix().also { m.invert(it) }
    /** 화면 → 바닥. 지평선 위를 누르면 null */
    fun unmap(sp: Offset): Offset? {
        if (sp.y <= horizon + 4f) return null
        buf[0] = sp.x; buf[1] = sp.y; inv.mapPoints(buf, 0, buf, 0, 1)
        return Offset(buf[0], buf[1]).takeIf { visible(it) }
    }
    /** 그 지점의 화면 확대율(멀수록 작다) */
    fun scaleAt(g: Offset): Float {
        buf[0] = g.x; buf[1] = g.y; buf[2] = g.x + 10f; buf[3] = g.y
        m.mapPoints(buf)
        return hypot(buf[2] - buf[0], buf[3] - buf[1]) / 10f
    }
}

/** 위경도 → 화면 좌표. 경도는 cos(위도)로 보정해 서울 위도에서 모양이 찌그러지지 않게 한다. */
internal class Projector(points: List<GeoPoint>, w: Float, h: Float, pad: Float) {
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
    /** 화면(지도 px) → 위경도 */
    fun geo(o: Offset) = GeoPoint(maxY - (o.y - oy) / scale, ((o.x - ox) / scale + minX) / k)

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
internal class Camera(val car: Offset, val headingDeg: Float, val zoom: Float, val anchor: Offset) {
    private val r = Math.toRadians(-headingDeg.toDouble())
    private val c = cos(r).toFloat()
    private val s = sin(r).toFloat()
    fun screen(p: Offset): Offset {
        val dx = (p.x - car.x) * zoom
        val dy = (p.y - car.y) * zoom
        return Offset(dx * c - dy * s + anchor.x, dx * s + dy * c + anchor.y)
    }
    /** 화면 → 지도 px (screen의 역) */
    fun unscreen(q: Offset): Offset {
        val dx = q.x - anchor.x; val dy = q.y - anchor.y
        return Offset((dx * c + dy * s) / zoom + car.x, (-dx * s + dy * c) / zoom + car.y)
    }
}

/** 카메라 입력(그리기 단계에서만 읽어서, 차가 움직일 때 바탕 경로를 다시 만들지 않는다). */
private data class CamInput(val follow: Boolean, val car: GeoPoint?, val headingDeg: Float, val tilt: Boolean, val zoomMul: Float = 1f)

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
    /** 주행 중 3D(원근) 시점. false면 기울이지 않은 2D 추적 시점. */
    tilt3d: Boolean = true,
    /** 노래 수신 반경(미터). 주행 중 내 차 둘레에 표시한다. */
    pickupRadiusM: Double? = null,
    /** 자유 주행 테스트: 주행 지도를 누르면 그 위치(위경도)를 알려준다. null이면 주행 지도는 터치를 받지 않는다. */
    onTapGeo: ((GeoPoint) -> Unit)? = null,
    /** 자유 주행 목적지 표시 */
    destination: GeoPoint? = null,
    /** 자유 주행 경로(도로 위) 표시 */
    plannedRoute: List<GeoPoint> = emptyList(),
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
    // 진행 방향: 실제로 움직인 방향(이전 위치 → 지금 위치, 8m 이상 움직였을 때). 아직 움직이지 않았으면 경로 방향.
    val lastPos = remember { arrayOfNulls<GeoPoint>(1) }
    val moved = carGeo?.let { cur ->
        val prev = lastPos[0]
        when {
            prev == null -> { lastPos[0] = cur; null }
            Zone.metersBetween(prev, cur) >= 8.0 -> {
                val k = cos(Math.toRadians(cur.lat))
                lastPos[0] = cur
                Math.toDegrees(atan2((cur.lng - prev.lng) * k, cur.lat - prev.lat)).toFloat()
            }
            else -> null
        }
    }
    val rawHeading = if (follow) moved ?: (if (lastHeading[0] == 0f) headingAlong(track, carGeo!!) else null) else null
    val targetHeading = if (follow) rawHeading?.let { raw ->
        val prev = lastHeading[0]
        val delta = ((raw - prev) % 360f + 540f) % 360f - 180f
        (prev + delta).also { lastHeading[0] = it }
    } ?: lastHeading[0] else lastHeading[0]
    val heading by animateFloatAsState(targetHeading, tween(700), label = "heading")
    // 확대: 주차 지도는 두 손가락 확대·이동(+/− 버튼도), 주행 지도는 +/− 버튼으로 차 주변 배율
    val zoom = remember { mutableFloatStateOf(1f) }
    val pan = remember { mutableStateOf(Offset.Zero) }
    val followMul = remember { mutableFloatStateOf(1f) }
    val cam = rememberUpdatedState(CamInput(follow, if (carGeo != null) GeoPoint(lat.toDouble(), lng.toDouble()) else null, heading, tilt3d, followMul.floatValue))

    var boxModifier = modifier
    if (detail) {
        boxModifier = modifier.pointerInput(pins, extent) {
            detectTapGestures { pos ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val pr = Projector(extent, w, h, 70 * density)
                val v = { g: GeoPoint -> viewOf(pr.px(g), zoom.floatValue, pan.value, w, h) }
                val hit = pins.minByOrNull { val o = v(Zone.centerOf(it.zoneId)); hypot(o.x - pos.x, o.y - pos.y) }
                val d = hit?.let { val o = v(Zone.centerOf(it.zoneId)); hypot(o.x - pos.x, o.y - pos.y) }
                onSelect(if (hit != null && d != null && d < 60 * density) hit.zoneId else null)
            }
        }.pointerInput(Unit) {
            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val (z, pn) = zoomAround(zoom.floatValue, pan.value, zoomChange, centroid, w, h)
                zoom.floatValue = z
                pan.value = clampPan(pn + panChange, z, w, h)
            }
        }
    }

    val tapGeo = rememberUpdatedState(onTapGeo)
    if (!detail && onTapGeo != null) {
        boxModifier = boxModifier.pointerInput(extent) {
            detectTapGestures { pos ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val pr = Projector(extent, w, h, 70 * density)
                val ci = cam.value
                val camera = cameraOf(ci, pr, w, h)
                val ground = if (camera != null && ci.tilt) Perspective(w, h).unmap(pos) else pos
                if (ground != null) {
                    val mapPx = camera?.unscreen(ground) ?: ground
                    tapGeo.value?.invoke(pr.geo(mapPx))
                }
            }
        }
    }

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
                val z = zoom.floatValue
                val pn = pan.value
                val k = if (camera != null) 1.8f / camera.zoom else 1f / Math.pow(z.toDouble(), 0.6).toFloat()
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
                    val persp = if (ci.tilt) Perspective(size.width, size.height) else null
                    if (persp != null) drawIntoCanvas { it.nativeCanvas.save(); it.nativeCanvas.concat(persp.m) }
                    withTransform({
                        translate(camera.anchor.x, camera.anchor.y)
                        rotate(-camera.headingDeg, pivot = Offset.Zero)
                        scale(camera.zoom, camera.zoom, pivot = Offset.Zero)
                        translate(-camera.car.x, -camera.car.y)
                    }) { drawBase() }
                    if (persp != null) {
                        drawIntoCanvas { it.nativeCanvas.restore() }
                        // 지평선 쪽은 안개처럼 흐리게(먼 곳이 끝없이 이어지는 느낌)
                        drawRect(Brush.verticalGradient(listOf(Color(0xFF0D1426), Color(0xFF0D1426), Color(0x000D1426)), startY = 0f, endY = size.height * 0.34f))
                    }
                } else {
                    withTransform({
                        translate(pn.x, pn.y)
                        scale(z, z, pivot = Offset(size.width / 2f, size.height / 2f))
                    }) { drawBase() }
                    // 핀이 없는 곳의 지명(주차 중에만). 핀이 있는 구간의 지명은 핀 아래에 붙인다.
                    labels.forEach { (o0, l) -> val o = viewOf(o0, z, pn, size.width, size.height); drawText(l, topLeft = Offset(o.x - l.size.width / 2f, o.y - l.size.height / 2f)) }
                    // 집·회사
                    privateZones.forEach { (_, r) ->
                        val o = viewOf(pr.px(r.point), z, pn, size.width, size.height)
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
        Box(Modifier.fillMaxSize()) {
            // ---- 바탕: 캐시 ----
            Spacer(Modifier.fillMaxSize().drawWithCache(baseBlock))

            // ---- 핀·내 차: 애니메이션 (화면 좌표로 그려 확대해도 크기가 같다) ----
            Canvas(Modifier.fillMaxSize()) {
                val u = density
                val pr = Projector(extent, size.width, size.height, 70 * u)
                val ci = cam.value
                val camera = cameraOf(ci, pr, size.width, size.height)
                val persp = if (camera != null && ci.tilt) Perspective(size.width, size.height) else null
                val zz = zoom.floatValue
                val pp = pan.value
                val ground: (GeoPoint) -> Offset = { g -> camera?.screen(pr.px(g)) ?: viewOf(pr.px(g), zz, pp, size.width, size.height) }
                if (persp != null) {
                    // 3D: 노래 지점은 기울이지 않은 '구'로. 먼 것부터 그려 가까운 구가 앞에 오게 한다.
                    pins.map { it to ground(Zone.centerOf(it.zoneId)) }.filter { persp.visible(it.second) }
                        .sortedBy { it.second.y }.forEach { (p, g) ->
                            val o = persp.map(g)
                            val k = persp.scaleAt(g).coerceIn(0.25f, 1.6f)
                            if (p.zoneId == offerZone) {
                                drawOval(PinViolet.copy(alpha = 0.5f * (1 - pulse)), topLeft = Offset(o.x - (20 + 46 * pulse) * u * k, o.y - (6 + 14 * pulse) * u * k), size = Size((40 + 92 * pulse) * u * k, (12 + 28 * pulse) * u * k))
                            }
                            val color = if (p.waiting > 0) PinViolet else PinBlue
                            val r = (if (p.waiting > 0) 12 + 3 * minOf(p.waiting, 4) else 11) * u * k
                            sphere(o, r, color, breathe)
                            if (p.received) drawOval(PinViolet, topLeft = Offset(o.x - r * 1.5f, o.y - r * 0.4f), size = Size(r * 3f, r * 0.8f), style = Stroke(2f * u))
                        }
                    privateZones.forEach { (_, r) ->
                        val g = ground(r.point)
                        if (persp.visible(g)) { val o = persp.map(g); drawCircle(PinBlue.copy(alpha = 0.7f), radius = 7 * u * persp.scaleAt(g).coerceIn(0.25f, 1.4f), center = o, style = Stroke(2 * u)) }
                    }
                    if (plannedRoute.size >= 2) {
                        drawIntoCanvas { it.nativeCanvas.save(); it.nativeCanvas.concat(persp.m) }
                        withTransform({
                            translate(camera!!.anchor.x, camera.anchor.y); rotate(-camera.headingDeg, pivot = Offset.Zero)
                            scale(camera.zoom, camera.zoom, pivot = Offset.Zero); translate(-camera.car.x, -camera.car.y)
                        }) { routeLine(plannedRoute.map { pr.px(it) }, u / camera!!.zoom) }
                        drawIntoCanvas { it.nativeCanvas.restore() }
                    }
                    destination?.let { dg -> val g = ground(dg); if (persp.visible(g)) destFlag(persp.map(g), u * persp.scaleAt(g).coerceIn(0.4f, 1.5f)) }
                    // 내 차: 바닥에 붙은 화살표(원근 적용)
                    drawIntoCanvas { it.nativeCanvas.save(); it.nativeCanvas.concat(persp.m) }
                    pickupRadiusM?.let { pickupRing(camera!!.anchor, pr.meters(it) * camera.zoom, u) }
                    carArrow(camera!!.anchor, u * 1.3f)
                    drawIntoCanvas { it.nativeCanvas.restore() }
                    return@Canvas
                }
                val at = ground
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
                    if (plannedRoute.size >= 2) routeLine(plannedRoute.map { at(it) }, u)
                    destination?.let { destFlag(at(it), u) }
                    pickupRadiusM?.let { pickupRing(camera.anchor, pr.meters(it) * camera.zoom, u) }
                    drawCircle(Color.White.copy(alpha = 0.25f * (1 - pulse)), radius = (16 + 26 * pulse) * u, center = camera.anchor)
                    carArrow(camera.anchor, u)
                } else cam.value.car?.let { g ->
                    val o = viewOf(pr.px(g), zz, pp, size.width, size.height)
                    drawCircle(Color.White.copy(alpha = 0.35f * (1 - pulse)), radius = (10 + 22 * pulse) * u, center = o)
                    carDot(o, u)
                }
            }
        }
        // 확대·축소 버튼(에뮬레이터·장갑 낀 손에서도 쓸 수 있게). 주행 중에는 차 주변 배율만 바꾼다.
        ZoomButtons(
            canReset = if (follow) followMul.floatValue != 1f else (zoom.floatValue != 1f || pan.value != Offset.Zero),
            onZoom = { f ->
                if (follow) followMul.floatValue = (followMul.floatValue * f).coerceIn(0.5f, 2.5f)
                else {
                    val z = (zoom.floatValue * f).coerceIn(1f, 6f)
                    pan.value = pan.value * (z / zoom.floatValue)
                    zoom.floatValue = z
                }
            },
            onReset = { followMul.floatValue = 1f; zoom.floatValue = 1f; pan.value = Offset.Zero },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
        )
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
    return Camera(pr.px(ci.car), ci.headingDeg, FOLLOW_ZOOM * ci.zoomMul, Offset(w / 2f, h * FOLLOW_ANCHOR_Y))
}

/** 주차 지도 확대·이동: 지도 좌표 → 화면. 화면 중심을 기준으로 z배 확대한 뒤 pan만큼 옮긴다. */
internal fun viewOf(o: Offset, z: Float, pan: Offset, w: Float, h: Float): Offset {
    val c = Offset(w / 2f, h / 2f)
    return Offset((o.x - c.x) * z + c.x + pan.x, (o.y - c.y) * z + c.y + pan.y)
}

/** 두 손가락 확대: 손가락 사이 지점이 화면에서 움직이지 않도록 pan을 함께 조정한다. */
internal fun zoomAround(z: Float, pan: Offset, factor: Float, centroid: Offset, w: Float, h: Float): Pair<Float, Offset> {
    val nz = (z * factor).coerceIn(1f, 6f)
    val f = nz / z
    val c = Offset(w / 2f, h / 2f)
    val q = centroid - c
    return nz to Offset(q.x * (1 - f) + pan.x * f, q.y * (1 - f) + pan.y * f)
}

/** 확대한 만큼만 끌 수 있게(지도가 화면 밖으로 사라지지 않게). */
internal fun clampPan(p: Offset, z: Float, w: Float, h: Float): Offset {
    val mx = (z - 1f) * w / 2f + w * 0.15f
    val my = (z - 1f) * h / 2f + h * 0.15f
    return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
}

@Composable
private fun ZoomButtons(canReset: Boolean, onZoom: (Float) -> Unit, onReset: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xE60A0F1C)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("+" to 1.5f, "−" to (1f / 1.5f)).forEach { (label, f) ->
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).clickable { onZoom(f) }, contentAlignment = Alignment.Center) {
                Text(label, color = Color(0xFFF2F5FB), fontSize = 30.sp)
            }
        }
        // 원위치 버튼은 늘 같은 자리에 두어 +/− 위치가 움직이지 않게 한다(쓸 수 없을 땐 흐리게)
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = canReset, onClick = onReset), contentAlignment = Alignment.Center) {
            Text("↺", color = if (canReset) PinBlue else Color(0x33FFFFFF), fontSize = 26.sp)
        }
    }
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

/**
 * 바닥 지점 [g] 위에 놓인 빛나는 구. 왼쪽 위에서 빛이 오고, 바닥에 납작한 그림자가 생긴다.
 */
private fun DrawScope.sphere(g: Offset, r: Float, color: Color, glow: Float) {
    val c = Offset(g.x, g.y - r * 1.15f) // 바닥에서 살짝 떠 있음
    drawOval(Color.Black.copy(alpha = 0.45f), topLeft = Offset(g.x - r * 0.95f, g.y - r * 0.22f), size = Size(r * 1.9f, r * 0.44f))
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.40f * glow), Color.Transparent), center = c, radius = r * 2.6f), radius = r * 2.6f, center = c)
    val dark = Color(color.red * 0.35f, color.green * 0.3f, color.blue * 0.55f, 1f)
    drawCircle(
        Brush.radialGradient(listOf(Color.White, color, dark), center = Offset(c.x - r * 0.38f, c.y - r * 0.42f), radius = r * 1.55f),
        radius = r, center = c,
    )
}

/** 자유 주행 경로(도로 위): 밝은 선. */
private fun DrawScope.routeLine(pts: List<Offset>, u: Float) {
    val p = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
    drawPath(p, Color.White.copy(alpha = 0.18f), style = Stroke(16 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(p, Color(0xFFE8F0FF), style = Stroke(5 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 자유 주행 목적지 깃발. */
private fun DrawScope.destFlag(o: Offset, u: Float) {
    drawCircle(Color.White.copy(alpha = 0.18f), radius = 18 * u, center = o)
    drawCircle(Color.White, radius = 18 * u, center = o, style = Stroke(2.5f * u))
    drawCircle(Color.White, radius = 5 * u, center = o)
}

/** 노래 수신 반경: 이 원 안에 노래가 들어오면 아미가 들려준다. */
private fun DrawScope.pickupRing(c: Offset, r: Float, u: Float) {
    drawCircle(PinViolet.copy(alpha = 0.08f), radius = r, center = c)
    drawCircle(PinViolet.copy(alpha = 0.45f), radius = r, center = c, style = Stroke(2.2f * u))
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
