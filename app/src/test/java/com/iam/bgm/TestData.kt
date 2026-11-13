package com.iam.bgm

import com.iam.bgm.app.LocationPort
import com.iam.bgm.app.MusicPort
import com.iam.bgm.app.NaviPort
import com.iam.bgm.app.StorePort
import com.iam.bgm.app.TimePort
import com.iam.bgm.app.VehiclePort
import com.iam.bgm.app.VoicePort
import com.iam.bgm.core.BgmCatalog
import com.iam.bgm.core.ContentPolicy
import com.iam.bgm.core.DiaryEntry
import com.iam.bgm.core.Dialogue
import com.iam.bgm.core.EmotionLexicon
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Gear
import com.iam.bgm.core.MusicLetter
import com.iam.bgm.platform.BgmJson
import com.iam.bgm.platform.DemoScenario
import java.io.File

/** 테스트는 앱이 실제로 쓰는 assets JSON을 그대로 읽는다(데이터와 로직을 함께 검증). */
object TestData {
    private fun text(name: String) = File("src/main/assets/$name").readText(Charsets.UTF_8)
    val lexicon: EmotionLexicon by lazy { BgmJson.decodeFromString(text("emotion_lexicon.json")) }
    val catalog: BgmCatalog by lazy { BgmJson.decodeFromString(text("bgm_catalog.json")) }
    val dialogue: Dialogue by lazy { BgmJson.decodeFromString(text("dialogue.json")) }
    val policy: ContentPolicy by lazy { BgmJson.decodeFromString(text("content_policy.json")) }
    val scenario: DemoScenario by lazy { BgmJson.decodeFromString(text("demo_scenario.json")) }
}

class FakeVehicle : VehiclePort {
    override val label = "fake-vehicle"
    var onGear: ((Gear) -> Unit)? = null
    var ambient: Int? = null
    override fun start(onGear: (Gear) -> Unit) { this.onGear = onGear }
    override fun setAmbientColor(rgb: Int, onResult: (Boolean) -> Unit) { ambient = rgb; onResult(true) }
    override fun stop() {}
}

class FakeLocation : LocationPort {
    override val label = "fake-location"
    var watched: Map<String, GeoPoint> = emptyMap()
    var emit: ((GeoPoint) -> Unit)? = null
    override fun start(onLocation: (GeoPoint) -> Unit) { emit = onLocation }
    override fun watchZones(centers: Map<String, GeoPoint>, radiusMeters: Float, onEnter: (String) -> Unit) { watched = centers }
    override fun stop() {}
}

/** TTS는 즉시 끝난 것으로 보고, 마지막 발화와 마이크 대기 여부만 기록한다. */
class FakeVoice : VoicePort {
    override val label = "fake-voice"
    override var isSpeaking = false
    val spoken = mutableListOf<String>()
    var listening = false
    override fun speak(text: String, onDone: () -> Unit) { spoken += text; onDone() }
    override fun listen(onFinal: (String) -> Unit) { listening = true }
    override fun stop() { listening = false }
}

class FakeNavi : NaviPort {
    override val label = "fake-navi"
    override fun start(onArrived: () -> Unit, onNextTurnDistance: (Int?) -> Unit) {}
    override fun stop() {}
}

class FakeMusic : MusicPort {
    override var currentTrackId: String? = null
    val played = mutableListOf<String>()
    override fun play(trackId: String) { currentTrackId = trackId; played += trackId }
    override fun stop() { currentTrackId = null }
}

class FakeTime(var millis: Long = 1_700_000_000_000L, var hour: Int = 18) : TimePort {
    override fun nowMillis() = millis
    override fun hourOfDay() = hour
    override fun minuteOfHour() = 0
    override fun dateLabel() = "11월 14일"
    fun advanceMinutes(m: Int) { millis += m * 60_000L }
}

class MemoryStore : StorePort {
    private var letters = listOf<MusicLetter>()
    private var diaries = listOf<DiaryEntry>()
    private val received = mutableMapOf<String, Set<String>>()
    override fun letters() = letters
    override fun saveLetters(letters: List<MusicLetter>) { this.letters = letters }
    override fun diaries() = diaries
    override fun saveDiaries(diaries: List<DiaryEntry>) { this.diaries = diaries }
    override fun received(userId: String) = received[userId] ?: emptySet()
    override fun saveReceived(userId: String, ids: Set<String>) { received[userId] = ids }
}
