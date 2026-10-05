package io.github.jaehun6912.remoteaccesshub.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import io.github.jaehun6912.remoteaccesshub.DebugTools
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.router.DiagnosticsExporter

/** 화면 맨 위 틀. 맨 아래 층에 공유기 화면이 늘 붙어 있고, 그 위를 메인 화면·설정 화면이 덮는다. */
@Composable
fun AppRoot(c: AppController) {
    AppTheme(c.settings.theme) {
        val p = LocalPalette.current
        BackHandler(enabled = true) { c.onBack() }
        Box(
            Modifier
                .fillMaxSize()
                .background(p.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // 1) 공유기 화면 층: 숨겨도 떼어 내지 않는다(로그인 세션·자동 조작 유지).
            Column(Modifier.fillMaxSize()) {
                if (c.routerVisible) RouterBar(c)
                key("router-frame") {
                    AndroidView(
                        factory = { c.browser.frame.also { (it.parent as? ViewGroup)?.removeView(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
            // 2) 메인 화면: 공유기 화면을 숨길 때 위를 덮는다. 아래 공유기 화면으로 터치가 새지 않게 막는다.
            if (!c.routerVisible) {
                MainScreen(
                    c,
                    Modifier
                        .fillMaxSize()
                        .background(p.background)
                        .blockTouchesBelow(),
                )
            }
            // 3) 설정·시작 설정은 모두 덮는다.
            when (c.screen) {
                Screen.Settings -> SettingsScreen(c, Modifier.fillMaxSize().background(p.background).blockTouchesBelow())
                Screen.Setup -> SetupScreen(c, Modifier.fillMaxSize().background(p.background).blockTouchesBelow())
                Screen.Main -> Unit
            }
        }
        c.modeSheet?.let { ModeDialog(it, onChoose = { m -> c.chooseMode(m) }, onDismiss = { c.dismissModeSheet() }) }
        c.dialog?.let { AppDialogView(it) }
    }
}

/**
 * 이 층이 차지한 곳의 터치를 아래 층(공유기 화면)으로 넘기지 않는다.
 * 겹친 형제 중 위층이 터치를 받으면 아래층에는 전달되지 않으므로 받기만 하고 소비(consume)하지 않는다.
 * 소비하면 스크롤이 "다른 곳에서 터치를 썼다"고 보고 스크롤을 취소한다(1.0.1까지 설정 화면 스크롤이 잘 안 되던 원인).
 */
fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Final)
    }
}

@Composable
private fun RouterBar(c: AppController) {
    val p = LocalPalette.current
    Surface(color = p.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Router, null, tint = p.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.routerTitle, fontWeight = FontWeight.Bold, color = p.text)
                    Text(
                        if (c.browser.session.isLoggedIn) "닫아도 로그인은 유지됩니다." else "아이디·비밀번호·보안문자는 직접 입력합니다. 앱은 저장하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.subText,
                    )
                }
                TextButton(onClick = { c.showRouter(false) }) {
                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("닫기")
                }
            }
            if (c.bannerText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.bannerText, style = MaterialTheme.typography.bodySmall, color = bannerColor(c.bannerKind, p), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MainScreen(c: AppController, modifier: Modifier) {
    val p = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    val diagLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) c.exportDiagnostics(uri)
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        // --- 머리글: 제목 · 설정/더보기 · 종료
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RemoteAccessHub" + if (c.options.mock) " [모의]" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = p.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { c.openSettings() }, enabled = !c.busy && !c.exiting) { Icon(Icons.Filled.Settings, "설정") }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !c.exiting) { Icon(Icons.Filled.MoreVert, "더 보기") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("공유기 화면 다시 열기") }, enabled = !c.busy, onClick = { menuOpen = false; c.reopenRouter() })
                    DropdownMenuItem(text = { Text("현재 화면에서 PC 켜기") }, enabled = c.gate.wake, onClick = { menuOpen = false; c.startWake(skipNavigation = true) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("시작 설정 다시 하기") }, enabled = !c.busy, onClick = { menuOpen = false; c.openSetup() })
                    DropdownMenuItem(text = { Text("진단 내보내기") }, onClick = { menuOpen = false; diagLauncher.launch(DiagnosticsExporter.defaultFileName()) })
                    HorizontalDivider()
                    for ((label, value) in listOf("휴대폰 설정 따르기" to "system", "어둡게" to "dark", "밝게" to "light")) {
                        DropdownMenuItem(
                            text = { Text("테마: $label") },
                            trailingIcon = { if (c.settings.theme == value) Icon(Icons.Filled.Check, null) },
                            onClick = { menuOpen = false; c.setTheme(value) },
                        )
                    }
                    if (DebugTools.AVAILABLE) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (c.options.mock) "[디버그] 실제 공유기로 다시 열기" else "[디버그] 모의 공유기로 다시 열기") },
                            onClick = { menuOpen = false; c.restartInMockMode(!c.options.mock) },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { c.exitWithLogout() }, enabled = !c.exiting, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(if (c.exiting) "종료 중" else "종료")
            }
        }

        // --- 상태 배지
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillView(c.routerPill, AppIcons.Router)
            c.powerPill?.let { PillView(it, AppIcons.Power, onClick = { c.checkPowerNow() }, description = "${it.text}. 눌러서 지금 확인") }
        }
        Spacer(Modifier.height(12.dp))

        // --- 단계 표시
        StepsCard(c.steps)
        Spacer(Modifier.height(12.dp))

        // --- 안내 줄
        StatusBanner(c.bannerText, c.bannerKind)
        Spacer(Modifier.height(14.dp))

        // --- 동작 버튼
        Button(
            onClick = { c.showModeSheet(wakeFirst = true) },
            enabled = c.gate.wakeConnect,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("PC 켜고 접속", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { c.startWake(skipNavigation = false) },
                enabled = c.gate.wake,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(AppIcons.Power, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("PC 켜기")
            }
            ConnectButton(c, Modifier.weight(1f).height(48.dp))
        }
        if (c.busy && !c.exiting) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { c.cancelOperation() },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.danger, contentColor = if (p.isDark) Color(0xFF1A0505) else Color.White),
            ) {
                Icon(Icons.Filled.Close, null)
                Spacer(Modifier.width(6.dp))
                Text("취소")
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            TextButton(onClick = { c.toggleRouter() }) {
                Icon(AppIcons.Router, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("공유기 화면")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { c.showLog(!c.logVisible) }) {
                Icon(AppIcons.Log, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (c.logVisible) "기록 숨기기" else "기록")
            }
        }
        if (c.logVisible) {
            LogPanel(c.logLines)
            TextButton(onClick = { c.shareLog() }, modifier = Modifier.align(Alignment.End)) { Text("기록 공유(주소·MAC 가림)") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** [PC 접속]: PC가 켜진 것이 확인되면 서서히 밝아졌다 어두워진다(Windows 버전과 같은 2.2초 주기). */
@Composable
private fun ConnectButton(c: AppController, modifier: Modifier) {
    val p = LocalPalette.current
    val level = if (c.blinkConnect) {
        val t = rememberInfiniteTransition(label = "blink")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "level")
        v
    } else {
        0f
    }
    OutlinedButton(
        onClick = { c.showModeSheet(wakeFirst = false) },
        enabled = c.gate.connect,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, lerp(p.border, p.accent, level)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = p.accent.copy(alpha = 0.28f * level)),
    ) {
        Icon(AppIcons.Monitor, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("PC 접속")
    }
}

@Composable
private fun PillView(pill: Pill, icon: ImageVector, onClick: (() -> Unit)? = null, description: String? = null) {
    val p = LocalPalette.current
    val color = when (pill.tone) {
        PillTone.Success -> p.success
        PillTone.Warning -> p.warning
        PillTone.Info -> p.info
        PillTone.Muted -> p.subText
    }
    Row(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { if (description != null) contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(pill.text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun stepColor(state: StepState, p: Palette): Color = when (state) {
    StepState.Pending -> p.muted
    StepState.Active -> p.info
    StepState.Done -> p.success
    StepState.Warning -> p.warning
    StepState.Failed -> p.danger
}

@Composable
private fun StepsCard(steps: List<StepInfo>) {
    val p = LocalPalette.current
    Surface(color = p.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.border)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            steps.forEachIndexed { i, s ->
                val color = stepColor(s.state, p)
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .border(2.dp, color, CircleShape)
                            .background(if (s.state == StepState.Done) color else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (s.state) {
                            StepState.Done -> Icon(Icons.Filled.Check, null, tint = p.surface, modifier = Modifier.size(18.dp))
                            StepState.Warning -> Text("!", color = color, fontWeight = FontWeight.Bold)
                            StepState.Failed -> Icon(Icons.Filled.Close, null, tint = color, modifier = Modifier.size(16.dp))
                            else -> Text("${i + 1}", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = p.text, fontWeight = FontWeight.SemiBold)
                        Text(s.detail, color = if (s.state == StepState.Pending) p.subText else color, style = MaterialTheme.typography.bodySmall)
                        s.progress?.let { pr ->
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { pr.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = color, trackColor = p.border)
                        }
                    }
                }
                if (i < steps.size - 1) HorizontalDivider(Modifier.padding(start = 54.dp, end = 14.dp), color = p.border.copy(alpha = 0.6f))
            }
        }
    }
}

private fun bannerColor(kind: BannerKind, p: Palette): Color = when (kind) {
    BannerKind.Info -> p.subText
    BannerKind.Progress -> p.info
    BannerKind.Success -> p.success
    BannerKind.Warning -> p.warning
    BannerKind.Error -> p.danger
}

@Composable
private fun StatusBanner(text: String, kind: BannerKind) {
    val p = LocalPalette.current
    val color = bannerColor(kind, p)
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            BannerKind.Progress -> CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
            BannerKind.Success -> Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(20.dp))
            BannerKind.Warning, BannerKind.Error -> Icon(Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(20.dp))
            BannerKind.Info -> Icon(Icons.Filled.Info, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (kind == BannerKind.Info) p.text else color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogPanel(lines: List<String>) {
    val p = LocalPalette.current
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(p.logBackground, RoundedCornerShape(8.dp))
            .border(1.dp, p.border, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        items(lines) { l -> Text(l, color = p.logText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) }
    }
}

/** 접속 방식 선택 창. 설정이 비어 있는 방식은 "설정 필요"로 표시하고 고를 수 없다. */
@Composable
private fun ModeDialog(sheet: ModeSheet, onChoose: (ConnectMode) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = p.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, p.border)) {
            Column(Modifier.padding(12.dp)) {
                Text(sheet.heading, color = p.subText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                sheet.options.forEachIndexed { i, o ->
                    val preferred = o.enabled && o.mode == sheet.preferred
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .border(if (preferred) 1.5.dp else 1.dp, if (preferred) p.accent else p.border, RoundedCornerShape(10.dp))
                            .clickable(enabled = o.enabled) { onChoose(o.mode) }
                            .semantics { contentDescription = "${i + 1}. ${o.title}, ${o.detail}${if (o.enabled) "" else ", 사용할 수 없음"}" }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp).border(1.dp, p.border, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = if (o.enabled) p.subText else p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            if (o.mode == ConnectMode.Crd) AppIcons.Monitor else AppIcons.Globe,
                            null,
                            tint = if (o.enabled) p.accent else p.muted,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.title, color = if (o.enabled) p.text else p.muted, fontWeight = FontWeight.Bold)
                            Text(o.detail, color = if (o.enabled) p.subText else p.warning, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun AppDialogView(d: AppDialog) {
    AlertDialog(
        onDismissRequest = { if (d.dismissText != null) d.onResult(false) },
        title = { Text(d.title) },
        text = { Text(d.message) },
        confirmButton = { TextButton(onClick = { d.onResult(true) }) { Text(d.confirmText) } },
        dismissButton = d.dismissText?.let { t -> { TextButton(onClick = { d.onResult(false) }) { Text(t) } } },
    )
}
