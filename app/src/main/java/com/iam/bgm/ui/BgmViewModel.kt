package com.iam.bgm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iam.bgm.app.AutoDrive
import com.iam.bgm.app.DriveAction
import com.iam.bgm.app.DriveEnv
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.iam.bgm.app.BgmController
import com.iam.bgm.app.ManualLocationGate
import com.iam.bgm.app.ManualVehicleGate
import com.iam.bgm.core.Gear
import com.iam.bgm.platform.AssetData
import com.iam.bgm.platform.DemoDriver
import com.iam.bgm.platform.FileStore
import com.iam.bgm.platform.PleosLocationPort
import com.iam.bgm.platform.PleosNaviPort
import com.iam.bgm.platform.PleosVehiclePort
import com.iam.bgm.platform.PleosVoicePort
import com.iam.bgm.platform.RawMusicPlayer
import com.iam.bgm.platform.ScenarioClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 시연 콘솔 상태(심사용). 실제 차량에서는 SDK 이벤트가 같은 컨트롤러 메서드를 호출한다. */
data class ConsoleState(
    val driver: DemoDriver,
    val dayOffset: Long,
    val routeIndex: Int?,
    val nextLine: Int,
    val clockLabel: String,
    /** 자동 주행 진행률(0~1). null이면 자동 주행 중이 아님. */
    val autoProgress: Double? = null,
    /** 자유 주행(테스트): 켜면 주행 지도를 눌러 그쪽으로 달린다. */
    val freeDrive: Boolean = false,
    val freeSpeedKmh: Double = 60.0,
    val freeTarget: com.iam.bgm.core.GeoPoint? = null,
    /** 자유 주행 경로(도로 위 점 목록). 지도에 표시한다. */
    val freeRoute: List<com.iam.bgm.core.GeoPoint> = emptyList(),
)

class BgmViewModel(app: Application) : AndroidViewModel(app) {
    val data = AssetData(app)
    private val store = FileStore(app)
    private val clock = ScenarioClock()
    private val voice = PleosVoicePort(app)
    private val location = ManualLocationGate(PleosLocationPort(app))
    private val vehicle = ManualVehicleGate(PleosVehiclePort(app))
    private var counter = 0

    val controller = BgmController(
        lexicon = data.lexicon, catalog = data.catalog, dialogue = data.dialogue, policy = data.policy,
        vehicle = vehicle, location = location, voice = voice,
        navi = PleosNaviPort(app), music = RawMusicPlayer(app), time = clock, store = store,
        ids = { "L${clock.nowMillis()}-${counter++}" },
    )

    private val _console = MutableStateFlow(ConsoleState(data.scenario.drivers.first(), 0, null, 0, ""))
    val console: StateFlow<ConsoleState> = _console

    init {
        if (store.isEmpty) store.saveLetters(data.scenario.seedAsLetters(clock.nowMillis()))
        selectDriver(data.scenario.drivers.first(), dayOffset = 0)
        controller.start(_console.value.driver.id, data.scenario.privateZones)
    }

    fun selectDriver(d: DemoDriver, dayOffset: Long) {
        clock.set(dayOffset, d.startHour, d.startMinute)
        _console.value = ConsoleState(d, dayOffset, null, 0, clock.label())
        controller.switchDriver(d.id, data.scenario.privateZones)
    }

    fun gear(g: Gear) { vehicle.inject(g); refresh() }

    fun moveTo(index: Int) {
        val p = data.scenario.route[index]
        if (_console.value.routeIndex != null) clock.advanceMinutes(8)
        location.inject(p.point)
        _console.value = _console.value.copy(routeIndex = index)
        refresh()
    }

    fun sayLine(i: Int) {
        val line = _console.value.driver.lines[i]
        voice.stop() // 마이크 대기를 끊고 콘솔 문장을 실제 인식 결과처럼 주입
        controller.onUserSpeech(line)
        _console.value = _console.value.copy(nextLine = i + 1)
        refresh()
    }

    fun resetDemo() {
        stopAutoDrive()
        vehicle.inject(Gear.PARK)
        vehicle.release()
        location.release()
        store.reset()
        store.saveLetters(data.scenario.seedAsLetters(clock.nowMillis()))
        selectDriver(data.scenario.drivers.first(), 0)
    }

    /** 일기장·내 노래 화면용 조회(현재 운전자 기준). */
    fun letterById(id: String) = store.letters().firstOrNull { it.id == id }
    fun receivedLetters(): List<com.iam.bgm.core.MusicLetter> {
        val ids = store.received(controller.state.value.driverId)
        return store.letters().filter { it.id in ids }.sortedByDescending { it.createdAtMillis }
    }

    private var autoJob: Job? = null

    /**
     * 자동 주행: 실제 도로 경로를 따라 차를 움직이며 타임라인대로 기어·발화를 주입한다.
     * 콘솔 버튼과 같은 경로(위치 게이트·기어 게이트·발화 주입)를 쓰므로 컨트롤러는 실차와 같은 흐름을 탄다.
     */
    fun startAutoDrive() {
        val auto = data.scenario.autoDrive ?: return
        val timeline = auto.drivers[_console.value.driver.id] ?: return
        if (autoJob?.isActive == true || controller.state.value.mode != com.iam.bgm.core.DriveMode.PARKED) return
        val drive = AutoDrive(data.scenario.trackPoints, timeline)
        val env = object : DriveEnv {
            override val voiceBusy get() = voice.isSpeaking
            override val offerPending get() = controller.state.value.pendingOffer != null
            override fun canEnter(zoneId: String) = !(voice.isSpeaking && (controller.state.value.zoneLetterCounts[zoneId] ?: 0) > 0)
        }
        controller.onSpeed(drive.virtualSpeedKmh)
        val tickMs = 100L
        val df = tickMs / 1000.0 / auto.simSeconds
        autoJob = viewModelScope.launch {
            var lastMinute = 0L
            while (!drive.done) {
                for (a in drive.step(df, env)) when (a) {
                    is DriveAction.Move -> location.inject(a.p)
                    is DriveAction.SetGear -> vehicle.inject(if (a.gear == "D") Gear.DRIVE else Gear.PARK)
                    is DriveAction.Say -> sayLine(a.line)
                }
                val m = (drive.progress * drive.tripMinutes).toLong()
                if (m > lastMinute) { clock.advanceMinutes(m - lastMinute); lastMinute = m }
                _console.value = _console.value.copy(autoProgress = drive.progress, clockLabel = clock.label())
                delay(tickMs)
            }
            _console.value = _console.value.copy(autoProgress = null)
            controller.onSpeed(0.0)
        }
    }

    // ---------------- 자유 테스트 ----------------

    /** 입력한 문장을 음성 인식 결과처럼 넣는다(실차에서는 SpeechToText SDK가 같은 메서드를 부른다). */
    fun sayText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        voice.stop()
        controller.onUserSpeech(t)
        refresh()
    }

    fun setFreeDrive(on: Boolean) {
        _console.value = _console.value.copy(freeDrive = on, freeTarget = if (on) _console.value.freeTarget else null, freeRoute = if (on) _console.value.freeRoute else emptyList())
        if (!on) { freeJob?.cancel(); controller.onSpeed(0.0) }
    }

    fun setFreeSpeed(kmh: Double) {
        _console.value = _console.value.copy(freeSpeedKmh = kmh)
        if (freeJob?.isActive == true) controller.onSpeed(kmh)
    }

    private var freeJob: Job? = null

    /**
     * 자유 주행: 지금 위치에서 누른 곳까지 **도로를 따라** 달린다(앱 안 도로 그래프로 길찾기, 네트워크 불필요).
     * 출발·도착은 가까운 도로에 붙인다. 도로 그래프가 없거나 길이 없으면 직선으로 간다.
     * 실제 시간보다 [FREE_SIM_X]배 빠르게 움직이고, 시나리오 시계도 같은 비율로 흐른다.
     */
    fun freeDriveTo(target: com.iam.bgm.core.GeoPoint) {
        if (!_console.value.freeDrive || autoJob?.isActive == true) return
        val start = controller.state.value.position ?: data.scenario.route.first().point
        val road = data.roads?.route(start, target)
        val path = if (road != null && road.size >= 2) listOf(start) + road else listOf(start, target)
        _console.value = _console.value.copy(freeTarget = path.last(), freeRoute = path)
        freeJob?.cancel()
        freeJob = viewModelScope.launch {
            val tickMs = 100L
            var seg = 0
            var pos = path.first()
            var simSeconds = 0.0
            controller.onSpeed(_console.value.freeSpeedKmh)
            while (seg < path.size - 1) {
                var step = _console.value.freeSpeedKmh / 3.6 * (tickMs / 1000.0) * FREE_SIM_X
                // 이번 틱에 갈 거리만큼 경로 점을 따라 나아간다(구간 경계를 넘을 수 있음)
                while (step > 0 && seg < path.size - 1) {
                    val next = path[seg + 1]
                    val d = com.iam.bgm.core.Zone.metersBetween(pos, next)
                    if (d <= step) { pos = next; step -= d; seg++ }
                    else { pos = com.iam.bgm.core.GeoPoint(pos.lat + (next.lat - pos.lat) * step / d, pos.lng + (next.lng - pos.lng) * step / d); step = 0.0 }
                }
                location.inject(pos)
                simSeconds += tickMs / 1000.0 * FREE_SIM_X
                if (simSeconds >= 60) { clock.advanceMinutes((simSeconds / 60).toLong()); simSeconds %= 60 }
                refresh()
                delay(tickMs)
            }
            controller.onSpeed(0.0)
            _console.value = _console.value.copy(freeTarget = null, freeRoute = emptyList())
        }
    }

    fun stopAutoDrive() {
        autoJob?.cancel(); autoJob = null
        controller.onSpeed(0.0)
        _console.value = _console.value.copy(autoProgress = null)
    }

    private companion object { const val FREE_SIM_X = 8.0 }

    private fun refresh() { _console.value = _console.value.copy(clockLabel = clock.label()) }

    override fun onCleared() { controller.stop(); voice.release() }
}
