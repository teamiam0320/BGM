package com.iam.bgm

import com.iam.bgm.app.BgmController
import com.iam.bgm.core.DriveMode
import com.iam.bgm.core.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 기획서 4장의 시나리오 1·2를 컨트롤러 단위로 끝까지 재현한다. */
class ScenarioTest {
    private lateinit var vehicle: FakeVehicle
    private lateinit var voice: FakeVoice
    private lateinit var music: FakeMusic
    private lateinit var time: FakeTime
    private lateinit var store: MemoryStore
    private lateinit var c: BgmController
    private var n = 0

    private val sc = TestData.scenario
    private fun point(id: String) = sc.route.first { it.id == id }.point
    private fun driver(id: String) = sc.drivers.first { it.id == id }

    @Before fun setUp() {
        vehicle = FakeVehicle(); voice = FakeVoice(); music = FakeMusic(); time = FakeTime(); store = MemoryStore()
        store.saveLetters(sc.seedAsLetters(time.nowMillis()))
        c = BgmController(
            TestData.lexicon, TestData.catalog, TestData.dialogue, TestData.policy,
            vehicle, FakeLocation(), voice, FakeNavi(), music, time, store, ids = { "id${n++}" },
        )
        c.start("driver_a", sc.privateZones)
    }

    private fun driveA() {
        val a = driver("driver_a")
        time.hour = a.startHour
        c.onGear(Gear.DRIVE)
        c.onLocation(point("office"))
        c.onUserSpeech(a.lines[0]); c.onUserSpeech(a.lines[1]); c.onUserSpeech(a.lines[2])
        time.advanceMinutes(20)
        c.onLocation(point("riverside"))
        c.onUserSpeech(a.lines[3]) // 이 노래 여기 남겨줘
        c.onUserSpeech(a.lines[4]) // 한마디
        time.advanceMinutes(15)
        c.onLocation(point("home"))
        c.onGear(Gear.PARK)
    }

    @Test fun driverALeavesSongAndGetsDiaryOnPark() {
        driveA()
        val mine = store.letters().filter { it.authorId == "driver_a" }
        assertEquals(1, mine.size)
        assertEquals("오늘도 버틴 나한테 주는 노래", mine.single().message)
        assertTrue(voice.spoken.contains(TestData.dialogue.prompt("leftOk")))

        val s = c.state.value
        assertEquals(DriveMode.PARKED, s.mode)
        val diary = assertNotNullAnd(s.latestDiary)
        assertEquals("tired", diary.moodId)
        assertTrue(diary.paragraphs.first().contains("35분"))
        // 부정 감정이므로 일기 BGM 1순위는 위로 곡이고, 주차 직후 그 곡이 재생된다
        assertTrue("comfort" in TestData.catalog.track(diary.bgmTrackIds.first())!!.tags)
        assertEquals(diary.bgmTrackIds.first(), music.currentTrackId)
        assertEquals(BgmController.parseRgb(TestData.lexicon.mood("tired").colorHex), vehicle.ambient)
        // 음성 명령 문장("남겨줘")은 일기에 인용되지 않는다
        assertFalse(diary.paragraphs.any { it.contains("남겨줘") })
    }

    @Test fun leavingIsBlockedNearHomeOrOffice() {
        c.onGear(Gear.DRIVE)
        c.onLocation(point("office"))
        c.onUserSpeech("이 노래 여기 남겨줘")
        assertTrue(voice.spoken.last() == TestData.dialogue.prompt("leftBlocked"))
        assertTrue(store.letters().none { it.authorId == "driver_a" })
    }

    @Test fun driverBReceivesThanksAndAIsNotifiedNextTrip() {
        driveA()
        val aLetter = store.letters().single { it.authorId == "driver_a" }

        // 다음 날 B
        val b = driver("driver_b")
        time.advanceMinutes(60 * 24); time.hour = b.startHour
        c.switchDriver("driver_b", sc.privateZones)
        c.onGear(Gear.DRIVE)
        c.onLocation(point("office"))
        c.onUserSpeech(b.lines[0]); c.onUserSpeech(b.lines[1])
        c.onLocation(point("downtown")) // 시드 편지(분노 테마)는 B와 결이 달라 제안되지 않아야 한다
        assertNull(c.state.value.pendingOffer)
        c.onLocation(point("riverside"))
        val offer = assertNotNullAnd(c.state.value.pendingOffer)
        assertEquals(aLetter.id, offer.letter.id)
        assertTrue(voice.spoken.last().contains(aLetter.message))

        c.onUserSpeech(b.lines[2]) // 응
        assertEquals(aLetter.trackId, music.currentTrackId)
        c.onUserSpeech(b.lines[3]) // 고마워
        assertEquals(listOf("driver_b"), store.letters().single { it.id == aLetter.id }.thankedBy)
        c.onLocation(point("home")); c.onGear(Gear.PARK)
        assertEquals(listOf(aLetter.id), c.state.value.latestDiary!!.receivedLetterIds)

        // 같은 편지는 다시 제안되지 않는다
        c.onGear(Gear.DRIVE); c.onUserSpeech(b.lines[0]); c.onLocation(point("riverside"))
        assertNull(c.state.value.pendingOffer)
        c.onGear(Gear.PARK)

        // 다음 운전 때 A가 '닿았어요' 알림을 한 번만 듣는다
        c.switchDriver("driver_a", sc.privateZones)
        voice.spoken.clear()
        c.onGear(Gear.DRIVE)
        assertEquals(TestData.dialogue.prompt("reached"), voice.spoken.first())
        c.onGear(Gear.PARK)
        voice.spoken.clear()
        c.onGear(Gear.DRIVE)
        assertFalse(voice.spoken.contains(TestData.dialogue.prompt("reached")))
    }

    @Test fun crisisMessageIsNotPublishedAndShowsHelpLine() {
        c.onGear(Gear.DRIVE)
        c.onUserSpeech("오늘 너무 힘들었어")
        c.onLocation(point("riverside"))
        c.onUserSpeech("이 노래 여기 남겨줘")
        c.onUserSpeech("다 끝내고 싶어")
        assertTrue(store.letters().none { it.authorId == "driver_a" })
        assertEquals(TestData.policy.helpLine, c.state.value.helpLine)
    }

    @Test fun screenTapsIgnoredWhileDriving() {
        c.onGear(Gear.DRIVE)
        val before = music.currentTrackId
        c.playFromParked("bright_morning")
        assertEquals(before, music.currentTrackId)
    }

    @Test fun parkWithoutTalkingMakesNoDiary() {
        c.onGear(Gear.DRIVE); c.onGear(Gear.PARK)
        assertNull(c.state.value.latestDiary)
        assertTrue(store.diaries().isEmpty())
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}
