package com.iam.bgm

import com.iam.bgm.core.BgmRecommender
import com.iam.bgm.core.ContentFilter
import com.iam.bgm.core.DiaryComposer
import com.iam.bgm.core.DriveMode
import com.iam.bgm.core.DriveModeReducer
import com.iam.bgm.core.FilterResult
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Gear
import com.iam.bgm.core.LetterMatcher
import com.iam.bgm.core.MoodAnalyzer
import com.iam.bgm.core.MusicLetter
import com.iam.bgm.core.SpeechGate
import com.iam.bgm.core.ThemeVector
import com.iam.bgm.core.VoiceIntent
import com.iam.bgm.core.VoiceIntentParser
import com.iam.bgm.core.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    private val analyzer = MoodAnalyzer(TestData.lexicon)

    @Test fun unknownGearKeepsPreviousMode() {
        assertEquals(DriveMode.DRIVING, DriveModeReducer.reduce(DriveMode.PARKED, Gear.DRIVE))
        assertEquals(DriveMode.DRIVING, DriveModeReducer.reduce(DriveMode.DRIVING, Gear.UNKNOWN))
        assertEquals(DriveMode.PARKED, DriveModeReducer.reduce(DriveMode.PARKED, Gear.UNKNOWN))
        assertEquals(DriveMode.PARKED, DriveModeReducer.reduce(DriveMode.DRIVING, Gear.PARK))
    }

    @Test fun zoneQuantizesToAbout500mAndRoundTrips() {
        val a = Zone.idOf(GeoPoint(37.5280, 127.0150))
        val b = Zone.idOf(GeoPoint(37.5281, 127.0151)) // 약 14m 이동
        assertEquals(a, b)
        assertEquals(a, Zone.idOf(Zone.centerOf(a)))
        val far = Zone.idOf(GeoPoint(37.5445, 127.0560))
        assertFalse(Zone.isNear(a, far))
        assertTrue(Zone.isNear(a, a))
    }

    @Test fun negativeMoodNeverGetsSadderMusic() {
        val rec = BgmRecommender(TestData.catalog)
        for (mood in TestData.lexicon.moods) {
            val tracks = rec.recommend(mood)
            assertTrue("${mood.id}: 추천곡이 없음", tracks.isNotEmpty())
            assertTrue("${mood.id}: 현재 감정보다 어두운 곡 추천", tracks.all { it.valence >= mood.valence })
            if (mood.valence < 0) assertTrue("${mood.id}: 위로 곡이 1순위가 아님", "comfort" in tracks.first().tags)
        }
    }

    @Test fun recommendationIsDeterministic() {
        val rec = BgmRecommender(TestData.catalog)
        val mood = TestData.lexicon.mood("tired")
        assertEquals(rec.recommend(mood), rec.recommend(mood))
    }

    @Test fun moodAnalysisFromScenarioLine() {
        val v = analyzer.themesOf("오늘 회사에서 기획안이 반려돼서 너무 지쳤어")
        assertEquals("work_stress", v.top(1).single())
        assertEquals("tired", analyzer.moodOf(v).id)
        assertEquals("neutral", analyzer.moodOf(analyzer.themesOf("음")).id)
    }

    @Test fun voiceIntents() {
        assertEquals(VoiceIntent.LEAVE_SONG, VoiceIntentParser.parse("아미, 이 노래 여기 남겨줘"))
        assertEquals(VoiceIntent.THANKS, VoiceIntentParser.parse("고마워"))
        assertEquals(VoiceIntent.YES, VoiceIntentParser.parse("응", awaitingYesNo = true))
        assertEquals(VoiceIntent.NO, VoiceIntentParser.parse("아니 괜찮아", awaitingYesNo = true))
        // 예/아니오를 기다리지 않을 때 "응"은 일반 대화로 본다
        assertEquals(VoiceIntent.FREE_TEXT, VoiceIntentParser.parse("응"))
    }

    @Test fun contentFilter() {
        val f = ContentFilter(TestData.policy)
        assertEquals(FilterResult.Ok("오늘도 버틴 나한테 주는 노래"), f.check(" 오늘도 버틴 나한테 주는 노래 "))
        assertEquals(FilterResult.Crisis, f.check("그냥 죽고 싶다"))
        assertEquals(FilterResult.Rejected, f.check("연락줘 010-1234-5678"))
        assertEquals(FilterResult.Rejected, f.check("www.example.com 와봐"))
        assertEquals(FilterResult.Rejected, f.check(""))
        val long = f.check("가".repeat(100)) as FilterResult.Ok
        assertEquals(TestData.policy.maxMessageLength, long.message.length)
    }

    private fun letter(id: String, zone: String, author: String, themes: Map<String, Double>, at: Long) =
        MusicLetter(id, author, zone, "warm_home", "msg", ThemeVector(themes), at)

    @Test fun matcherFiltersAndRanks() {
        val now = 100L * 86_400_000L
        val z = "1:1"
        val me = ThemeVector(mapOf("work_stress" to 3.0, "self_doubt" to 2.0))
        val letters = listOf(
            letter("mine", z, "me", mapOf("work_stress" to 1.0), now),
            letter("otherZone", "9:9", "x", mapOf("work_stress" to 1.0), now),
            letter("dissimilar", z, "x", mapOf("joy" to 1.0), now),
            letter("expired", z, "x", mapOf("work_stress" to 1.0), now - 31L * 86_400_000L),
            letter("ok_old", z, "x", mapOf("work_stress" to 1.0), now - 1000),
            letter("ok_best", z, "y", mapOf("work_stress" to 3.0, "self_doubt" to 2.0), now - 5000),
        )
        val m = LetterMatcher()
        assertEquals("ok_best", m.match(z, me, "me", letters, emptySet(), now)?.id)
        assertEquals("ok_old", m.match(z, me, "me", letters, setOf("ok_best"), now)?.id)
        assertNull(m.match(z, ThemeVector.EMPTY, "me", letters, emptySet(), now))
        assertFalse(m.canLeaveAt("5:5", listOf("5:6")))
        assertTrue(m.canLeaveAt("5:5", listOf("7:7")))
    }

    /** 수신 반경은 차속 × 15초, 200~600m. */
    @Test fun pickupRadiusFollowsSpeed() {
        val p = TestData.policy.pickup
        assertEquals(200.0, p.radiusMeters(0.0), 1e-9)
        assertEquals(200.0, p.radiusMeters(30.0), 1e-9)       // 도심 30km/h → 125m → 최소 200m
        assertEquals(333.3, p.radiusMeters(80.0), 0.1)        // 강변북로 80km/h
        assertEquals(600.0, p.radiusMeters(200.0), 1e-9)      // 상한
    }

    /** 옆 구간의 노래는 반경이 넓을 때(빠를 때)만 미리 받는다. 같은 구간이면 반경과 무관하게 받는다. */
    @Test fun matchNearUsesRadiusAroundZoneCenter() {
        val now = 100L * 86_400_000L
        val t = mapOf("work_stress" to 1.0)
        val here = GeoPoint(37.5300, 127.0000)
        val hereZone = Zone.idOf(here)
        val (a, b) = hereZone.split(":").map { it.toLong() }
        val nextZone = "${a + 1}:$b" // 북쪽 옆 구간(중심이 약 300~700m 떨어짐)
        val far = Zone.metersBetween(here, Zone.centerOf(nextZone))
        val letters = listOf(letter("next", nextZone, "x", t, now))
        val m = LetterMatcher()
        val me = ThemeVector(t)
        assertNull(m.matchNear(here, far - 1, me, "me", letters, emptySet(), now))
        assertEquals("next", m.matchNear(here, far + 1, me, "me", letters, emptySet(), now)?.id)
        val same = listOf(letter("same", hereZone, "x", t, now))
        assertEquals("same", m.matchNear(here, 0.0, me, "me", same, emptySet(), now)?.id)
    }

    /** 지도에는 내 것·이미 받은 것·만료된 것을 뺀 다른 사람 노래의 '개수'만 나간다. */
    @Test fun waitingCountsExcludeMineReceivedAndExpired() {
        val now = 100L * 86_400_000L
        val t = mapOf("joy" to 1.0)
        val letters = listOf(
            letter("mine", "1:1", "me", t, now),
            letter("a", "1:1", "x", t, now),
            letter("b", "1:1", "y", t, now - 1000),
            letter("received", "1:1", "z", t, now),
            letter("expired", "2:2", "x", t, now - 31L * 86_400_000L),
            letter("c", "3:3", "x", t, now),
        )
        val counts = LetterMatcher().waitingCountsByZone("me", letters, setOf("received"), now)
        assertEquals(mapOf("1:1" to 2, "3:3" to 1), counts)
    }

    @Test fun speechGateDefersNearTurns() {
        assertTrue(SpeechGate.canOffer(null, ttsBusy = false))
        assertFalse(SpeechGate.canOffer(120, ttsBusy = false))
        assertTrue(SpeechGate.canOffer(800, ttsBusy = false))
        assertFalse(SpeechGate.canOffer(null, ttsBusy = true))
    }

    @Test fun koreanParticle() {
        assertEquals("업무 스트레스와 피로", DiaryComposer.joinWithGwa(listOf("업무 스트레스", "피로")))
        assertEquals("슬픔과 불안", DiaryComposer.joinWithGwa(listOf("슬픔", "불안")))
    }

    /** 회귀: 주차 안내가 받침과 무관하게 "~예요"를 붙여 "흐림예요"로 말하던 결함. */
    @Test fun copulaFollowsBatchim() {
        val expected = mapOf(
            "맑음" to "맑음이에요", "햇살" to "햇살이에요", "흐림" to "흐림이에요", "천둥" to "천둥이에요",
            "눈" to "눈이에요", "구름 조금" to "구름 조금이에요",
            "비" to "비예요", "안개" to "안개예요", "무지개" to "무지개예요",
        )
        // 감정 사전의 모든 날씨가 빠짐없이 검사되고, 올바른 조사로 끝난다
        assertEquals(expected.keys, TestData.lexicon.moods.map { it.weather }.toSet())
        for ((weather, want) in expected) assertEquals(weather, want, DiaryComposer.withCopula(weather))
    }
}

/** 회귀: 시계가 거꾸로 가면(편지 작성 시각 > 현재) 그 편지는 매칭되지 않는다 — 시연 시계는 단조 증가해야 하는 이유. */
class ClockMonotonicTest {
    @Test fun letterFromTheFutureIsNeverMatched() {
        val z = "1:1"
        val t = ThemeVector(mapOf("work_stress" to 1.0))
        val l = MusicLetter("l", "a", z, "warm_home", "m", t, createdAtMillis = 2_000)
        assertNull(LetterMatcher().match(z, t, "b", listOf(l), emptySet(), nowMillis = 1_000))
        assertEquals("l", LetterMatcher().match(z, t, "b", listOf(l), emptySet(), nowMillis = 3_000)?.id)
    }
}
