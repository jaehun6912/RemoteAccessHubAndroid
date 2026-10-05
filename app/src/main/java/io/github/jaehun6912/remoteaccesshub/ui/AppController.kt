package io.github.jaehun6912.remoteaccesshub.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jaehun6912.remoteaccesshub.BuildConfig
import io.github.jaehun6912.remoteaccesshub.DebugTools
import io.github.jaehun6912.remoteaccesshub.MockRouterHandle
import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.LogLevel
import io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException
import io.github.jaehun6912.remoteaccesshub.core.RouterPageKind
import io.github.jaehun6912.remoteaccesshub.core.SessionProbeResult
import io.github.jaehun6912.remoteaccesshub.core.SessionState
import io.github.jaehun6912.remoteaccesshub.router.ApiCall
import io.github.jaehun6912.remoteaccesshub.router.DiagnosticsExporter
import io.github.jaehun6912.remoteaccesshub.router.LogoutResult
import io.github.jaehun6912.remoteaccesshub.router.NavResult
import io.github.jaehun6912.remoteaccesshub.router.NavStatus
import io.github.jaehun6912.remoteaccesshub.router.RouterBrowser
import io.github.jaehun6912.remoteaccesshub.router.SessionProbeDetail
import io.github.jaehun6912.remoteaccesshub.router.StageStatus
import io.github.jaehun6912.remoteaccesshub.router.WolAutomation
import io.github.jaehun6912.remoteaccesshub.router.WolOutcome
import io.github.jaehun6912.remoteaccesshub.router.WolStep
import io.github.jaehun6912.remoteaccesshub.router.refreshSession
import io.github.jaehun6912.remoteaccesshub.services.AndroidCrdLauncher
import io.github.jaehun6912.remoteaccesshub.services.AndroidRdpLauncher
import io.github.jaehun6912.remoteaccesshub.services.ConnectOutcome
import io.github.jaehun6912.remoteaccesshub.services.ConnectProgress
import io.github.jaehun6912.remoteaccesshub.services.ConnectStage
import io.github.jaehun6912.remoteaccesshub.services.ConnectWorkflow
import io.github.jaehun6912.remoteaccesshub.services.CrdLauncher
import io.github.jaehun6912.remoteaccesshub.services.PcPowerState
import io.github.jaehun6912.remoteaccesshub.services.PcPowerStatus
import io.github.jaehun6912.remoteaccesshub.services.PortProbe
import io.github.jaehun6912.remoteaccesshub.services.PowerWatcher
import io.github.jaehun6912.remoteaccesshub.services.RdpLauncher
import io.github.jaehun6912.remoteaccesshub.services.TcpPortProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime

enum class BannerKind { Info, Progress, Success, Warning, Error }

enum class PillTone { Success, Warning, Info, Muted }

data class Pill(val text: String, val tone: PillTone)

enum class Screen { Main, Settings, Setup }

/** 접속 방식 선택 창 상태. */
data class ModeSheet(val heading: String, val options: List<ModeOption>, val preferred: ConnectMode, val wakeFirst: Boolean)

/** 앱 대화상자(공유기 페이지 대화상자, 종료 확인 등). */
data class AppDialog(
    val title: String,
    val message: String,
    val confirmText: String,
    val dismissText: String?,
    val onResult: (Boolean) -> Unit,
)

/** 실행 옵션. mock=true면 디버그 빌드의 모의 공유기로 실행한다(Windows의 --mock). */
data class LaunchOptions(val mock: Boolean = false, val selfTest: Boolean = false)

/**
 * 앱 동작 제어(Windows 버전 UI/MainForm.cs의 동작 부분).
 * 상태 표시와 사용자 입력은 Compose 화면이 맡고, 자동화 동작(세션 판정·WOL·접속)은 router/services 계층에 있다.
 * 공유기 화면(WebView)은 화면 맨 아래 층에 늘 붙어 있고, 숨길 때는 다른 화면이 덮을 뿐 버리지 않는다(로그인 세션 유지).
 */
class AppController(
    activity: Activity,
    val options: LaunchOptions,
    portProbe: PortProbe? = null,
    rdpLauncher: RdpLauncher? = null,
    crdLauncher: CrdLauncher? = null,
) {
    /** 지금 붙어 있는 화면. 시스템이 화면을 다시 만들면 [attach]로 바꾼다. */
    private var activity: Activity = activity
    private val appContext: Context = activity.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val log = AppLog(File(appContext.filesDir, "logs"))
    private val settingsFile = File(appContext.filesDir, if (options.selfTest) "settings-selftest.json" else if (options.mock) "settings-mock.json" else "settings.json")

    private val probe: PortProbe = portProbe ?: TcpPortProbe()
    private val rdp: RdpLauncher = rdpLauncher ?: AndroidRdpLauncher(appContext, log)
    private val crd: CrdLauncher = crdLauncher ?: AndroidCrdLauncher(appContext, log)

    var settings: AppSettings by mutableStateOf(if (options.selfTest) AppSettings() else AppSettings.load(settingsFile, log))
        private set

    val browser = RouterBrowser(activity, log) { settings }
    val wol = WolAutomation(browser, log) { settings }
    private val connect = ConnectWorkflow(probe, rdp, crd, log)
    val flow = FlowTracker()
    private val power = PowerWatcher(probe, log, scope, { settings }, { browser.session.isLoggedIn }, { busy || exiting })
    private var mock: MockRouterHandle? = null

    /** 자체검사용: 모의 공유기(디버그 빌드). */
    val mockRouter: MockRouterHandle? get() = mock

    // ------------------------------------------------------------------ 화면 상태(Compose가 읽음)

    var steps by mutableStateOf(flow.steps); private set
    var bannerText by mutableStateOf("시작하는 중..."); private set
    var bannerKind by mutableStateOf(BannerKind.Progress); private set
    var routerPill by mutableStateOf(Pill("공유기 확인 전", PillTone.Muted)); private set
    var powerPill by mutableStateOf<Pill?>(null); private set
    var gate by mutableStateOf(ActionGate.Gate(false, false, false)); private set
    var busy by mutableStateOf(false); private set
    var exiting by mutableStateOf(false); private set
    var routerVisible by mutableStateOf(false); private set
    var logVisible by mutableStateOf(false); private set
    var blinkConnect by mutableStateOf(false); private set
    var preparingAdmin by mutableStateOf(false); private set
    var modeSheet by mutableStateOf<ModeSheet?>(null); private set
    var dialog by mutableStateOf<AppDialog?>(null); private set
    var screen by mutableStateOf(Screen.Main); private set
    val logLines = mutableStateListOf<String>()

    val routerTitle: String get() = if (browser.session.isLoggedIn) "공유기 관리 화면" else "공유기 로그인"
    val powerStatus: PcPowerStatus get() = power.status

    /** 자체검사용: 페이지 대화상자를 묻지 않고 수락한다. */
    var scriptDialogAutoAccept = options.selfTest
    var scriptDialogCount = 0; private set
    var attentionCount = 0; private set
    var lastSettle: NavResult? = null; private set
    val initialized = CompletableDeferred<Boolean>()

    private var opSignal: CancelSignal? = null
    private var settleSignal: CancelSignal? = null
    private var settleJob: Job? = null
    private var wakeRunning = false
    private var initDone = false
    private var exitApproved = false
    private var lastSessionProbe = 0L

    init {
        logVisible = settings.showLog
        log.onAppended { e ->
            if (e.level == LogLevel.Debug) return@onAppended
            scope.launch {
                logLines.add(e.format())
                if (logLines.size > 600) logLines.removeRange(0, logLines.size - 500)
            }
        }
        flow.changed = { steps = flow.steps }
        power.changed = { refreshPills() }
        refreshUi()
    }

    private fun setStatus(text: String, kind: BannerKind = BannerKind.Info) {
        bannerText = text
        bannerKind = kind
    }

    /** 사용자 조작이 필요할 때: 짧게 진동해 알린다. */
    private fun notifyAttention() {
        attentionCount++
        if (!options.selfTest) {
            try {
                activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (_: Exception) {
            }
        }
    }

    fun refreshUi() {
        val loggedIn = browser.session.isLoggedIn
        gate = ActionGate.compute(loggedIn, busy, exiting, preparingAdmin)
        steps = flow.steps
        refreshPills()
    }

    private fun refreshPills() {
        routerPill = when {
            browser.session.state == SessionState.LoggedIn && flow.login.state == StepState.Warning -> Pill("공유기 · 관리도구 선택 필요", PillTone.Warning)
            browser.session.state == SessionState.LoggedIn && preparingAdmin -> Pill("공유기 · 관리 화면 준비 중", PillTone.Info)
            browser.session.state == SessionState.LoggedIn -> Pill("공유기 로그인됨", PillTone.Success)
            browser.session.state == SessionState.LoggedOut -> Pill("공유기 로그인 필요", PillTone.Warning)
            else -> Pill("공유기 확인 전", PillTone.Muted)
        }
        val p = power.status
        powerPill = if (p.state == PcPowerState.Disabled) null else Pill(
            p.pillText,
            when (p.state) {
                PcPowerState.On -> PillTone.Success
                PcPowerState.Checking -> PillTone.Info
                else -> PillTone.Muted
            },
        )
        blinkConnect = ActionGate.shouldBlinkConnect(settings.blinkConnectWhenPcOn, p.state, gate.connect, busy, exiting)
    }

    // ================================================================== 초기화

    fun start() {
        scope.launch { initialize() }
        scope.launch {
            while (isActive) {
                delay(1000)
                onUiTick()
            }
        }
        // 공유기 화면에서 로그인하는 동안에는 공유기 API 기록을 자주 읽어 로그인 요청을 바로 알아챈다(1초 주기보다 빠르게).
        scope.launch {
            while (isActive) {
                delay(300)
                if (initDone && browser.isReady && routerVisible && !browser.session.isLoggedIn && !busy) browser.network.sync()
            }
        }
    }

    private suspend fun initialize() {
        try {
            if (options.mock || options.selfTest) {
                val m = DebugTools.startMockRouter(appContext, log)
                if (m == null) {
                    setStatus("모의 공유기는 디버그 빌드에서만 쓸 수 있습니다.", BannerKind.Error)
                } else {
                    mock = m
                    settings = settings.copy(
                        routerUrl = m.baseUrl + "ui/",
                        publicHost = "127.0.0.1",
                        publicRdpPort = m.fakeRdpPort,
                        bootWaitSeconds = 30,
                        wolPcName = settings.wolPcName.ifEmpty { MockRouterHandle.DEFAULT_TARGET_NAME },
                        setupCompleted = true,
                    )
                }
            }

            setStatus("내장 브라우저를 준비하는 중...", BannerKind.Progress)
            applyRouterLayout()
            browser.initialize()
            browser.scriptDialogHandler = ::onScriptDialog
            browser.session.onStateChanged { o, n, r -> onSessionStateChanged(o, n, r) }
            browser.network.callCompleted = { c -> onApiCall(c) }
            browser.navigationFinished = { ok -> if (ok) scope.launch { probeSessionNow() } }
            browser.urlChanged = { refreshUi() }
            wol.stageChanged = { s, st, m -> onWolStage(s, st, m) }
            wol.dialogAppeared = { showRouter(true); notifyAttention() }
            wol.userActionNeeded = { showRouter(true); notifyAttention() }

            initDone = true
            initialized.complete(true)

            val errors = settings.validateRouter()
            when {
                shouldRunSetup(settings, options) -> {
                    // 첫 실행: 시작 설정으로 꼭 필요한 값을 받는다(마치면 공유기 화면이 열림)
                    screen = Screen.Setup
                    setStatus("시작 설정에서 공유기 주소와 켤 PC를 입력하세요.", BannerKind.Info)
                }
                errors.isNotEmpty() -> {
                    setStatus("설정(⚙)에서 공유기 주소와 WOL 대상 PC 이름을 먼저 입력하세요.", BannerKind.Warning)
                    log.warn(errors.joinToString(" / "))
                    if (!options.selfTest) screen = Screen.Settings
                }
                else -> navigateToRouter(expand = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            initialized.complete(false)
            setStatus("내장 브라우저를 시작하지 못했습니다: ${e.message}\nAndroid System WebView가 설치·최신 상태인지 확인하세요.", BannerKind.Error)
        }
    }

    private suspend fun onUiTick() {
        flow.tick(OffsetDateTime.now())
        if (!initDone || !browser.isReady) return
        // 공유기 API 호출 기록을 읽어 로그인·로그아웃 요청을 빨리 알아챈다(Windows는 즉시 알림을 받는다).
        browser.network.sync()
        if (!options.selfTest) power.tick()
        val interval = settings.sessionProbeIntervalSeconds.coerceIn(5, 600) * 1000L
        if (!busy && System.currentTimeMillis() - lastSessionProbe >= interval) probeSessionNow()
        if (!browser.isReady && browser.lastInitError != null) setStatus(browser.lastInitError!!, BannerKind.Error)
    }

    private fun onScriptDialog(kind: String, message: String, answer: (Boolean) -> Unit) {
        scriptDialogCount++
        if (scriptDialogAutoAccept) {
            answer(true)
            return
        }
        showRouter(true)
        notifyAttention()
        dialog = if (kind == "Alert") {
            AppDialog("공유기 페이지 알림", message, "확인", null) { dialog = null; answer(true) }
        } else {
            AppDialog("공유기 페이지 확인", "공유기 페이지가 확인을 요청합니다:\n\n$message", "예", "아니요") { ok -> dialog = null; answer(ok) }
        }
    }

    // ================================================================== 세션

    suspend fun probeSessionNow(): SessionProbeDetail {
        lastSessionProbe = System.currentTimeMillis()
        if (!browser.isReady) return SessionProbeDetail(SessionProbeResult.Unavailable, "브라우저 준비 안 됨", 0, null)
        val d = try {
            browser.refreshSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val x = SessionProbeDetail(SessionProbeResult.Unavailable, e.message ?: "오류", 0, null)
            browser.session.apply(x.result, x.reason)
            x
        }
        if (d.result == SessionProbeResult.Unavailable) {
            log.debug("세션 확인 불가(상태 유지): ${d.reason}")
        } else if (browser.session.state == SessionState.LoggedIn) {
            flow.onSession(SessionState.LoggedIn, browser.session.lastConfirmedAt)
        }
        refreshUi()
        return d
    }

    private fun onSessionStateChanged(oldState: SessionState, newState: SessionState, reason: String) {
        flow.onSession(newState, browser.session.lastConfirmedAt)
        refreshUi()
        if (exiting) return // [종료] 중의 로그아웃은 의도한 것이므로 만료 경고·화면 펼침·알림을 하지 않는다.
        if (newState == SessionState.LoggedIn && oldState == SessionState.LoggedOut && !busy) {
            // 새로 로그인하면 이전 실행의 PC 켜기·부팅·접속 결과를 지운다(지난 결과가 "완료"로 남아 헷갈리지 않도록).
            flow.resetForNewSession()
        }
        if (newState == SessionState.LoggedIn) {
            log.info("공유기 로그인 확인됨 → [PC 켜기] 활성화 ($reason)")
            setStatus("공유기 로그인 확인. 관리 화면을 준비하는 중...", BannerKind.Progress)
            if (!busy) {
                // 로그인이 확인되면 곧바로 공유기 화면을 닫는다. 로그인 직후의 [관리도구] 선택은 닫힌 채로 처리하고,
                // 자동으로 넘기지 못하면 settleAfterLogin이 다시 띄워 사용자에게 맡긴다.
                // [관리도구] 자동 선택을 끈 경우에는 사용자가 눌러야 하므로 관리 화면이 확인된 뒤에 닫는다.
                if (settings.autoCollapseAfterLogin && settings.autoSelectAdminTool) showRouter(false)
                settleJob = scope.launch { settleAfterLogin() }
            }
        } else if (newState == SessionState.LoggedOut) {
            settleSignal?.cancel()
            if (oldState == SessionState.LoggedIn) {
                log.warn("공유기 세션 만료/로그아웃 감지 → [PC 켜기] 비활성화 ($reason)")
                setStatus("공유기 세션이 만료되었습니다. 공유기 화면에서 다시 로그인하세요.", BannerKind.Warning)
                showRouter(true)
                notifyAttention()
            } else {
                setStatus("공유기 화면에서 아이디·비밀번호·보안문자를 입력해 로그인하세요. 로그인이 확인되면 공유기 화면은 저절로 닫힙니다.", BannerKind.Info)
            }
        }
    }

    /**
     * 로그인 확인 후 관리 화면 준비:
     * 선택 화면이면 설정에 따라 [관리도구]를 누르고, 관리 화면이 확인되면 공유기 화면을 닫는다.
     * 자동으로 넘기지 못하면 공유기 화면을 띄운 채 사용자에게 안내한다.
     */
    suspend fun settleAfterLogin(): NavResult? {
        settleSignal?.cancel()
        lastSettle = null
        val sig = CancelSignal().withTimeout(3 * 60_000)
        settleSignal = sig
        val settleStart = io.github.jaehun6912.remoteaccesshub.core.Mono.now()
        // "관리 화면이 준비됐습니다"가 뜨기 전에는 동작 버튼을 막는다([관리도구] 자동 선택과 겹치지 않도록).
        setAdminPreparing(true)
        try {
            var nav = wol.navigator.ensureAdminTool(settings.autoSelectAdminTool, sig)
            if (nav.status == NavStatus.NeedUserSelect) {
                showRouter(true)
                flow.onAdminSelectNeeded(true)
                refreshUi()
                setStatus(nav.message, BannerKind.Warning)
                notifyAttention()
                log.info("관리 화면 대기: ${nav.message}")
                while (true) {
                    sig.delay(1000)
                    val (kind, _) = wol.navigator.classify(sig)
                    if (kind == RouterPageKind.AdminMain || kind == RouterPageKind.WolList) {
                        nav = NavResult(NavStatus.Ok, "관리 화면 표시됨", kind, "사용자")
                        break
                    }
                }
            }
            flow.onAdminSelectNeeded(false)
            if (nav.status == NavStatus.Ok) {
                setAdminPreparing(false)
                setStatus("공유기 관리 화면이 준비됐습니다. [PC 켜고 접속]을 누르면 끝까지 진행합니다.", BannerKind.Success)
                if (settings.autoCollapseAfterLogin && routerVisible && !busy) showRouter(false)
            } else if (nav.status == NavStatus.Failed) {
                if (!busy) showRouter(true)
                setStatus("로그인은 확인됐지만 관리 화면을 확인하지 못했습니다. 필요하면 공유기 화면에서 [관리도구]를 누르세요.", BannerKind.Warning)
                log.warn("관리 화면 확인 실패: ${nav.message}")
            }
            log.info("관리 화면 준비 끝: ${nav.status}, ${"%.1f".format((io.github.jaehun6912.remoteaccesshub.core.Mono.now() - settleStart) / 1000.0)}초")
            refreshUi()
            lastSettle = nav
            return nav
        } catch (_: OperationCanceledException) {
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("로그인 후 화면 준비 오류: ${e.message}")
            if (!busy && !exiting && browser.session.isLoggedIn) {
                showRouter(true)
                setStatus("로그인은 확인됐지만 관리 화면을 준비하지 못했습니다. 필요하면 공유기 화면에서 [관리도구]를 누르세요.", BannerKind.Warning)
            }
            return null
        } finally {
            if (settleSignal === sig) {
                settleSignal = null
                // 실패·시간 초과·취소로 끝나도 버튼이 계속 막혀 있지 않게 푼다(새 준비 작업이 시작된 경우는 그쪽이 관리).
                setAdminPreparing(false)
            }
        }
    }

    private fun setAdminPreparing(preparing: Boolean) {
        if (preparingAdmin == preparing) return
        preparingAdmin = preparing
        flow.onAdminPreparing(preparing)
        refreshUi()
    }

    private fun onApiCall(c: ApiCall) {
        if (c.method.equals("session/info", ignoreCase = true)) {
            log.debug("공유기 API $c") // 주기적으로 반복되므로 화면 기록에는 표시하지 않음
        } else if (c.method.startsWith("session/", ignoreCase = true) || c.method.startsWith("wol/", ignoreCase = true)) {
            log.info("공유기 API $c")
        }
        if (c.method == "session/login" || c.method == "session/logout") {
            scope.launch {
                delay(150)
                if (!busy) probeSessionNow()
            }
        }
    }

    // ================================================================== 공유기 화면

    suspend fun navigateToRouter(expand: Boolean) {
        if (!browser.isReady) return
        val uri = InputRules.parseRouterUrl(settings.routerUrl)
        if (uri == null) {
            setStatus("공유기 주소가 올바르지 않습니다. 설정(⚙)을 확인하세요.", BannerKind.Error)
            return
        }
        if (expand) showRouter(true)
        setStatus("공유기 관리자 페이지를 여는 중...", BannerKind.Progress)
        browser.navigate(uri.text, 30_000)
        delay(300)
        probeSessionNow()
        if (browser.session.state != SessionState.LoggedIn) {
            setStatus("공유기 화면에서 아이디·비밀번호·보안문자를 입력해 로그인하세요. 로그인이 확인되면 공유기 화면은 저절로 닫힙니다.", BannerKind.Info)
        }
        // 로그인 화면에서도 상태 판독을 위해 접근성 트리를 켜 둔다(입력은 사용자가 직접).
        scope.launch {
            try {
                browser.ensureSemantics(20_000)
            } catch (_: Exception) {
            }
        }
    }

    /** 공유기 화면을 띄우거나(true) 숨긴다(false). 숨겨도 화면 맨 아래 층에 그대로 두어 로그인 세션과 조작 기능을 유지한다. */
    fun showRouter(visible: Boolean) {
        if (routerVisible == visible) return
        routerVisible = visible
        applyRouterLayout()
        if (!visible) {
            // 숨길 때 입력 초점이 공유기 화면에 남아 있으면 보이지 않는 페이지로 키 입력이 가므로 거둔다.
            browser.webView.clearFocus()
            hideKeyboard()
        }
        log.debug(if (visible) "공유기 화면 띄움" else "공유기 화면 숨김(세션·브라우저 상태 유지)")
        refreshUi()
    }

    fun toggleRouter() = showRouter(!routerVisible)

    /** 보여 줄 때는 휴대폰 너비 그대로, 숨긴 채 자동으로 조작할 때는 PC 화면 너비로 그린다(WideWebFrame 설명 참고). */
    private fun applyRouterLayout() {
        browser.frame.cssWidth = if (routerVisible) 0 else settings.automationLayoutWidth
    }

    private fun hideKeyboard() {
        try {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(browser.webView.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    fun reopenRouter() {
        if (busy) return
        scope.launch { navigateToRouter(expand = true) }
    }

    // ================================================================== 작업

    private fun beginOperation(): CancelSignal {
        busy = true
        val s = CancelSignal()
        opSignal = s
        refreshUi()
        return s
    }

    private fun endOperation() {
        busy = false
        opSignal = null
        refreshUi()
    }

    fun cancelOperation() {
        val s = opSignal ?: return
        if (!s.isCancelled) {
            log.warn("사용자가 작업 취소를 요청했습니다.")
            s.cancel()
        }
    }

    private fun onWolStage(step: WolStep, status: StageStatus, message: String) {
        // 로그인 직후 화면 준비(관리도구 선택)도 같은 이동 알림을 쓰므로,
        // PC 켜기 단계 표시는 실제로 PC 켜기를 실행 중일 때만 바꾼다.
        if (!wakeRunning) {
            if (step == WolStep.NavigateToWol && status == StageStatus.Running) setStatus(message, BannerKind.Progress)
            return
        }
        flow.onWolStage(step, status, message)
        val kind = when {
            status == StageStatus.Failed -> BannerKind.Error
            status == StageStatus.Unknown -> BannerKind.Warning
            message.contains("누르세요") -> BannerKind.Warning
            else -> BannerKind.Progress
        }
        setStatus("[${stepName(step)}] $message", kind)
    }

    private fun stepName(s: WolStep): String = when (s) {
        WolStep.SessionCheck -> "세션 확인"
        WolStep.NavigateToWol -> "WOL 화면"
        WolStep.Match -> "대상 찾기"
        WolStep.Click -> "버튼 클릭"
        WolStep.Confirm -> "확인창"
        WolStep.RouterResponse -> "공유기 응답"
    }

    /**
     * 접속 방식 선택 창을 연다([PC 켜고 접속]·[PC 접속]을 누를 때마다).
     * 방식마다 필요한 설정만 검사하며, 설정이 비어 있는 방식은 선택할 수 없게 표시한다.
     */
    fun showModeSheet(wakeFirst: Boolean) {
        if (busy || exiting) return
        if (wakeFirst && !browser.session.isLoggedIn) return
        if (preparingAdmin && browser.session.isLoggedIn) return
        modeSheet = ModeSheet(if (wakeFirst) "PC를 켠 뒤 접속할 방식" else "접속 방식", ModeOptions.forSettings(settings), settings.lastMode, wakeFirst)
    }

    fun dismissModeSheet() {
        modeSheet = null
    }

    /** 선택 창에서 고름. 사용할 수 없는 방식이면 false. */
    fun chooseMode(mode: ConnectMode): Boolean {
        val sheet = modeSheet ?: return false
        val o = sheet.options.firstOrNull { it.mode == mode }
        if (o == null || !o.enabled) return false
        modeSheet = null
        settings = settings.copy(lastConnectMode = if (mode == ConnectMode.Crd) "crd" else "direct")
        if (!options.selfTest) trySaveSettings()
        scope.launch { if (sheet.wakeFirst) runWakeAndConnect(mode) else runConnect(mode) }
        return true
    }

    fun startWake(skipNavigation: Boolean) {
        scope.launch { runWake(skipNavigation) }
    }

    suspend fun runWake(skipNavigation: Boolean): WolOutcome {
        if (busy) return WolOutcome.fail(WolStep.SessionCheck, "다른 작업이 진행 중입니다.")
        val errors = settings.validateRouter()
        if (errors.isNotEmpty()) {
            val msg = errors.joinToString(" ") + " 설정(⚙)에서 입력하세요."
            setStatus(msg, BannerKind.Error)
            return WolOutcome.fail(WolStep.SessionCheck, msg)
        }
        if (!browser.session.isLoggedIn) {
            val msg = "공유기 로그인이 확인되지 않았습니다. 공유기 화면에서 먼저 로그인하세요."
            setStatus(msg, BannerKind.Warning)
            showRouter(true)
            return WolOutcome.fail(WolStep.SessionCheck, msg)
        }

        // 로그인 후 화면 준비 작업과 동시에 공유기 화면을 조작하지 않도록 먼저 멈춘다.
        settleSignal?.cancel()
        val wakeStart = io.github.jaehun6912.remoteaccesshub.core.Mono.now()
        flow.onWolStarted()
        val sig = beginOperation()
        wakeRunning = true
        val outcome = try {
            wol.wake(skipNavigation, sig)
        } catch (_: OperationCanceledException) {
            WolOutcome.fail(WolStep.Click, "PC 켜기 작업이 취소되었습니다.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("PC 켜기 오류: $e")
            WolOutcome.fail(WolStep.Click, "오류: ${e.message}")
        } finally {
            wakeRunning = false
            endOperation()
        }

        flow.onWolOutcome(outcome, OffsetDateTime.now())
        log.info("PC 켜기 끝: ${if (outcome.success) "성공" else "실패"}, ${"%.1f".format((io.github.jaehun6912.remoteaccesshub.core.Mono.now() - wakeStart) / 1000.0)}초")
        if (outcome.success) {
            log.info("WOL 완료: ${outcome.message}")
            power.checkSoon(20_000) // 부팅할 시간을 조금 준 뒤 전원 배지를 갱신
            setStatus(
                if (outcome.routerStatus == StageStatus.Done) "공유기가 PC 켜기 요청을 처리했습니다. 부팅 여부는 [PC 접속]에서 원격 데스크톱 포트 응답으로 확인합니다." else outcome.message,
                if (outcome.routerStatus == StageStatus.Done) BannerKind.Success else BannerKind.Warning,
            )
            if (settings.autoCollapseAfterLogin && !options.selfTest) showRouter(false)
        } else {
            val cancelled = !outcome.requestAborted && outcome.message.contains("취소되었습니다")
            log.warn("WOL 실패(${stepName(outcome.lastStep)}): ${outcome.message}")
            setStatus(
                if (cancelled) outcome.message else outcome.message + " 공유기 화면을 띄워 두었으니 필요하면 직접 [PC 켜기]를 누르세요.",
                if (cancelled || outcome.requestAborted) BannerKind.Warning else BannerKind.Error,
            )
            if (!cancelled && outcome.lastStep in setOf(WolStep.Match, WolStep.NavigateToWol, WolStep.Confirm, WolStep.RouterResponse, WolStep.SessionCheck)) {
                showRouter(true)
            }
            if (!cancelled) notifyAttention()
        }
        refreshUi()
        return outcome
    }

    suspend fun runConnect(mode: ConnectMode): ConnectOutcome {
        if (busy) return ConnectOutcome(ConnectStage.Failed, false, "다른 작업이 진행 중입니다.", Duration.ZERO, false, mode)
        val errors = settings.validateConnect(mode)
        if (errors.isNotEmpty()) {
            val msg = errors.joinToString(" ") + " 설정(⚙)에서 입력하세요."
            setStatus(msg, BannerKind.Error)
            val failed = ConnectOutcome(ConnectStage.Failed, false, msg, Duration.ZERO, false, mode)
            flow.onConnectOutcome(failed, OffsetDateTime.now())
            return failed
        }

        flow.onConnectStarted(mode, settings.bootWaitSeconds)
        val sig = beginOperation()
        val progress: (ConnectProgress) -> Unit = { p ->
            flow.onConnectProgress(p, OffsetDateTime.now())
            setStatus("[PC 접속] ${p.message}", if (p.stage == ConnectStage.PortOpen) BannerKind.Success else BannerKind.Progress)
        }
        val outcome = try {
            connect.run(settings, mode, progress, sig)
        } finally {
            endOperation()
        }

        flow.onConnectOutcome(outcome, OffsetDateTime.now())
        power.checkSoon()
        if (outcome.success) {
            setStatus("[PC 접속] ${outcome.message}", BannerKind.Success)
        } else {
            setStatus("[PC 접속] ${outcome.message}", if (outcome.isCancelled) BannerKind.Warning else BannerKind.Error)
            if (!outcome.isCancelled) notifyAttention()
        }
        refreshUi()
        return outcome
    }

    /** [PC 켜고 접속]: 접속 설정을 먼저 확인 → PC 켜기 → 성공하면 이어서 접속. PC 켜기가 실패하면 접속하지 않는다. */
    suspend fun runWakeAndConnect(mode: ConnectMode): Pair<WolOutcome, ConnectOutcome?> {
        if (busy) return WolOutcome.fail(WolStep.SessionCheck, "다른 작업이 진행 중입니다.") to null
        val connectErrors = settings.validateConnect(mode)
        if (connectErrors.isNotEmpty()) {
            val msg = connectErrors.joinToString(" ") + " 설정(⚙)에서 입력하세요."
            setStatus(msg, BannerKind.Error)
            return WolOutcome.fail(WolStep.SessionCheck, msg) to null
        }

        val w = runWake(skipNavigation = false)
        if (!w.success) return w to null

        setStatus("PC 켜기 요청 완료. 이어서 부팅을 기다린 뒤 접속합니다.", BannerKind.Progress)
        val c = runConnect(mode)
        return w to c
    }

    /** 설정 화면의 [기기 목록 열기]: 크롬 원격 데스크톱 기기 목록을 연다. */
    fun openCrdDeviceList() {
        try {
            crd.open(null, settings.crdOpen)
        } catch (e: Exception) {
            log.warn("크롬 원격 데스크톱을 열지 못했습니다: ${e.message}")
        }
    }

    /** 시작 설정의 [연결 확인]: 주소·포트로 연결되는지만 본다. */
    suspend fun isReachable(host: String, port: Int): Boolean = try {
        probe.isOpen(host, port, 4000, CancelSignal.None)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    fun checkPowerNow() {
        scope.launch { power.checkNow() }
    }

    /** 자체검사용: 전원 상태를 지금 확인한다. */
    suspend fun checkPowerNowAsync() = power.checkNow()

    // ================================================================== 종료

    /**
     * [종료] 준비: 진행 중인 작업을 멈추고 공유기 관리 세션을 로그아웃한 뒤 결과를 돌려준다.
     * 화면은 닫지 않는다(자체검사에서도 쓰기 위해). 실패하면 exiting을 되돌려 계속 쓸 수 있게 한다.
     */
    suspend fun prepareExit(): LogoutResult {
        exiting = true
        modeSheet = null
        settleSignal?.cancel()
        refreshUi()
        if (busy) {
            cancelOperation()
            val deadline = System.currentTimeMillis() + 8000
            while (busy && System.currentTimeMillis() < deadline) delay(100)
        }

        setStatus("공유기 관리 세션을 로그아웃하는 중...", BannerKind.Progress)
        val result = try {
            browser.logout(CancelSignal().withTimeout(10_000))
        } catch (_: OperationCanceledException) {
            LogoutResult(false, false, "공유기가 제한 시간 안에 응답하지 않았습니다.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogoutResult(false, false, "로그아웃 중 오류: ${e.message}")
        }

        log.info("종료 준비: ${result.message}")
        flow.onSession(browser.session.state, browser.session.lastConfirmedAt)
        refreshUi()
        return result
    }

    /** [종료] 버튼: 공유기 로그아웃 확인 후 종료. 확인이 안 되면 그래도 종료할지 묻는다. */
    fun exitWithLogout() {
        if (exiting) return
        scope.launch {
            val result = prepareExit()
            if (!result.confirmed) {
                if (options.selfTest) {
                    exiting = false
                    refreshUi()
                    return@launch
                }
                dialog = AppDialog(
                    "종료",
                    "공유기 로그아웃을 확인하지 못했습니다.\n${result.message}\n\n그래도 종료할까요? (공유기 세션은 시간이 지나면 자동으로 만료됩니다)",
                    "종료",
                    "취소",
                ) { ok ->
                    dialog = null
                    if (ok) {
                        finishApp(result.message)
                    } else {
                        exiting = false
                        setStatus("종료를 취소했습니다. ${result.message}", BannerKind.Warning)
                        refreshUi()
                    }
                }
                return@launch
            }
            finishApp(result.message)
        }
    }

    private fun finishApp(message: String) {
        setStatus("$message 앱을 종료합니다.", BannerKind.Success)
        exitApproved = true
        scope.launch {
            try {
                browser.clearBrowsingData()
            } catch (_: Exception) {
            }
            activity.finishAndRemoveTask()
        }
    }

    /** 뒤로 가기: 열린 창부터 닫고, 마지막에는 앱을 끄지 않고 뒤로 보낸다(로그인 세션 유지). */
    fun onBack() {
        when {
            dialog != null -> Unit
            modeSheet != null -> modeSheet = null
            screen == Screen.Settings -> screen = Screen.Main
            screen == Screen.Setup -> skipSetup()
            routerVisible -> showRouter(false)
            else -> activity.moveTaskToBack(true)
        }
    }

    // ================================================================== 기록

    fun showLog(visible: Boolean) {
        if (logVisible == visible) return
        logVisible = visible
        settings = settings.copy(showLog = visible)
        if (!options.selfTest) trySaveSettings()
    }

    // ================================================================== 설정 / 진단

    fun openSettings() {
        if (busy) return
        screen = Screen.Settings
    }

    fun closeSettings() {
        screen = Screen.Main
    }

    fun openSetup() {
        if (busy) return
        screen = Screen.Setup
    }

    companion object {
        const val SETUP_SKIPPED_MESSAGE = "시작 설정을 건너뛰었습니다. ⚙ 설정에서 공유기 주소와 켤 PC 이름을 입력하면 공유기 화면이 열립니다."

        /** 시작 설정을 띄울지: 아직 마치지 않았고, 모의 공유기·자체검사 실행이 아닐 때. */
        fun shouldRunSetup(s: AppSettings, options: LaunchOptions): Boolean = !s.setupCompleted && !options.mock && !options.selfTest
    }

    fun skipSetup() {
        log.info("시작 설정을 건너뜀")
        screen = Screen.Main
        setStatus(SETUP_SKIPPED_MESSAGE, BannerKind.Warning)
    }

    /** 시작 설정 완료: 저장하고 공유기 화면을 연다. */
    fun completeSetup(result: AppSettings) {
        log.info("시작 설정 완료")
        result.setupCompleted = true
        val routerChanged = !RouterBrowser.sameRouter(result.routerUrl, settings.routerUrl)
        screen = Screen.Main
        applySettings(result, save = true)
        if (!routerChanged && initDone) scope.launch { navigateToRouter(expand = true) }
    }

    /**
     * 설정 초기화: 모든 설정을 처음 상태로 되돌려 저장하고, 열려 있던 공유기 화면을 비운 뒤 시작 설정을 다시 연다.
     * 기록 파일은 건드리지 않는다.
     */
    fun resetSettings(showSetup: Boolean) {
        log.warn("설정 초기화: 모든 설정을 처음 상태로 되돌립니다(기록은 그대로).")
        settleSignal?.cancel()
        settings = AppSettings(showLog = logVisible)
        trySaveSettings()
        browser.session.reset("설정 초기화")
        flow.onSession(SessionState.Unknown, null)
        flow.resetForNewSession()
        if (browser.isReady) scope.launch { browser.navigate("about:blank", 5000) }
        setStatus("설정을 초기화했습니다. 시작 설정에서 공유기 주소와 켤 PC를 다시 입력하세요.", BannerKind.Info)
        refreshUi()
        screen = if (showSetup && !options.selfTest) Screen.Setup else Screen.Main
    }

    fun applySettings(s: AppSettings, save: Boolean) {
        // ⚙ 설정에서 공유기 설정을 제대로 저장하면 시작 설정을 마친 것으로 본다.
        if (save && s.validateRouter().isEmpty()) s.setupCompleted = true
        val routerChanged = !RouterBrowser.sameRouter(s.routerUrl, settings.routerUrl)
        // 기록 표시 같은 화면 상태는 설정 화면에서 편집하지 않으므로 현재 값을 유지한다.
        s.showLog = logVisible
        settings = s
        if (save) trySaveSettings()
        applyRouterLayout()
        power.checkSoon()
        refreshUi()
        if (routerChanged) {
            browser.session.reset("공유기 URL 변경")
            flow.onSession(SessionState.Unknown, null)
            refreshUi()
            if (initDone) {
                if (s.routerUri != null) scope.launch { navigateToRouter(expand = true) }
                else if (browser.isReady) scope.launch { browser.navigate("about:blank", 5000) }
            }
        }
    }

    private fun trySaveSettings() {
        try {
            settings.save(settingsFile)
        } catch (e: Exception) {
            log.warn("설정 저장 실패: ${e.message}")
        }
    }

    fun setTheme(theme: String) {
        settings = settings.copy(theme = theme)
        trySaveSettings()
    }

    /** 설정 내보내기(사용자가 고른 파일에). 성공하면 null, 실패하면 이유. */
    suspend fun exportSettings(uri: Uri, current: AppSettings): String? = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(current.toExportJson().toByteArray(Charsets.UTF_8)) }
                ?: return@withContext "파일을 열지 못했습니다."
            log.info("설정 내보내기 완료")
            null
        } catch (e: Exception) {
            "내보내지 못했습니다: ${e.message}"
        }
    }

    /** 설정 가져오기. (설정, 오류) 중 하나. */
    suspend fun importSettings(uri: Uri): Pair<AppSettings?, String?> = withContext(Dispatchers.IO) {
        try {
            val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext null to "파일을 열지 못했습니다."
            AppSettings.fromExportJson(text)
        } catch (e: Exception) {
            null to "가져오지 못했습니다: ${e.message}"
        }
    }

    /**
     * [기록 공유]: 화면 기록(주소·MAC·비밀번호 후보는 가림)을 다른 앱으로 보낸다. 받는 앱은 사용자가 고른다.
     * 실기기에서 느린 단계나 실패 원인을 알려 줄 때 쓴다(단계별 경과 시간이 들어 있다).
     */
    fun shareLog() {
        val text = buildString {
            appendLine("RemoteAccessHub Android ${BuildConfig.VERSION_NAME} 기록 (Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL})")
            for (e in log.snapshot(800)) {
                if (e.level != LogLevel.Debug) appendLine(DiagnosticsExporter.maskHostsInText(e.format(), settings))
            }
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "RemoteAccessHub 기록")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        try {
            activity.startActivity(android.content.Intent.createChooser(send, "기록 공유"))
        } catch (e: Exception) {
            setStatus("기록을 공유하지 못했습니다: ${e.message}", BannerKind.Warning)
        }
    }

    fun exportDiagnostics(uri: Uri) {
        scope.launch {
            try {
                val device = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
                val json = DiagnosticsExporter.build(browser, settings, log, BuildConfig.VERSION_NAME, device)
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("파일을 열지 못했습니다.")
                }
                log.info("진단 파일 저장")
                setStatus("진단 파일을 저장했습니다(비밀번호·쿠키·원본 HTML 미포함, 주소·MAC 가림).", BannerKind.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("진단 파일 저장 실패: ${e.message}")
                setStatus("진단 파일을 저장하지 못했습니다: ${e.message}", BannerKind.Error)
            }
        }
    }

    /** 디버그 빌드: 모의 공유기 모드로 앱을 다시 연다. */
    fun restartInMockMode(enable: Boolean) {
        val intent = activity.intent.apply { putExtra(MainActivityExtras.MOCK, enable) }
        MainActivity.releaseRetained()
        activity.finish()
        activity.startActivity(intent)
    }

    /**
     * 시스템이 화면(액티비티)을 다시 만들었을 때 새 화면에 이어 붙인다. 제어부·WebView·로그인 세션은 그대로 유지한다.
     * (리소스 경로 변경 등 매니페스트로 막을 수 없는 이유로 화면이 다시 만들어질 수 있다.)
     */
    fun attach(newActivity: Activity) {
        activity = newActivity
        browser.attachTo(newActivity)
        log.debug("새 화면에 다시 연결(공유기 화면·세션 유지)")
    }

    /** 화면이 사라질 때(다시 만들어질 예정): 공유기 화면을 떼어 두기만 하고 버리지 않는다. */
    fun detach() {
        (browser.frame.parent as? android.view.ViewGroup)?.removeView(browser.frame)
    }

    fun dispose() {
        settleSignal?.cancel()
        opSignal?.cancel()
        power.dispose()
        scope.cancel()
        log.info("앱 종료")
        browser.destroy()
        mock?.close()
        log.close()
    }
}

object MainActivityExtras {
    const val MOCK = "mock"
}
