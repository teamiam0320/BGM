package com.iam.bgm

import com.iam.bgm.app.BgmController
import com.iam.bgm.app.ManualVehicleGate
import com.iam.bgm.core.DriveMode
import com.iam.bgm.core.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 회귀: 에뮬레이터 차량 서비스가 재시작되면 Vehicle SDK가 실제 기어(P)를 다시 보내,
 * 시연 콘솔로 고른 D가 덮어써지고 주행 도중 일기가 만들어지던 결함.
 */
class VehicleGateTest {
    private fun setup(): Triple<BgmController, FakeVehicle, ManualVehicleGate> {
        val sdk = FakeVehicle()
        val gate = ManualVehicleGate(sdk)
        val c = BgmController(
            TestData.lexicon, TestData.catalog, TestData.dialogue, TestData.policy,
            gate, FakeLocation(), FakeVoice(), FakeNavi(), FakeMusic(), FakeTime(), MemoryStore(), ids = { "x" },
        )
        c.start("driver_a", TestData.scenario.privateZones)
        return Triple(c, sdk, gate)
    }

    @Test fun sdkParkDoesNotEndConsoleDrive() {
        val (c, sdk, gate) = setup()
        gate.inject(Gear.DRIVE)
        c.onUserSpeech("오늘 회사에서 기획안이 반려돼서 너무 지쳤어")
        sdk.onGear!!(Gear.PARK) // 차량 서비스 재연결로 실제 기어 P 재전송
        assertEquals(DriveMode.DRIVING, c.state.value.mode)
        assertNull(c.state.value.latestDiary)
        gate.inject(Gear.PARK) // 콘솔 P는 그대로 주차 처리
        assertEquals(DriveMode.PARKED, c.state.value.mode)
    }

    @Test fun sdkDrivesGearUntilConsoleTakesOverAndAfterRelease() {
        val (c, sdk, gate) = setup()
        sdk.onGear!!(Gear.DRIVE)
        assertEquals(DriveMode.DRIVING, c.state.value.mode)
        gate.inject(Gear.PARK)
        gate.release()
        sdk.onGear!!(Gear.DRIVE)
        assertEquals(DriveMode.DRIVING, c.state.value.mode)
    }
}
