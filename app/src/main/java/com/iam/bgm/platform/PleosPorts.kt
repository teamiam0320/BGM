package com.iam.bgm.platform

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.iam.bgm.app.LocationPort
import com.iam.bgm.app.NaviPort
import com.iam.bgm.app.VehiclePort
import com.iam.bgm.app.VoicePort
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Gear
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/*
 * Pleos Connect SDK 어댑터. 각 SDK 연결이 실패하면 상태 라벨에 "미연결"로 남기고, 앱은 시연 콘솔 입력으로 계속 동작한다.
 * 모든 콜백은 메인 스레드로 옮겨 컨트롤러 상태를 단일 스레드에서만 바꾼다.
 */

private const val TAG = "BGM/Pleos"
private val main = Handler(Looper.getMainLooper())
private fun ui(block: () -> Unit) { main.post(block) }

class PleosVehiclePort(context: Context) : VehiclePort {
    private val vehicle = Vehicle(context.applicationContext)
    private var connected = false
    private var gearListener: GearSelectionListener? = null
    override var label = "Vehicle SDK: 연결 중"; private set

    override fun start(onGear: (Gear) -> Unit) {
        try {
            vehicle.initialize()
            connected = true
            val gear = vehicle.getGear()
            gear.getCurrentGearSelectionState({ g -> ui { onGear(map(g)) } }, { e -> Log.w(TAG, "gear read", e) })
            val l = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = ui { onGear(map(gear)) }
                override fun onFailed(exception: Exception) { Log.w(TAG, "gear listener", exception) }
            }
            gear.registerGearSelection(l)
            gearListener = l
            label = "Vehicle SDK: 기어 구독 중"
        } catch (e: Exception) {
            Log.w(TAG, "vehicle init", e)
            label = "Vehicle SDK: 미연결(${e.javaClass.simpleName})"
        }
    }

    override fun setAmbientColor(rgb: Int, onResult: (Boolean) -> Unit) {
        if (!connected) return onResult(false)
        try {
            vehicle.getLight().setAmbientLightBrightnessColor(
                SeatArea(SeatValue.ROW_1_LEFT), rgb,
                { _, _ -> ui { onResult(true) } },
                { e -> Log.w(TAG, "ambient", e); ui { onResult(false) } },
            )
        } catch (e: Exception) {
            Log.w(TAG, "ambient", e); onResult(false)
        }
    }

    override fun stop() {
        if (!connected) return
        try {
            gearListener?.let { vehicle.getGear().unregisterGearSelection(it) }
            vehicle.release()
        } catch (e: Exception) { Log.w(TAG, "vehicle release", e) }
        connected = false
    }

    private fun map(g: VehicleGear?): Gear = when (g) {
        null -> Gear.UNKNOWN
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }
}

class PleosLocationPort(context: Context) : LocationPort {
    private val fused = FusedLocation(context.applicationContext)
    private var connected = false
    private var locListener: FusedLocationListener? = null
    private val alertIds = mutableMapOf<String, String>() // alertId -> zoneId
    override var label = "FusedLocation SDK: 연결 중"; private set

    override fun start(onLocation: (GeoPoint) -> Unit) {
        try {
            fused.initialize()
            if (!fused.isSupported()) { label = "FusedLocation SDK: 미지원 환경"; return }
            connected = true
            val l = object : FusedLocationListener {
                override fun onChanged(data: LocationData?) { if (data != null) ui { onLocation(GeoPoint(data.latitude, data.longitude)) } }
                override fun onError(e: Exception) { Log.w(TAG, "location", e) }
            }
            fused.registerFusedLocationCallback(l)
            locListener = l
            label = "FusedLocation SDK: 위치·근접알림 구독 중"
        } catch (e: Exception) {
            Log.w(TAG, "fused init", e)
            label = "FusedLocation SDK: 미연결(${e.javaClass.simpleName})"
        }
    }

    override fun watchZones(centers: Map<String, GeoPoint>, radiusMeters: Float, onEnter: (String) -> Unit) {
        if (!connected) return
        try {
            alertIds.keys.toList().forEach { fused.removeProximityAlert(it) }
            alertIds.clear()
            for ((zoneId, c) in centers) {
                fused.addProximityAlert(
                    ProximityAlertRequest(c.lat, c.lng, radiusMeters, setOf(ProximityTransition.ENTER)),
                    object : ProximityAlertListener {
                        override fun onRegistered(alertId: String) { alertIds[alertId] = zoneId }
                        override fun onTransition(event: ProximityAlertEvent) {
                            if (event.transition == ProximityTransition.ENTER) ui { onEnter(alertIds[event.alertId] ?: zoneId) }
                        }
                        override fun onError(e: Exception) { Log.w(TAG, "proximity", e) }
                    },
                )
            }
        } catch (e: Exception) { Log.w(TAG, "proximity", e) }
    }

    override fun stop() {
        if (!connected) return
        try {
            alertIds.keys.forEach { fused.removeProximityAlert(it) }
            locListener?.let { fused.unregisterFusedLocationCallback(it) }
            fused.release()
        } catch (e: Exception) { Log.w(TAG, "fused release", e) }
        connected = false
    }
}

/**
 * 음성 입출력. 둘 다 ON_DEVICE 모드(네트워크·앱 등록 불필요)로 시작한다.
 * 공모전 규정상 차량 마이크 음성은 외부 음성인식으로 보내지 않고 SpeechToText SDK만 사용한다.
 * TTS 큐는 SDK에 문서화되어 있지 않아 앱에서 직접 순서대로 보낸다(onDone → 다음 문장).
 */
class PleosVoicePort(context: Context) : VoicePort {
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private var sttReady = false
    private var ttsReady = false
    private val queue = ArrayDeque<Pair<String, () -> Unit>>()
    private var currentDone: (() -> Unit)? = null
    private var pendingListen: ((String) -> Unit)? = null
    private var sttState = "연결 중"
    private var ttsState = "연결 중"
    override val label get() = "SpeechToText SDK: $sttState · TextToSpeech SDK: $ttsState"
    override var isSpeaking = false; private set

    init {
        try {
            tts.initialize(object : TtsInitListener {
                override fun onSuccess() = ui {
                    ttsReady = true; ttsState = "준비됨"
                    tts.setLocale(Locale.KOREA)
                    pump()
                }
                override fun onFailure(errorType: TtsError) = ui { ttsState = "실패($errorType)"; flushAsCaptionOnly() }
            })
            tts.addEventListener(object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() = ui { finishCurrent() }
                override fun onError(errorType: TtsError) = ui { Log.w(TAG, "tts $errorType"); finishCurrent() }
                override fun onStop() = ui { isSpeaking = false }
                override fun onUpdatedRms(rms: Double) {}
            })
        } catch (e: Exception) { Log.w(TAG, "tts init", e); ttsState = "미연결" }

        try {
            stt.initialize(object : SttInitListener {
                override fun onSuccess() = ui { sttReady = true; sttState = "준비됨"; stt.setLocale(Locale.KOREA) }
                override fun onFailure(errorType: SttError) = ui { sttState = "실패($errorType)" }
            })
            stt.addListener(object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    if (isFinal && text.isNotBlank()) ui { val cb = pendingListen; pendingListen = null; cb?.invoke(text) }
                }
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(errorType: SttError) = ui { Log.w(TAG, "stt $errorType"); sttState = "오류($errorType)" }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            })
        } catch (e: Exception) { Log.w(TAG, "stt init", e); sttState = "미연결" }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        queue.addLast(text to onDone)
        if (ttsState.startsWith("실패") || ttsState == "미연결") flushAsCaptionOnly() else pump()
    }

    private fun pump() {
        if (!ttsReady || isSpeaking) return
        val (text, done) = queue.removeFirstOrNull() ?: return
        isSpeaking = true
        currentDone = done
        try { tts.speak(text, true) } catch (e: Exception) { Log.w(TAG, "speak", e); finishCurrent() }
    }

    private fun finishCurrent() {
        isSpeaking = false
        val d = currentDone; currentDone = null
        d?.invoke()
        pump()
    }

    /** TTS가 없으면 화면 자막만으로 진행한다(다음 단계가 막히지 않게). */
    private fun flushAsCaptionOnly() {
        while (queue.isNotEmpty()) queue.removeFirst().second.invoke()
    }

    override fun listen(onFinal: (String) -> Unit) {
        pendingListen = onFinal
        if (!sttReady) return
        try { stt.request() } catch (e: Exception) { Log.w(TAG, "stt request", e) }
    }

    override fun stop() {
        queue.clear(); currentDone = null; pendingListen = null; isSpeaking = false
        try { tts.stop() } catch (_: Exception) {}
        try { if (sttReady) stt.stop() } catch (_: Exception) {}
    }

    fun release() {
        stop()
        try { tts.release() } catch (_: Exception) {}
        try { stt.release() } catch (_: Exception) {}
    }
}

class PleosNaviPort(context: Context) : NaviPort {
    private val navi = NaviHelper(context.applicationContext)
    private var listener: NaviHelperEventListener? = null
    override var label = "NaviHelper SDK: 연결 중"; private set

    override fun start(onArrived: () -> Unit, onNextTurnDistance: (Int?) -> Unit) {
        try {
            navi.initialize({
                val l = object : NaviHelperEventListener {
                    override fun onDestinationArrived(info: ai.pleos.playground.navi.data.DestinationArrivedInfo) = ui { onArrived() }
                    override fun onRouteEnded() = ui { onNextTurnDistance(null) }
                }
                navi.addListener(l)
                listener = l
                label = "NaviHelper SDK: 도착 이벤트 구독 중"
            }, { t -> Log.w(TAG, "navi init", t); label = "NaviHelper SDK: 미연결(${t.javaClass.simpleName})" })
        } catch (e: Exception) {
            Log.w(TAG, "navi init", e)
            label = "NaviHelper SDK: 미연결(${e.javaClass.simpleName})"
        }
    }

    override fun stop() {
        try { listener?.let { navi.removeListener(it) }; navi.release() } catch (_: Exception) {}
    }
}
