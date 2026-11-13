package com.iam.bgm.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iam.bgm.app.Offer
import com.iam.bgm.app.UiState
import com.iam.bgm.core.DriveMode
import com.iam.bgm.core.Gear
import com.iam.bgm.core.MoodDef
import com.iam.bgm.core.Track

private val Ink = Color(0xFF0E1320)
private val Panel = Color(0xFF171E2E)
private val Line = Color(0xFF2A3348)
private val Soft = Color(0xFFB9C2D6)
private val Accent = Color(0xFF8FB7FF)

private fun hex(h: String) = Color(0xFF000000 or h.removePrefix("#").toLong(16))

@Composable
fun BgmApp(vm: BgmViewModel) {
    val s by vm.controller.state.collectAsState()
    val c by vm.console.collectAsState()
    var consoleOpen by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxSize().background(Ink)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (s.mode == DriveMode.DRIVING) DrivingScreen(s) else ParkedScreen(s, vm)
            OutlinedButton(
                onClick = { consoleOpen = !consoleOpen },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) { Text(if (consoleOpen) "시연 콘솔 닫기" else "시연 콘솔", color = Soft, fontSize = 16.sp) }
        }
        if (consoleOpen) DemoConsole(vm, s, c)
    }
}

// ---------------- 주행 화면: 음성 전용, 최소 표시 ----------------

@Composable
private fun DrivingScreen(s: UiState) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val k by pulse.animateFloat(1f, if (s.listening) 1.12f else 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "k")
    Column(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("주행 중 · 음성으로만 대화해요", color = Soft, fontSize = 22.sp)
        Box(
            Modifier.size(260.dp).scale(k).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Accent, Color(0xFF3B5BA8), Ink))),
            contentAlignment = Alignment.Center,
        ) { Text(if (s.listening) "듣고 있어요" else "에이미", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold) }

        val lastAmi = s.captions.lastOrNull { it.speaker == "ami" }?.text.orEmpty()
        val lastUser = s.captions.lastOrNull { it.speaker == "user" }?.text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (lastUser != null) Text("“$lastUser”", color = Soft, fontSize = 26.sp, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(16.dp))
            Text(lastAmi, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 3, lineHeight = 50.sp)
        }
        s.pendingOffer?.let { OfferBanner(it) } ?: NowPlaying(s.nowPlaying)
    }
}

@Composable
private fun OfferBanner(o: Offer) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF2B2140)).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♪", color = Color(0xFFE3A9FF), fontSize = 44.sp)
        Spacer(Modifier.width(24.dp))
        Column {
            Text("누군가 이 길에 남긴 노래 · ${o.track.title}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("“${o.letter.message}”  —  \"응\" 하면 재생해요", color = Soft, fontSize = 24.sp)
        }
    }
}

@Composable
private fun NowPlaying(t: Track?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♪", color = Accent, fontSize = 44.sp)
        Spacer(Modifier.width(24.dp))
        Column {
            Text(t?.title ?: "재생 중인 BGM 없음", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("\"에이미, 이 노래 여기 남겨줘\"라고 말하면 이 구간에 남길 수 있어요", color = Soft, fontSize = 22.sp)
        }
    }
}

// ---------------- 정차 화면: 일기·BGM·받은 노래 ----------------

@Composable
private fun ParkedScreen(s: UiState, vm: BgmViewModel) {
    val diary = s.latestDiary
    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(
            Modifier.weight(1.3f).fillMaxHeight().clip(RoundedCornerShape(32.dp)).background(Panel).padding(40.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (diary == null) {
                Text("BGM", color = Accent, fontSize = 64.sp, fontWeight = FontWeight.Black)
                Text("퇴근길 30분, 마음을 내려놓는 차", color = Color.White, fontSize = 34.sp)
                Spacer(Modifier.height(24.dp))
                Text("기어를 D로 바꾸면 에이미가 먼저 말을 걸어요.\n주차하면 오늘의 대화가 일기와 BGM으로 완성됩니다.", color = Soft, fontSize = 24.sp, lineHeight = 36.sp)
            } else {
                Text("오늘의 일기", color = Soft, fontSize = 22.sp)
                Text(diary.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                diary.paragraphs.forEach {
                    Text(it, color = Color(0xFFE7EBF5), fontSize = 26.sp, lineHeight = 40.sp)
                    Spacer(Modifier.height(16.dp))
                }
                s.helpLine?.let {
                    Text("도움이 필요할 때: $it", color = Color(0xFFFFC2B8), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            s.diaryMood?.let { MoodCard(it, s.ambientApplied) }
            if (diary != null) {
                Card("오늘의 일기 BGM") {
                    diary.bgmTrackIds.mapNotNull { vm.data.catalog.track(it) }.forEach { t ->
                        TrackRow(t, playing = s.nowPlaying?.id == t.id) { vm.controller.playFromParked(t.id) }
                    }
                }
            }
            if (s.receivedThisTrip.isNotEmpty()) {
                Card("오늘 받은 노래") {
                    s.receivedThisTrip.forEach { o ->
                        TrackRow(o.track, playing = s.nowPlaying?.id == o.track.id, note = "“${o.letter.message}”") { vm.controller.playFromParked(o.track.id) }
                    }
                }
            }
            if (s.myLetters.isNotEmpty()) {
                Card("내가 길 위에 남긴 노래") {
                    s.myLetters.forEach { l ->
                        val t = vm.data.catalog.track(l.trackId)
                        Text("${t?.title ?: l.trackId} · “${l.message}”", color = Color.White, fontSize = 22.sp)
                        Text(if (l.thankedBy.isEmpty()) "아직 닿기를 기다리는 중" else "${l.thankedBy.size}명에게 닿았어요", color = Soft, fontSize = 20.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodCard(m: MoodDef, ambient: Boolean?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(hex(m.colorHex)))
        Spacer(Modifier.width(24.dp))
        Column {
            Text("마음 날씨 · ${m.weather} (${m.label})", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when (ambient) {
                    true -> "앰비언트 라이트를 오늘의 감정 색으로 바꿨어요"
                    false -> "앰비언트 라이트 제어를 지원하지 않는 환경이에요"
                    null -> "앰비언트 라이트 적용 중"
                },
                color = Soft, fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp)) {
        Text(title, color = Soft, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun TrackRow(t: Track, playing: Boolean, note: String? = null, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(note ?: t.artist, color = Soft, fontSize = 20.sp)
        }
        Button(
            onClick = onPlay,
            colors = ButtonDefaults.buttonColors(containerColor = if (playing) Accent else Line),
            modifier = Modifier.height(64.dp),
        ) { Text(if (playing) "재생 중" else "재생", fontSize = 22.sp, color = if (playing) Ink else Color.White) }
    }
}

// ---------------- 시연 콘솔(심사용) ----------------

@Composable
private fun DemoConsole(vm: BgmViewModel, s: UiState, c: ConsoleState) {
    Column(
        Modifier.width(560.dp).fillMaxHeight().background(Color(0xFF0A0E18)).border(1.dp, Line)
            .padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("시연 콘솔 (심사용)", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("실차에서는 기어·위치·음성이 Pleos SDK에서 들어옵니다. 여기서는 같은 이벤트를 수동으로 주입합니다.", color = Soft, fontSize = 15.sp)
        Text("시나리오 시각 ${c.clockLabel} · ${if (s.mode == DriveMode.DRIVING) "주행" else "주차"}", color = Accent, fontSize = 18.sp)

        Label("1. 운전자")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.data.scenario.drivers.forEach { d ->
                Chip(d.label, selected = d.id == c.driver.id, enabled = s.mode == DriveMode.PARKED) { vm.selectDriver(d, dayOffset = c.dayOffset + 1) /* 운전자 전환 = 다음 날. 시계가 거꾸로 가면 편지 만료 판정이 깨진다 */ }
            }
        }
        Label("2. 기어 (Vehicle SDK 이벤트)")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("P 주차", selected = s.mode == DriveMode.PARKED) { vm.gear(Gear.PARK) }
            Chip("D 주행", selected = s.mode == DriveMode.DRIVING) { vm.gear(Gear.DRIVE) }
        }
        Label("3. 위치 (FusedLocation SDK 이벤트)")
        vm.data.scenario.route.forEachIndexed { i, p ->
            Chip("${i + 1}. ${p.label}${if (p.privateZone) " · 사적 구간" else ""}", selected = c.routeIndex == i) { vm.moveTo(i) }
        }
        Label("4. 운전자 발화 (SpeechToText SDK 결과)")
        c.driver.lines.forEachIndexed { i, line ->
            Chip("“$line”", selected = false, enabled = s.mode == DriveMode.DRIVING, highlight = i == c.nextLine) { vm.sayLine(i) }
        }
        Label("SDK 연결 상태")
        s.portLabels.forEach { Text("· $it", color = Soft, fontSize = 14.sp) }
        Text("· 현재 구간 ${s.zoneId ?: "-"}", color = Soft, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.resetDemo() }, enabled = true) { Text("시연 데이터 초기화", color = Soft) }
    }
}

@Composable
private fun Label(t: String) = Text(t, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean = true, highlight: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = when { selected -> Accent; highlight -> Color(0xFF34406A); else -> Line },
            disabledContainerColor = Color(0xFF1A2030),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text, color = if (selected) Ink else Color.White, fontSize = 16.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
}
