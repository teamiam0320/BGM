package com.iam.bgm.app

import com.iam.bgm.core.AmiDialogue
import com.iam.bgm.core.BgmCatalog
import com.iam.bgm.core.BgmRecommender
import com.iam.bgm.core.ContentFilter
import com.iam.bgm.core.ContentPolicy
import com.iam.bgm.core.DiaryComposer
import com.iam.bgm.core.DiaryEntry
import com.iam.bgm.core.Dialogue
import com.iam.bgm.core.DriveMode
import com.iam.bgm.core.DriveModeReducer
import com.iam.bgm.core.EmotionLexicon
import com.iam.bgm.core.FilterResult
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Gear
import com.iam.bgm.core.LetterMatcher
import com.iam.bgm.core.MoodAnalyzer
import com.iam.bgm.core.MoodDef
import com.iam.bgm.core.MusicLetter
import com.iam.bgm.core.SpeechGate
import com.iam.bgm.core.ThemeVector
import com.iam.bgm.core.Track
import com.iam.bgm.core.TripContext
import com.iam.bgm.core.Utterance
import com.iam.bgm.core.VoiceIntent
import com.iam.bgm.core.VoiceIntentParser
import com.iam.bgm.core.Zone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Caption(val speaker: String, val text: String)

data class Offer(val letter: MusicLetter, val track: Track)

data class UiState(
    val mode: DriveMode = DriveMode.PARKED,
    val driverId: String = "",
    val zoneId: String? = null,
    val captions: List<Caption> = emptyList(),
    val listening: Boolean = false,
    val nowPlaying: Track? = null,
    val pendingOffer: Offer? = null,
    val latestDiary: DiaryEntry? = null,
    val diaryMood: MoodDef? = null,
    val receivedThisTrip: List<Offer> = emptyList(),
    val myLetters: List<MusicLetter> = emptyList(),
    val helpLine: String? = null,
    val ambientApplied: Boolean? = null,
    val portLabels: List<String> = emptyList(),
)

/**
 * BGM의 상태 기계. 기어(P↔D)가 주행 세션의 시작·끝을 결정한다.
 * - 주행 시작: 에이미가 먼저 말을 걸고, 대화가 이어진다(음성 전용).
 * - 주행 중: "이 노래 여기 남겨줘" → 한마디 → 검수 → 구간에 저장 / 편지 구간 진입 → 테마 매칭 → 음성 제안.
 * - 주차: 대화를 일기로 정리, 감정 BGM 재생, 앰비언트 라이트를 감정 색으로.
 *
 * 세션 시작 시각(sessionStartMillis)과 마지막 주차 시각(parkedAtMillis)은 서로 다른 수명주기라 필드를 분리한다.
 */
class BgmController(
    lexicon: EmotionLexicon,
    private val catalog: BgmCatalog,
    private val dialogue: Dialogue,
    policy: ContentPolicy,
    private val vehicle: VehiclePort,
    private val location: LocationPort,
    private val voice: VoicePort,
    private val navi: NaviPort,
    private val music: MusicPort,
    private val time: TimePort,
    private val store: StorePort,
    private val ids: () -> String,
) {
    private val analyzer = MoodAnalyzer(lexicon)
    private val ami = AmiDialogue(dialogue, analyzer)
    private val recommender = BgmRecommender(catalog)
    private val composer = DiaryComposer(dialogue, analyzer, ami, recommender, lexicon)
    private val filter = ContentFilter(policy)
    private val matcher = LetterMatcher()
    private val helpLine = policy.helpLine

    private val _state = MutableStateFlow(UiState(portLabels = listOf(vehicle.label, location.label, voice.label, navi.label)))
    val state: StateFlow<UiState> = _state

    private enum class Await { NONE, MESSAGE, OFFER_ANSWER }

    private var mode = DriveMode.PARKED
    private var driverId = ""
    private var privateZones: Set<String> = emptySet()
    private var await = Await.NONE
    private var utterances = mutableListOf<Utterance>()
    private var turn = 0
    private var sessionStartMillis: Long? = null
    private var sessionStartHour = 0
    private var parkedAtMillis: Long? = null
    private var zoneId: String? = null
    private var nextTurnDistance: Int? = null
    private var lastAccepted: MusicLetter? = null
    private var receivedThisTrip = mutableListOf<Offer>()
    private var started = false

    fun start(driverId: String, privateZones: Set<String>) {
        this.driverId = driverId
        this.privateZones = privateZones
        if (!started) {
            started = true
            vehicle.start(::onGear)
            location.start(::onLocation)
            navi.start(onArrived = ::onArrived, onNextTurnDistance = { nextTurnDistance = it })
        }
        refreshLetterWatch()
        publish()
    }

    /** 데모 콘솔에서 운전자를 바꿀 때 사용. 주차 상태에서만 허용한다. */
    fun switchDriver(driverId: String, privateZones: Set<String>) {
        if (mode != DriveMode.PARKED) return
        this.driverId = driverId
        this.privateZones = privateZones
        _state.value = _state.value.copy(latestDiary = null, diaryMood = null, receivedThisTrip = emptyList(), captions = emptyList(), helpLine = null)
        publish()
    }

    /** 정차 화면에서 일기 BGM·받은 노래를 다시 재생. 주행 중에는 화면 조작을 받지 않는다. */
    fun playFromParked(trackId: String) {
        if (mode != DriveMode.PARKED) return
        playTrack(trackId)
    }

    fun stop() {
        voice.stop(); music.stop(); vehicle.stop(); location.stop(); navi.stop()
    }

    // ---------------- 차량 이벤트 ----------------

    fun onGear(gear: Gear) {
        val next = DriveModeReducer.reduce(mode, gear)
        if (next == mode) return
        mode = next
        if (next == DriveMode.DRIVING) beginTrip() else endTrip()
        publish()
    }

    fun onLocation(p: GeoPoint) {
        val z = Zone.idOf(p)
        if (z == zoneId) return
        zoneId = z
        publish()
        onZoneEntered(z)
    }

    /** FusedLocation 근접 알림(ProximityAlert ENTER)도 같은 경로로 들어온다. */
    fun onZoneEntered(z: String) {
        if (mode != DriveMode.DRIVING || await != Await.NONE) return
        if (!SpeechGate.canOffer(nextTurnDistance, voice.isSpeaking)) return
        val letter = matcher.match(
            zoneId = z,
            myThemes = sessionThemes(),
            me = driverId,
            letters = store.letters(),
            alreadyReceived = store.received(driverId),
            nowMillis = time.nowMillis(),
        ) ?: return
        val track = catalog.track(letter.trackId) ?: return
        val offer = Offer(letter, track)
        await = Await.OFFER_ANSWER
        markReceived(letter.id)
        receivedThisTrip += offer
        _state.value = _state.value.copy(pendingOffer = offer)
        say(dialogue.prompt("offer", "message" to letter.message))
    }

    fun onArrived() {
        if (mode == DriveMode.DRIVING) say(dialogue.prompt("arriving"), listenAfter = false)
    }

    // ---------------- 음성 ----------------

    fun onUserSpeech(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        utterances += Utterance(DiaryComposer.USER, text, time.nowMillis())
        caption(DiaryComposer.USER, text)
        when (await) {
            Await.MESSAGE -> handleMessage(text)
            Await.OFFER_ANSWER -> handleOfferAnswer(text)
            Await.NONE -> handleFree(text)
        }
        publish()
    }

    private fun handleFree(text: String) {
        when (VoiceIntentParser.parse(text)) {
            VoiceIntent.LEAVE_SONG -> requestLeave()
            VoiceIntent.THANKS -> if (!thankLastAccepted()) say(ami.reply(text, turn++))
            else -> say(ami.reply(text, turn++))
        }
    }

    private fun requestLeave() {
        val z = zoneId
        if (z == null || !matcher.canLeaveAt(z, privateZones)) {
            say(dialogue.prompt("leftBlocked"))
            return
        }
        if (music.currentTrackId == null) {
            playTrack(catalog.tracks.first().id)
            say(dialogue.prompt("nothingPlaying"), listenAfter = false)
        }
        await = Await.MESSAGE
        say(dialogue.prompt("askMessage"))
    }

    private fun handleMessage(text: String) {
        await = Await.NONE
        when (val r = filter.check(text)) {
            is FilterResult.Ok -> {
                val z = zoneId ?: return
                val trackId = music.currentTrackId ?: catalog.tracks.first().id
                val letter = MusicLetter(
                    id = ids(), authorId = driverId, zoneId = z, trackId = trackId, message = r.message,
                    themes = sessionThemes(), createdAtMillis = time.nowMillis(),
                )
                store.saveLetters(store.letters() + letter)
                refreshLetterWatch()
                say(dialogue.prompt("leftOk"))
            }
            FilterResult.Rejected -> say(dialogue.prompt("leftRejected"))
            FilterResult.Crisis -> {
                _state.value = _state.value.copy(helpLine = helpLine)
                say(dialogue.prompt("leftCrisis"), listenAfter = false)
            }
        }
    }

    private fun handleOfferAnswer(text: String) {
        val offer = _state.value.pendingOffer
        await = Await.NONE
        _state.value = _state.value.copy(pendingOffer = null)
        if (offer == null) return handleFree(text)
        when (VoiceIntentParser.parse(text, awaitingYesNo = true)) {
            VoiceIntent.YES -> {
                lastAccepted = offer.letter
                playTrack(offer.track.id)
                say(dialogue.prompt("offerAccepted", "title" to offer.track.title))
            }
            VoiceIntent.THANKS -> {
                lastAccepted = offer.letter
                playTrack(offer.track.id)
                thankLastAccepted()
            }
            VoiceIntent.NO -> say(dialogue.prompt("offerDeclined"))
            // 대답 대신 다른 이야기를 하면 제안은 일기에 보관하고 대화를 이어간다.
            else -> {
                if (VoiceIntentParser.parse(text) == VoiceIntent.LEAVE_SONG) requestLeave()
                else say(ami.reply(text, turn++))
            }
        }
    }

    private fun thankLastAccepted(): Boolean {
        val l = lastAccepted ?: return false
        val letters = store.letters()
        val updated = letters.map {
            if (it.id == l.id && driverId !in it.thankedBy) it.copy(thankedBy = it.thankedBy + driverId, reachNoticeSeen = false) else it
        }
        store.saveLetters(updated)
        lastAccepted = null
        say(dialogue.prompt("thanksSent"))
        return true
    }

    // ---------------- 세션 수명주기 ----------------

    private fun beginTrip() {
        sessionStartMillis = time.nowMillis()
        sessionStartHour = time.hourOfDay()
        utterances = mutableListOf()
        receivedThisTrip = mutableListOf()
        lastAccepted = null
        await = Await.NONE
        turn = 0
        _state.value = _state.value.copy(captions = emptyList(), latestDiary = null, diaryMood = null, helpLine = null, pendingOffer = null)
        playTrack(recommender.recommend(analyzer.moodOf(ThemeVector.EMPTY)).first().id)

        // 내가 남긴 노래가 누군가에게 닿았으면 먼저 알려준다.
        val reached = store.letters().filter { it.authorId == driverId && it.thankedBy.isNotEmpty() && !it.reachNoticeSeen }
        if (reached.isNotEmpty()) {
            store.saveLetters(store.letters().map { if (it in reached) it.copy(reachNoticeSeen = true) else it })
            say(dialogue.prompt("reached"), listenAfter = false)
        }
        say(ami.opener(sessionStartHour, time.minuteOfHour()))
    }

    private fun endTrip() {
        voice.stop()
        await = Await.NONE
        val start = sessionStartMillis
        val now = time.nowMillis()
        parkedAtMillis = now
        sessionStartMillis = null
        _state.value = _state.value.copy(pendingOffer = null, listening = false)
        if (start == null || utterances.none { it.speaker == DiaryComposer.USER }) return

        val minutes = ((now - start) / 60_000L).toInt().coerceAtLeast(1)
        val diary = composer.compose(
            id = ids(), authorId = driverId, utterances = utterances,
            trip = TripContext(sessionStartHour, minutes, time.dateLabel()),
            receivedLetterIds = receivedThisTrip.map { it.letter.id }, nowMillis = now,
        )
        store.saveDiaries(store.diaries() + diary)
        val mood = analyzer.moodOf(diary.themes)
        diary.bgmTrackIds.firstOrNull()?.let { playTrack(it) }
        vehicle.setAmbientColor(parseRgb(mood.colorHex)) { ok -> _state.value = _state.value.copy(ambientApplied = ok) }
        _state.value = _state.value.copy(latestDiary = diary, diaryMood = mood)
        say("오늘의 일기가 완성됐어요. 마음 날씨는 ${mood.weather}예요.", listenAfter = false)
    }

    // ---------------- 보조 ----------------

    private fun sessionThemes(): ThemeVector =
        analyzer.themesOf(
            utterances.filter { it.speaker == DiaryComposer.USER }.map { it.text }
                .filter { VoiceIntentParser.parse(it) == VoiceIntent.FREE_TEXT },
        )

    private fun markReceived(letterId: String) {
        store.saveReceived(driverId, store.received(driverId) + letterId)
    }

    private fun refreshLetterWatch() {
        val zones = store.letters().filter { it.authorId != driverId }.map { it.zoneId }.distinct()
        location.watchZones(zones.associateWith { Zone.centerOf(it) }, radiusMeters = 300f) { onZoneEntered(it) }
    }

    private fun playTrack(id: String) {
        music.play(id)
        publish()
    }

    private fun say(text: String, listenAfter: Boolean = true) {
        utterances += Utterance(DiaryComposer.AMI, text, time.nowMillis())
        caption(DiaryComposer.AMI, text)
        voice.speak(text) {
            if (listenAfter && mode == DriveMode.DRIVING) {
                _state.value = _state.value.copy(listening = true)
                voice.listen { heard ->
                    _state.value = _state.value.copy(listening = false)
                    onUserSpeech(heard)
                }
            }
        }
    }

    private fun caption(speaker: String, text: String) {
        _state.value = _state.value.copy(captions = (_state.value.captions + Caption(speaker, text)).takeLast(6))
    }

    private fun publish() {
        _state.value = _state.value.copy(
            mode = mode,
            driverId = driverId,
            zoneId = zoneId,
            nowPlaying = music.currentTrackId?.let { catalog.track(it) },
            receivedThisTrip = receivedThisTrip.toList(),
            myLetters = store.letters().filter { it.authorId == driverId },
        )
    }

    companion object {
        fun parseRgb(hex: String): Int = hex.removePrefix("#").toInt(16)
    }
}
