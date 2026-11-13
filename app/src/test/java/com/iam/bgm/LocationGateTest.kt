package com.iam.bgm

import com.iam.bgm.app.BgmController
import com.iam.bgm.app.ManualLocationGate
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Gear
import com.iam.bgm.core.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 회귀: 에뮬레이터 FusedLocation이 약 1초마다 실제 좌표(마운틴뷰)를 보내, 시연 콘솔로 옮긴 구간이
 * 덮어써지던 결함. 콘솔이 위치를 잡은 뒤에는 SDK 위치가 구간을 바꾸지 못해야 한다.
 */
class LocationGateTest {
    private val sdkFix = GeoPoint(37.421998, -122.084)

    private fun setup(): Triple<BgmController, FakeLocation, ManualLocationGate> {
        val sdk = FakeLocation()
        val gate = ManualLocationGate(sdk)
        val store = MemoryStore()
        val c = BgmController(
            TestData.lexicon, TestData.catalog, TestData.dialogue, TestData.policy,
            FakeVehicle(), gate, FakeVoice(), FakeNavi(), FakeMusic(), FakeTime(), store, ids = { "x" },
        )
        c.start("driver_a", TestData.scenario.privateZones)
        return Triple(c, sdk, gate)
    }

    @Test fun sdkFixDoesNotOverrideConsoleZone() {
        val (c, sdk, gate) = setup()
        val riverside = TestData.scenario.route.first { it.id == "riverside" }.point
        c.onGear(Gear.DRIVE)
        c.onUserSpeech("오늘 회사 일 때문에 너무 지쳤어")
        gate.inject(riverside)
        sdk.emit!!(sdkFix) // 콘솔 이동 직후 SDK 갱신 도착
        assertEquals(Zone.idOf(riverside), c.state.value.zoneId)
    }

    @Test fun sdkDrivesLocationUntilConsoleTakesOverAndAfterRelease() {
        val (c, sdk, gate) = setup()
        sdk.emit!!(sdkFix)
        assertEquals(Zone.idOf(sdkFix), c.state.value.zoneId)
        gate.inject(TestData.scenario.route.first().point)
        gate.release()
        sdk.emit!!(sdkFix)
        assertEquals(Zone.idOf(sdkFix), c.state.value.zoneId)
    }
}
