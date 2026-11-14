package com.iam.bgm.app

import com.iam.bgm.core.Gear

/**
 * 기어 입력원 선택기. [ManualLocationGate]와 같은 이유로 둔다.
 * 실차·에뮬레이터의 Vehicle SDK 기어 이벤트와 시연 콘솔의 기어 버튼은 서로 다른 입력원이다.
 * 에뮬레이터의 차량 서비스가 재시작되면 SDK가 실제 기어(P)를 다시 보내는데, 이것이 콘솔로 고른 D를
 * 덮어써 주행 도중 일기가 만들어지던 결함이 있었다. 콘솔이 기어를 잡으면(manual) SDK 기어는 무시하고,
 * [release]로 다시 SDK에 맡긴다. 앰비언트 라이트 제어는 그대로 SDK에 위임한다.
 */
class ManualVehicleGate(private val inner: VehiclePort) : VehiclePort {
    override val label: String get() = inner.label + if (manual) " (시연 콘솔 기어 사용)" else ""

    var manual = false
        private set
    private var sink: ((Gear) -> Unit)? = null

    override fun start(onGear: (Gear) -> Unit) {
        sink = onGear
        inner.start { g -> if (!manual) onGear(g) }
    }

    /** 콘솔이 기어를 지정한다. 이후 SDK 기어 이벤트는 무시된다. */
    fun inject(g: Gear) {
        manual = true
        sink?.invoke(g)
    }

    fun release() { manual = false }

    override fun setAmbientColor(rgb: Int, onResult: (Boolean) -> Unit) = inner.setAmbientColor(rgb, onResult)

    override fun stop() = inner.stop()
}
