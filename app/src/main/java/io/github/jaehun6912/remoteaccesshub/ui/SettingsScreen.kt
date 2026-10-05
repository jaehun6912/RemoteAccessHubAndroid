package io.github.jaehun6912.remoteaccesshub.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import kotlinx.coroutines.launch

/** 설정 화면에서 고치는 값(저장 전까지는 원래 설정을 바꾸지 않는다). */
class SettingsDraft(private val base: AppSettings) {
    var routerUrl by mutableStateOf(base.routerUrl)
    var wolPcName by mutableStateOf(base.wolPcName)
    var wolPcMac by mutableStateOf(base.wolPcMac)
    var allowCert by mutableStateOf(base.allowRouterCertificateError)
    var publicHost by mutableStateOf(base.publicHost)
    var publicPort by mutableStateOf(base.publicRdpPort.toString())
    var useCrd by mutableStateOf(base.useCrd)
    var crdHostId by mutableStateOf(base.crdHostId)
    var crdCheck by mutableStateOf(if (base.crdBootCheckMode.trim().equals("direct", true)) "direct" else "none")
    var crdOpenWith by mutableStateOf(if (base.crdOpenWith.trim().equals("app", true)) "app" else "auto")
    var powerCheck by mutableStateOf(if (base.powerCheckMode.trim().equals("off", true)) "off" else "auto")
    var powerSeconds by mutableStateOf(base.powerCheckSeconds.toString())
    var blink by mutableStateOf(base.blinkConnectWhenPcOn)
    var bootWait by mutableStateOf(base.bootWaitSeconds.toString())
    var theme by mutableStateOf(base.theme)
    var autoCollapse by mutableStateOf(base.autoCollapseAfterLogin)
    var autoConfirm by mutableStateOf(base.autoConfirmWakeDialog)
    var autoAdmin by mutableStateOf(base.autoSelectAdminTool)
    var wolRoute by mutableStateOf(base.wolPageRoute)
    var adminLabel by mutableStateOf(base.adminToolLabel)
    var wolGroup by mutableStateOf(base.wolMenuGroupLabel)
    var wolMenu by mutableStateOf(base.wolMenuLabel)
    var wakePattern by mutableStateOf(base.wakeButtonPattern)
    var probeInterval by mutableStateOf(base.sessionProbeIntervalSeconds.toString())
    var layoutWidth by mutableStateOf(base.automationLayoutWidth.toString())

    /** 입력값으로 새 설정을 만든다. 숫자 칸이 비었거나 숫자가 아니면 (null, 오류). */
    fun build(): Pair<AppSettings?, List<String>> {
        val errors = mutableListOf<String>()
        fun num(text: String, name: String): Int {
            val v = text.trim().toIntOrNull()
            if (v == null) errors.add("$name 칸에는 숫자를 입력하세요.")
            return v ?: 0
        }
        val s = base.copy(
            routerUrl = routerUrl.trim(),
            wolPcName = wolPcName.trim(),
            wolPcMac = InputRules.normalizeMac(wolPcMac.trim()) ?: wolPcMac.trim(),
            allowRouterCertificateError = allowCert && routerUrl.trim().startsWith("https://", ignoreCase = true),
            publicHost = publicHost.trim(),
            publicRdpPort = num(publicPort, "일반 접속 포트"),
            useCrd = useCrd,
            crdHostId = InputRules.normalizeCrdHostId(crdHostId) ?: crdHostId.trim(),
            crdBootCheckMode = crdCheck,
            crdOpenWith = crdOpenWith,
            powerCheckMode = powerCheck,
            powerCheckSeconds = num(powerSeconds, "PC 전원 확인 주기"),
            blinkConnectWhenPcOn = blink,
            bootWaitSeconds = num(bootWait, "부팅 대기 시간"),
            theme = theme,
            autoCollapseAfterLogin = autoCollapse,
            autoConfirmWakeDialog = autoConfirm,
            autoSelectAdminTool = autoAdmin,
            wolPageRoute = wolRoute.trim(),
            adminToolLabel = adminLabel.trim(),
            wolMenuGroupLabel = wolGroup.trim(),
            wolMenuLabel = wolMenu.trim(),
            wakeButtonPattern = wakePattern.trim(),
            sessionProbeIntervalSeconds = num(probeInterval, "세션 확인 주기"),
            automationLayoutWidth = num(layoutWidth, "자동 조작 화면 너비"),
        )
        if (errors.isNotEmpty()) return null to errors
        errors.addAll(s.validateRouter())
        if (s.publicHost.isNotEmpty() && !InputRules.isValidHost(s.publicHost)) errors.add("일반 접속 주소 형식이 올바르지 않습니다.")
        if (s.publicHost.isNotEmpty() && !InputRules.isValidPort(s.publicRdpPort)) errors.add("일반 접속 포트는 1~65535 사이여야 합니다.")
        if (s.bootWaitSeconds !in 10..3600) errors.add("부팅 대기 시간은 10~3600초 사이여야 합니다.")
        if (s.crdHostId.isNotEmpty() && InputRules.normalizeCrdHostId(s.crdHostId) == null) errors.add("크롬 원격 데스크톱 기기 ID 형식이 올바르지 않습니다(16진수와 - 만).")
        return (if (errors.isEmpty()) s else null) to errors
    }
}

@Composable
fun SettingsScreen(c: AppController, modifier: Modifier) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(SettingsDraft(c.settings)) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (s, errors) = draft.build()
        scope.launch {
            val error = c.exportSettings(uri, s ?: c.settings)
            message = if (error == null) {
                "설정 내보내기" to (if (s == null) "입력값에 오류가 있어 저장된 설정을 내보냈습니다:\n" + errors.joinToString("\n") else "설정 파일을 저장했습니다(비밀번호·보안문자는 원래 저장하지 않음).")
            } else {
                "설정 내보내기" to error
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (s, error) = c.importSettings(uri)
            if (s != null) {
                draft = SettingsDraft(s)
                message = "설정 가져오기" to "가져온 값을 화면에 채웠습니다. 확인한 뒤 [저장]을 누르세요.\n(Windows 버전의 VPN 항목은 안드로이드 버전에 없어 쓰지 않습니다.)"
            } else {
                message = "설정 가져오기" to (error ?: "가져오지 못했습니다.")
            }
        }
    }

    Column(modifier) {
        Surface(color = p.surface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("설정", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = p.text)
                    Text("비밀번호·보안문자는 저장하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = p.subText)
                }
                TextButton(onClick = { c.closeSettings() }) { Text("취소") }
                Button(onClick = {
                    val (s, errors) = draft.build()
                    if (s == null) {
                        message = "설정 확인" to errors.joinToString("\n")
                    } else {
                        c.applySettings(s, save = true)
                        c.closeSettings()
                    }
                }) {
                    Icon(Icons.Filled.Check, null)
                    Spacer(Modifier.width(4.dp))
                    Text("저장")
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val d = draft
            SectionTitle("공유기 (ipTIME 관리자 페이지)")
            FieldRow("관리자 페이지 전체 URL", d.routerUrl, { d.routerUrl = it }, "프로토콜·주소·관리 포트 포함. 예: http://myhome.iptime.org:8080/", keyboard = KeyboardType.Uri)
            FieldRow("WOL 대상 PC 이름", d.wolPcName, { d.wolPcName = it }, "공유기 WOL 목록에 등록된 이름과 정확히 같아야 합니다.")
            FieldRow("WOL 대상 MAC 주소 (선택)", d.wolPcMac, { d.wolPcMac = it }, "같은 이름이 여러 개일 때 구분용. 예: 00:11:22:33:44:55")
            CheckRow(
                "공유기 주소에 한해 인증서 오류 허용 (자체 서명 HTTPS일 때만 켜세요)",
                d.allowCert && d.routerUrl.trim().startsWith("https://", true),
                { d.allowCert = it },
                enabled = d.routerUrl.trim().startsWith("https://", true),
            )

            SectionTitle("일반 접속 (원격 데스크톱 앱으로 바로)")
            FieldRow("접속 주소", d.publicHost, { d.publicHost = it }, "DDNS 호스트 이름 또는 공인 IP (포트 제외)", keyboard = KeyboardType.Uri)
            FieldRow("외부 포트", d.publicPort, { d.publicPort = it }, "공유기 포트포워딩에서 원격 데스크톱으로 연결한 외부 포트", keyboard = KeyboardType.Number)
            Para("휴대폰에 Microsoft 'Windows App'(원격 데스크톱) 같은 원격 데스크톱 앱이 있어야 합니다. 자격 증명은 그 앱에서 입력합니다.", sub = true)

            SectionTitle("크롬 원격 데스크톱")
            CheckRow("접속 방식에 '크롬 원격 데스크톱' 넣기", d.useCrd, { d.useCrd = it })
            FieldRow(
                "기기 ID (선택)", d.crdHostId, { d.crdHostId = it },
                "remotedesktop.google.com/access에서 그 PC에 연결했을 때 주소의 session/ 뒤 부분. 주소 전체를 붙여 넣어도 됩니다. 비우면 기기 목록을 엽니다.",
                enabled = d.useCrd,
            )
            RadioGroup(
                "부팅 확인 방법",
                listOf("none" to "확인하지 않고 바로 열기", "direct" to "일반 접속 주소·포트가 응답하는지 확인"),
                d.crdCheck,
                { d.crdCheck = it },
                hint = "크롬 원격 데스크톱은 열어 둔 포트가 없어 PC가 켜졌는지 확인할 방법이 없습니다. 확인하려면 위의 일반 접속 설정을 빌려 씁니다.",
                enabled = d.useCrd,
            )
            RadioGroup(
                "여는 방법",
                listOf(
                    "auto" to "기기 ID가 있으면 브라우저(Chrome)로 그 PC를 바로 열기",
                    "app" to "항상 크롬 원격 데스크톱 앱으로 열기(기기 목록에서 고르기)",
                ),
                d.crdOpenWith,
                { d.crdOpenWith = it },
                hint = "크롬 원격 데스크톱 앱은 특정 PC로 바로 가는 주소를 받지 않아 첫 화면(기기 목록)만 엽니다. 바로 연결하려면 브라우저로 엽니다(구글 로그인·PIN은 브라우저에서 입력).",
                enabled = d.useCrd,
            )
            OutlinedButton(onClick = { c.openCrdDeviceList() }, enabled = d.useCrd) { Text("기기 목록 열기") }

            SectionTitle("PC 전원 상태 표시")
            RadioGroup(
                "확인 방법",
                listOf("auto" to "일반 접속 주소·포트로 확인", "off" to "확인하지 않음"),
                d.powerCheck,
                { d.powerCheck = it },
                hint = "윗줄 배지에 PC가 켜져 있는지 보여 줍니다. 응답이 없어도 꺼졌다고 단정하지 않습니다.",
            )
            FieldRow("확인 주기(초)", d.powerSeconds, { d.powerSeconds = it }, "15~3600. 배지를 눌러 언제든 바로 확인할 수 있습니다.", keyboard = KeyboardType.Number)
            CheckRow("PC가 켜져 있으면 [PC 접속] 버튼을 천천히 깜빡이기", d.blink, { d.blink = it })

            SectionTitle("동작과 화면")
            FieldRow("부팅 대기 시간(초)", d.bootWait, { d.bootWait = it }, "원격 데스크톱 포트 응답을 기다리는 최대 시간(10~3600)", keyboard = KeyboardType.Number)
            RadioGroup("테마", listOf("system" to "휴대폰 설정 따르기", "dark" to "어둡게", "light" to "밝게"), d.theme, { d.theme = it })
            CheckRow("로그인이 확인되면 공유기 화면 자동으로 닫기", d.autoCollapse, { d.autoCollapse = it })
            CheckRow("WOL 확인창(PC를 켜시겠습니까?)의 [확인] 자동으로 누르기", d.autoConfirm, { d.autoConfirm = it })
            CheckRow("로그인 직후 선택 화면에서 [관리도구] 자동 선택", d.autoAdmin, { d.autoAdmin = it })

            SectionTitle("고급 (공유기 화면 구조가 바뀐 경우에만 수정)")
            FieldRow("WOL 페이지 경로", d.wolRoute, { d.wolRoute = it }, "기본 /ui/wol (공유기 주소 뒤에 붙는 경로)")
            FieldRow("관리도구 항목 이름", d.adminLabel, { d.adminLabel = it }, "로그인 직후 선택 화면에서 누를 항목")
            FieldRow("WOL 메뉴 그룹 이름", d.wolGroup, { d.wolGroup = it }, "WOL 메뉴가 들어 있는 메뉴 그룹")
            FieldRow("WOL 메뉴 이름", d.wolMenu, { d.wolMenu = it }, "직접 이동이 안 될 때 누를 메뉴 이름")
            FieldRow("PC 켜기 버튼 패턴(정규식)", d.wakePattern, { d.wakePattern = it })
            FieldRow("세션 확인 주기(초)", d.probeInterval, { d.probeInterval = it }, "5~600", keyboard = KeyboardType.Number)
            FieldRow(
                "자동 조작 화면 너비(px)", d.layoutWidth, { d.layoutWidth = it },
                "공유기 화면을 숨긴 채 조작할 때 PC 화면처럼 넓게 그립니다(기본 1180). 0이면 휴대폰 너비 그대로.",
                keyboard = KeyboardType.Number,
            )

            SectionTitle("설정 파일")
            Para("Windows 버전에서 내보낸 설정 파일도 가져올 수 있습니다(VPN 항목은 쓰지 않음).", sub = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("RemoteAccessHub-설정.json") }) { Text("내보내기") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("가져오기") }
                Button(
                    onClick = { confirmReset = true },
                    colors = ButtonDefaults.buttonColors(containerColor = p.danger, contentColor = if (p.isDark) Color(0xFF1A0505) else Color.White),
                ) { Text("초기화") }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = p.border)
            Para("RemoteAccessHub 안드로이드 ${io.github.jaehun6912.remoteaccesshub.BuildConfig.VERSION_NAME} · 개인이 만든 비공식 도구이며 ipTIME(EFM Networks)과 관련이 없습니다.", sub = true)
            Spacer(Modifier.height(24.dp))
        }
    }

    message?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("확인") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("설정 초기화") },
            text = { Text("모든 설정을 처음 상태로 되돌리고 시작 설정을 다시 엽니다. 기록은 그대로 둡니다.\n\n초기화할까요?") },
            confirmButton = { TextButton(onClick = { confirmReset = false; c.resetSettings(showSetup = true) }) { Text("초기화") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("취소") } },
        )
    }
}
