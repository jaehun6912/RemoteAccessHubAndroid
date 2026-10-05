package io.github.jaehun6912.remoteaccesshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import kotlinx.coroutines.launch

/** 시작 설정 입력값과 단계별 검사(화면과 분리해 단위 검사한다). Windows 버전 SetupWizardForm에서 VPN을 뺐다. */
class SetupDraft(base: AppSettings) {
    enum class Page { Welcome, Router, Pc, Connect, Done }

    var page by mutableStateOf(Page.Welcome)
    var routerUrl by mutableStateOf(base.routerUrl)
    var allowCert by mutableStateOf(base.allowRouterCertificateError)
    var pcName by mutableStateOf(base.wolPcName)
    var pcMac by mutableStateOf(base.wolPcMac)
    var useDirect by mutableStateOf(base.publicHost.isNotEmpty())
    var publicHost by mutableStateOf(base.publicHost)
    var publicPort by mutableStateOf(base.publicRdpPort.toString())
    var useCrd by mutableStateOf(base.useCrd)
    var crdHostId by mutableStateOf(base.crdHostId)
    private val work = base.clone()

    val isHttps: Boolean get() = routerUrl.trim().startsWith("https://", ignoreCase = true)

    fun validate(p: Page = page): String? = when (p) {
        Page.Router -> if (!InputRules.tryParseRouterUrl(routerUrl.trim())) {
            "주소 형식이 올바르지 않습니다. http:// 또는 https://로 시작하는 전체 주소를 입력하세요. 예: http://myhome.iptime.org:8080/"
        } else null
        Page.Pc -> when {
            pcName.isBlank() -> "공유기 WOL 목록에 등록된 PC 이름을 입력하세요."
            pcMac.trim().isNotEmpty() && InputRules.normalizeMac(pcMac.trim()) == null -> "MAC 주소 형식이 올바르지 않습니다. 예: 00:11:22:33:44:55 (모르면 비워 두세요)"
            else -> null
        }
        Page.Connect -> when {
            useDirect && !InputRules.isValidHost(publicHost.trim()) -> "일반 접속 주소를 확인하세요. DDNS 주소 또는 공인 IP만 입력합니다(포트 제외)."
            useDirect && publicPort.trim().toIntOrNull()?.let { InputRules.isValidPort(it) } != true -> "외부 포트는 1~65535 사이의 숫자여야 합니다."
            useCrd && crdHostId.trim().isNotEmpty() && InputRules.normalizeCrdHostId(crdHostId) == null ->
                "크롬 원격 데스크톱 기기 ID 형식이 올바르지 않습니다. 모르면 비워 두세요(연결할 때 기기 목록을 엽니다)."
            else -> null
        }
        else -> null
    }

    /** 다음 단계로. 입력이 틀리면 오류 문구를 돌려주고 머문다. 마지막 단계면 결과 설정을 돌려준다. */
    fun next(): Pair<String?, AppSettings?> {
        val error = validate()
        if (error != null) return error to null
        collect()
        if (page == Page.Done) {
            work.setupCompleted = true
            return null to work.clone()
        }
        page = Page.entries[page.ordinal + 1]
        // 보통 공유기와 같은 DDNS 주소를 쓴다
        if (page == Page.Connect && publicHost.isBlank()) work.routerUri?.let { publicHost = it.host.trim('[', ']') }
        return null to null
    }

    fun back() {
        if (page != Page.Welcome) page = Page.entries[page.ordinal - 1]
    }

    private fun collect() {
        when (page) {
            Page.Router -> {
                work.routerUrl = routerUrl.trim()
                work.allowRouterCertificateError = isHttps && allowCert
            }
            Page.Pc -> {
                work.wolPcName = pcName.trim()
                work.wolPcMac = InputRules.normalizeMac(pcMac.trim()) ?: ""
            }
            Page.Connect -> {
                work.publicHost = if (useDirect) publicHost.trim() else ""
                work.publicRdpPort = publicPort.trim().toIntOrNull() ?: 3389
                work.useCrd = useCrd
                work.crdHostId = if (useCrd) InputRules.normalizeCrdHostId(crdHostId) ?: "" else ""
                work.lastConnectMode = if (useDirect) "direct" else if (useCrd) "crd" else "direct"
            }
            else -> Unit
        }
    }

    fun summary(): List<Pair<String, String>> = listOf(
        "공유기 주소" to (work.routerUrl + if (work.allowRouterCertificateError) "  (인증서 오류 허용)" else ""),
        "켤 PC" to (work.wolPcName + if (work.wolPcMac.isNotEmpty()) "  ·  ${work.wolPcMac}" else ""),
        "일반 접속" to (if (work.publicHost.isNotEmpty()) InputRules.hostPort(work.publicHost, work.publicRdpPort) else "사용 안 함"),
        "크롬 원격 데스크톱" to when {
            !work.useCrd -> "사용 안 함"
            work.crdHostId.isNotEmpty() -> "저장된 기기로 바로 연결"
            else -> "기기 목록에서 고르기"
        },
    )

    companion object {
        val stepNames = listOf("시작", "공유기 주소", "켤 PC", "접속 방법", "확인")
    }
}

@Composable
fun SetupScreen(c: AppController, modifier: Modifier) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val d = remember { SetupDraft(c.settings) }
    var error by remember { mutableStateOf<String?>(null) }
    var checkText by remember { mutableStateOf("") }
    var checkColor by remember { mutableStateOf(Color.Unspecified) }
    var checking by remember { mutableStateOf(false) }
    val i = d.page.ordinal

    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("${i + 1} / ${SetupDraft.stepNames.size} · ${SetupDraft.stepNames[i]}", color = p.subText, style = MaterialTheme.typography.labelMedium)
        val (title, desc) = when (d.page) {
            SetupDraft.Page.Welcome -> "RemoteAccessHub 시작 설정" to "처음 한 번만 필요한 정보를 입력합니다."
            SetupDraft.Page.Router -> "공유기 관리자 주소" to "브라우저에서 ipTIME 관리 화면을 열 때 쓰는 주소를 입력하세요."
            SetupDraft.Page.Pc -> "켤 PC" to "공유기 [특수 기능 → WOL 기능] 목록에 등록된 PC를 지정합니다."
            SetupDraft.Page.Connect -> "접속 방법" to "쓰는 방법을 켜세요. 둘 다 켜면 접속할 때마다 고를 수 있습니다."
            SetupDraft.Page.Done -> "확인" to "아래 내용으로 저장합니다."
        }
        Text(title, color = p.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
        Text(desc, color = p.subText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(SetupDraft.stepNames.size) { k ->
                Box(Modifier.weight(1f).height(5.dp).background(if (k <= i) p.accent else p.border, RoundedCornerShape(50)))
            }
        }
        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (d.page) {
                SetupDraft.Page.Welcome -> {
                    SectionTitle("이 앱이 하는 일")
                    Para("① 앱 안에 ipTIME 공유기 관리자 화면을 열고, 로그인은 직접 합니다.\n② 공유기의 WOL 기능으로 집 PC를 켭니다.\n③ PC가 켜질 때까지 기다렸다가 원격 데스크톱 앱(또는 크롬 원격 데스크톱)으로 연결합니다.")
                    SectionTitle("준비할 것")
                    Para("• 집 밖에서 열 수 있는 공유기 관리자 주소 (DDNS 주소와 원격 관리 포트)\n• 공유기 [특수 기능 → WOL 기능]에 등록한 PC 이름\n• 접속 방법: 공유기 포트포워딩 + 원격 데스크톱 앱(Microsoft 'Windows App' 등), 또는 크롬 원격 데스크톱")
                    SectionTitle("저장하지 않는 것")
                    Para("공유기 아이디·비밀번호·보안문자는 입력받지도, 저장하지도 않습니다. 모든 값은 나중에 ⚙ 설정에서 바꿀 수 있습니다.")
                }
                SetupDraft.Page.Router -> {
                    FieldRow(
                        "관리자 페이지 주소", d.routerUrl, { d.routerUrl = it; checkText = ""; error = null },
                        "http:// 또는 https://부터 원격 관리 포트까지 전체 주소. 집 밖에서 쓰려면 DDNS 주소를 쓰세요.",
                        placeholder = "http://myhome.iptime.org:8080/", keyboard = KeyboardType.Uri,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !checking,
                            onClick = {
                                val uri = InputRules.parseRouterUrl(d.routerUrl.trim())
                                if (uri == null) {
                                    checkText = "주소 형식부터 확인하세요."
                                    checkColor = p.warning
                                    return@OutlinedButton
                                }
                                checking = true
                                checkText = "확인 중..."
                                checkColor = p.subText
                                scope.launch {
                                    val open = c.isReachable(uri.host.trim('[', ']'), uri.effectivePort)
                                    checkText = if (open) "연결됨 · ${uri.host}:${uri.effectivePort}" else "응답 없음 · 주소와 포트, 공유기의 원격 관리 허용 설정을 확인하세요."
                                    checkColor = if (open) p.success else p.warning
                                    checking = false
                                }
                            },
                        ) { Text("연결 확인") }
                        Spacer(Modifier.width(10.dp))
                        Text(checkText, color = checkColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Para("[연결 확인]은 이 주소·포트로 연결되는지만 봅니다. 공유기 로그인 화면은 설정을 마친 뒤 열립니다.", sub = true)
                    CheckRow("이 공유기 주소에 한해 https 인증서 오류 허용 (자체 서명 인증서일 때만)", d.isHttps && d.allowCert, { d.allowCert = it }, enabled = d.isHttps)
                }
                SetupDraft.Page.Pc -> {
                    FieldRow("PC 이름", d.pcName, { d.pcName = it; error = null }, "공유기 WOL 목록에 보이는 이름과 똑같이 입력하세요.", placeholder = "예: MY-PC")
                    FieldRow("MAC 주소 (선택)", d.pcMac, { d.pcMac = it; error = null }, "같은 이름의 PC가 여러 대일 때만 필요합니다.", placeholder = "예: 00:11:22:33:44:55")
                    Para("앱은 이 이름과 (입력했다면) MAC이 정확히 맞는 행의 [PC 켜기]만 누릅니다. 맞는 행이 없거나 여러 개면 아무것도 누르지 않습니다.", sub = true)
                }
                SetupDraft.Page.Connect -> {
                    CheckRow("일반 접속 — 공유기 포트포워딩으로 원격 데스크톱 앱이 바로 연결", d.useDirect, { d.useDirect = it; error = null }, bold = true)
                    FieldRow("접속 주소", d.publicHost, { d.publicHost = it; error = null }, "DDNS 주소 또는 공인 IP (포트 제외)", placeholder = "예: myhome.iptime.org", enabled = d.useDirect, keyboard = KeyboardType.Uri)
                    FieldRow("외부 포트", d.publicPort, { d.publicPort = it; error = null }, "공유기 포트포워딩에서 원격 데스크톱으로 연결한 외부 포트", enabled = d.useDirect, keyboard = KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    CheckRow("크롬 원격 데스크톱 — 구글 계정으로 연결(포트포워딩 불필요)", d.useCrd, { d.useCrd = it; error = null }, bold = true)
                    FieldRow(
                        "기기 ID (선택)", d.crdHostId, { d.crdHostId = it; error = null },
                        "모르면 비워 두세요(연결할 때 기기 목록을 엽니다). 세션 주소 전체를 붙여 넣어도 됩니다.",
                        enabled = d.useCrd,
                    )
                    Para("크롬 원격 데스크톱은 대상 PC에 호스트가 설치돼 있어야 하고, 구글 로그인과 PIN 입력은 직접 합니다. 열어 둔 포트가 없어 PC가 켜졌는지는 확인하지 않습니다(⚙ 설정에서 바꿀 수 있음).", sub = true)
                    Para("모두 끄면 [PC 켜기]만 쓸 수 있고, 접속 방법은 나중에 ⚙ 설정에서 넣을 수 있습니다.", sub = true)
                }
                SetupDraft.Page.Done -> {
                    for ((k, v) in d.summary()) {
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Text(k, color = p.subText, modifier = Modifier.width(130.dp))
                            Text(v, color = p.text)
                        }
                    }
                    Para("[완료]를 누르면 저장하고 공유기 로그인 화면을 엽니다. 아이디·비밀번호·보안문자를 입력해 로그인하면 [PC 켜고 접속]을 누를 수 있습니다.", sub = true)
                }
            }
        }

        error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { c.skipSetup() }) { Text("나중에 설정") }
            Spacer(Modifier.weight(1f))
            if (d.page != SetupDraft.Page.Welcome) {
                OutlinedButton(onClick = { error = null; d.back() }) { Text("이전") }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = {
                val (e, result) = d.next()
                error = e
                if (result != null) c.completeSetup(result)
            }) {
                if (d.page == SetupDraft.Page.Done) {
                    Icon(Icons.Filled.Check, null)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    when (d.page) {
                        SetupDraft.Page.Welcome -> "시작"
                        SetupDraft.Page.Done -> "완료"
                        else -> "다음"
                    },
                )
            }
        }
    }
}
