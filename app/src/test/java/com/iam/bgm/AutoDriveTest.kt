package com.iam.bgm

import com.iam.bgm.app.AutoDrive
import com.iam.bgm.app.BgmController
import com.iam.bgm.app.DriveAction
import com.iam.bgm.app.DriveEnv
import com.iam.bgm.core.DriveMode
import com.iam.bgm.core.Gear
import com.iam.bgm.core.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 자동 주행 시연 전체를 실제 데이터(demo_scenario.json의 track·autoDrive)로 끝까지 돌린다.
 * 실제 도로 경로는 서울역·용산·옥수 등 노래가 있는 구간을 지나가므로, 엉뚱한 곳에서 노래가 제안되지 않는지 검증한다.
 */
class AutoDriveTest {
    private val sc = TestData.scenario
    private lateinit var voice: FakeVoice
    private lateinit var time: FakeTime
    private lateinit var store: MemoryStore
    private lateinit var c: BgmController
    private var n = 0
    /** (운전자, 제안된 편지 id, 제안 구간) */
    private val offers = mutableListOf<Triple<String, String, String>>()

    @Before fun setUp() {
        voice = FakeVoice(); time = FakeTime(); store = MemoryStore()
        store.saveLetters(sc.seedAsLetters(time.nowMillis()))
        c = BgmController(
            TestData.lexicon, TestData.catalog, TestData.dialogue, TestData.policy,
            FakeVehicle(), FakeLocation(), voice, FakeNavi(), FakeMusic(), time, store, ids = { "id${n++}" },
        )
        c.start("driver_a", sc.privateZones)
    }

    private fun drive(driverId: String) {
        val timeline = sc.autoDrive!!.drivers.getValue(driverId)
        val lines = sc.drivers.first { it.id == driverId }.lines
        time.hour = sc.drivers.first { it.id == driverId }.startHour
        val d = AutoDrive(sc.trackPoints, timeline)
        val env = object : DriveEnv {
            override val voiceBusy get() = voice.isSpeaking
            override val offerPending get() = c.state.value.pendingOffer != null
            override fun canEnter(zoneId: String) = true
        }
        var lastMinute = 0
        var guard = 0
        while (!d.done && guard++ < 10_000) {
            for (a in d.step(0.004, env)) when (a) {
                is DriveAction.Move -> c.onLocation(a.p)
                is DriveAction.SetGear -> c.onGear(if (a.gear == "D") Gear.DRIVE else Gear.PARK)
                is DriveAction.Say -> c.onUserSpeech(lines[a.line])
            }
            c.state.value.pendingOffer?.let { o ->
                if (offers.none { it.second == o.letter.id }) offers += Triple(driverId, o.letter.id, c.state.value.zoneId!!)
            }
            val m = (d.progress * timeline.minutes).toInt()
            if (m > lastMinute) { time.advanceMinutes(m - lastMinute); lastMinute = m }
        }
        assertTrue("자동 주행이 끝나야 한다", d.done)
    }

    @Test fun fullAutoDriveOffersOnlyALetterToBOnRiverside() {
        // 시나리오 1: A
        drive("driver_a")
        assertEquals(DriveMode.PARKED, c.state.value.mode)
        val aLetter = store.letters().single { it.authorId == "driver_a" }
        val riverside = Zone.idOf(sc.route.first { it.id == "riverside" }.point)
        assertEquals("A의 노래는 강변북로 구간에 남는다", riverside, aLetter.zoneId)
        assertNotNull(c.state.value.latestDiary)
        assertTrue("A는 노래를 받지 않는다: $offers", offers.none { it.first == "driver_a" })

        // 시나리오 2: 다음 날 B
        time.advanceMinutes(60 * 24)
        c.switchDriver("driver_b", sc.privateZones)
        drive("driver_b")
        assertEquals("B는 A의 노래 한 곡만, 강변북로에서 제안받는다", listOf(Triple("driver_b", aLetter.id, riverside)), offers)
        assertEquals(listOf("driver_b"), store.letters().single { it.id == aLetter.id }.thankedBy)
        assertEquals(listOf(aLetter.id), c.state.value.latestDiary!!.receivedLetterIds)
    }

    @Test fun trackStartsAtOfficeEndsAtHomeAndPassesScenarioPoints() {
        val t = sc.trackPoints
        val ids = t.map { Zone.idOf(it) }.toSet()
        val d = AutoDrive(t, sc.autoDrive!!.drivers.getValue("driver_a"))
        val dense = (0..1000).map { Zone.idOf(d.positionAt(it / 1000.0)) }.toSet()
        for (r in sc.route) assertTrue("${r.id} 구간을 지나야 한다", Zone.idOf(r.point) in dense || Zone.idOf(r.point) in ids)
        assertEquals(Zone.idOf(sc.route.first().point), Zone.idOf(t.first()))
        assertEquals(Zone.idOf(sc.route.last().point), Zone.idOf(t.last()))
    }
}
