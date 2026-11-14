package com.iam.bgm

import androidx.compose.ui.geometry.Offset
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.ui.Camera
import com.iam.bgm.ui.Projector
import com.iam.bgm.ui.clampPan
import com.iam.bgm.ui.viewOf
import com.iam.bgm.ui.zoomAround
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주차 지도 확대·이동 계산. */
class MapZoomTest {
    private val w = 1200f
    private val h = 700f

    /** 두 손가락 사이 지점(centroid) 아래의 지도 지점은 확대 전후로 화면에서 움직이지 않는다. */
    @Test fun pinchKeepsPointUnderFingers() {
        val mapPoint = Offset(300f, 200f)
        val z0 = 1.5f; val pan0 = Offset(40f, -20f)
        val centroid = viewOf(mapPoint, z0, pan0, w, h)
        val (z1, pan1) = zoomAround(z0, pan0, 1.8f, centroid, w, h)
        val after = viewOf(mapPoint, z1, pan1, w, h)
        assertEquals(centroid.x, after.x, 0.01f)
        assertEquals(centroid.y, after.y, 0.01f)
        assertEquals(2.7f, z1, 1e-4f)
    }

    /** 자유 주행: 지도를 누른 화면 좌표 → 위경도 역변환이 정확해야 차가 누른 곳으로 간다(추적 카메라 회전·확대 포함). */
    @Test fun tapInversionRoundTrips() {
        val pts = listOf(GeoPoint(37.57, 126.97), GeoPoint(37.53, 127.05), GeoPoint(37.55, 127.0))
        val pr = Projector(pts, w, h, 70f)
        val g = GeoPoint(37.541, 127.012)
        val back = pr.geo(pr.px(g))
        assertEquals(g.lat, back.lat, 1e-6); assertEquals(g.lng, back.lng, 1e-6)
        val cam = Camera(pr.px(GeoPoint(37.54, 127.0)), 37f, 3.4f, Offset(w / 2, h * 0.8f))
        val p = pr.px(g)
        val round = cam.unscreen(cam.screen(p))
        assertEquals(p.x, round.x, 0.01f); assertEquals(p.y, round.y, 0.01f)
    }

    /** 배율은 1~6배, 이동은 확대한 만큼만. */
    @Test fun zoomAndPanAreBounded() {
        assertEquals(6f, zoomAround(5f, Offset.Zero, 3f, Offset(600f, 350f), w, h).first, 1e-6f)
        assertEquals(1f, zoomAround(1.2f, Offset.Zero, 0.1f, Offset(600f, 350f), w, h).first, 1e-6f)
        val p = clampPan(Offset(99999f, -99999f), 1f, w, h)
        assertTrue(p.x <= w * 0.15f + 0.01f && p.y >= -h * 0.15f - 0.01f)
    }
}
