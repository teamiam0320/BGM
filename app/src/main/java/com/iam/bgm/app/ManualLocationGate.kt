package com.iam.bgm.app

import com.iam.bgm.core.GeoPoint

/**
 * 위치 입력원 선택기. 실차·에뮬레이터의 FusedLocation 갱신(약 1초 주기)과 시연 콘솔의 구간 이동은
 * 서로 다른 입력원인데, 둘 다 컨트롤러의 현재 구간을 덮어쓰면 콘솔로 옮긴 구간이 다음 SDK 갱신에
 * 되돌아간다. 콘솔이 위치를 잡으면(manual) SDK 위치는 무시하고, [release]로 다시 SDK에 맡긴다.
 * 근접 알림(watchZones)은 그대로 SDK에 위임한다.
 */
class ManualLocationGate(private val inner: LocationPort) : LocationPort {
    override val label: String get() = inner.label + if (manual) " (시연 콘솔 위치 사용)" else ""

    var manual = false
        private set
    private var sink: ((GeoPoint) -> Unit)? = null

    override fun start(onLocation: (GeoPoint) -> Unit) {
        sink = onLocation
        inner.start { p -> if (!manual) onLocation(p) }
    }

    /** 콘솔이 위치를 지정한다. 이후 SDK 위치 갱신은 무시된다. */
    fun inject(p: GeoPoint) {
        manual = true
        sink?.invoke(p)
    }

    fun release() { manual = false }

    override fun watchZones(centers: Map<String, GeoPoint>, radiusMeters: Float, onEnter: (String) -> Unit) =
        inner.watchZones(centers, radiusMeters, onEnter)

    override fun stop() = inner.stop()
}
