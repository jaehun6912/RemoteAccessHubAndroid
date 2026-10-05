package io.github.jaehun6912.remoteaccesshub.router

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.Mono
import io.github.jaehun6912.remoteaccesshub.core.Paragraph
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.RouterPageKind
import io.github.jaehun6912.remoteaccesshub.core.RouterPages
import io.github.jaehun6912.remoteaccesshub.core.SemanticNode
import io.github.jaehun6912.remoteaccesshub.core.SessionProbeResult
import io.github.jaehun6912.remoteaccesshub.core.WolMatchResult
import io.github.jaehun6912.remoteaccesshub.core.WolMatcher
import io.github.jaehun6912.remoteaccesshub.core.WolTarget
import kotlin.math.abs

enum class WolStep { SessionCheck, NavigateToWol, Match, Click, Confirm, RouterResponse }

enum class StageStatus { Pending, Running, Done, Failed, Unknown }

/** @param requestAborted wol/signal 요청이 공유기 화면에서 중간에 취소됨(net::ERR_ABORTED) — 공유기 처리 여부를 알 수 없음 */
data class WolOutcome(
    val success: Boolean,
    val lastStep: WolStep,
    val message: String,
    val clickDone: Boolean,
    val routerStatus: StageStatus,
    val match: WolMatchResult?,
    val requestAborted: Boolean = false,
) {
    companion object {
        fun fail(step: WolStep, message: String, match: WolMatchResult? = null, clickDone: Boolean = false, router: StageStatus = StageStatus.Pending) =
            WolOutcome(false, step, message, clickDone, router, match)
    }
}

/**
 * 로그인된 같은 브라우저 세션에서: 세션 재확인 → (필요하면 [관리도구] 선택) → WOL 화면 이동 →
 * 대상 PC의 [PC 켜기] 버튼 확인 후 클릭 → 확인창 처리 → 공유기 응답(wol/signal) 확인.
 * 클릭 성공 / 공유기 처리 / 실제 부팅은 서로 구분해 보고한다. Windows 버전 Router/WolAutomation.cs와 같은 흐름.
 */
class WolAutomation(
    private val browser: RouterDriver,
    private val log: AppLog,
    private val settings: () -> AppSettings,
) {
    val navigator = RouterNavigator(browser, log, settings).also { nav ->
        nav.progress = { m -> stageChanged?.invoke(WolStep.NavigateToWol, StageStatus.Running, m) }
    }

    var stageChanged: ((WolStep, StageStatus, String) -> Unit)? = null

    /** 확인창이 화면에 나타났는데 자동으로 누르지 못했을 때 호출(공유기 화면을 띄우기 위함). */
    var dialogAppeared: (() -> Unit)? = null

    /** 사용자 조작이 필요할 때 호출(공유기 화면을 띄우기 위함). */
    var userActionNeeded: (() -> Unit)? = null

    private fun stage(step: WolStep, status: StageStatus, message: String) {
        log.info("[WOL $step] $status: $message")
        stageChanged?.invoke(step, status, message)
    }

    /** @param skipNavigation 사용자가 이미 WOL 화면을 열어 둔 경우(수동 이동) true. */
    suspend fun wake(skipNavigation: Boolean, signal: CancelSignal): WolOutcome {
        val s = settings()
        val wake = RouterPages.safeRegex(s.wakeButtonPattern)
        val target = WolTarget(s.wolPcName, s.wolPcMac)
        val startedAt = System.currentTimeMillis()

        // 1) 실행 전 세션 재확인 (공유기 API로 확정)
        stage(WolStep.SessionCheck, StageStatus.Running, "로그인 세션 확인 중...")
        var sd = browser.refreshSession(signal)
        if (sd.result == SessionProbeResult.Unavailable) {
            signal.delay(1000)
            sd = browser.refreshSession(signal)
        }
        if (sd.result == SessionProbeResult.Unauthenticated) {
            stage(WolStep.SessionCheck, StageStatus.Failed, "세션이 만료되었거나 로그인되지 않았습니다.")
            return WolOutcome.fail(WolStep.SessionCheck, "공유기 로그인 세션이 유효하지 않습니다. 공유기 화면에서 다시 로그인하세요.")
        }
        if (sd.result == SessionProbeResult.Unavailable) {
            stage(WolStep.SessionCheck, StageStatus.Failed, sd.reason)
            return WolOutcome.fail(WolStep.SessionCheck, "세션 상태를 확인할 수 없습니다: ${sd.reason}\n공유기 화면이 공유기 주소에 열려 있는지 확인하세요.")
        }
        stage(WolStep.SessionCheck, StageStatus.Done, "로그인 세션 유효")

        // 2) WOL 화면
        if (!skipNavigation) {
            stage(WolStep.NavigateToWol, StageStatus.Running, "WOL 화면으로 이동 중...")
            val nav = navigator.navigateToWol(signal)
            when (nav.status) {
                NavStatus.Ok -> stage(WolStep.NavigateToWol, StageStatus.Done, nav.message)
                NavStatus.NeedLogin -> {
                    // 화면만 보고 로그아웃을 확정하지 않는다: 공유기 API로 다시 확인해 반영
                    browser.refreshSession(signal)
                    stage(WolStep.NavigateToWol, StageStatus.Failed, nav.message)
                    return WolOutcome.fail(WolStep.SessionCheck, nav.message + " 다시 로그인한 뒤 실행하세요.")
                }
                NavStatus.NeedUserSelect -> {
                    stage(WolStep.NavigateToWol, StageStatus.Failed, nav.message)
                    userActionNeeded?.invoke()
                    return WolOutcome.fail(WolStep.NavigateToWol, nav.message + " 그다음 [PC 켜기]를 다시 누르세요.")
                }
                NavStatus.Failed -> {
                    stage(WolStep.NavigateToWol, StageStatus.Failed, nav.message)
                    return WolOutcome.fail(WolStep.NavigateToWol, nav.message)
                }
            }
        } else {
            browser.ensureSemantics(8000, signal)
            val (kind, _) = navigator.classify(signal)
            if (kind != RouterPageKind.WolList) {
                stage(WolStep.NavigateToWol, StageStatus.Failed, "현재 화면이 WOL 목록으로 보이지 않습니다($kind).")
                return WolOutcome.fail(
                    WolStep.NavigateToWol,
                    "현재 화면이 WOL 목록 화면으로 보이지 않습니다. [특수 기능 → WOL 기능] 화면을 연 뒤 다시 시도하세요.",
                )
            }
            stage(WolStep.NavigateToWol, StageStatus.Done, "현재 화면 사용")
        }

        // 공유기 화면이 WOL 요청을 중간에 취소하면(net::ERR_ABORTED) 목록을 새로 불러온 뒤 다시 보낸다(모두 합쳐 최대 MAX_WAKE_ATTEMPTS번).
        // WOL 신호는 여러 번 보내도 PC에는 같은 결과다. 대상 찾기·누르기 직전 확인은 매번 처음부터 다시 한다.
        var listSince = startedAt
        var attempt = 1
        while (true) {
            // 목록 요청이 끊긴 채 누르면 공유기 화면이 WOL 요청을 곧바로 취소했다(Windows 사용자 PC 기록). 먼저 목록을 정상으로 불러온다.
            navigator.ensureWolListLoaded(listSince, forceRefresh = attempt > 1, signal = signal)

            val outcome = matchClickAndObserve(s, target, wake, signal)
            if (!outcome.requestAborted) return outcome
            if (attempt >= MAX_WAKE_ATTEMPTS) {
                return outcome.copy(
                    message = "WOL 요청이 ${MAX_WAKE_ATTEMPTS}번 모두 공유기 화면에서 중간에 취소되어(net::ERR_ABORTED) 공유기가 처리했는지 확인할 수 없습니다.",
                )
            }
            log.warn("WOL 요청이 공유기 화면에서 중간에 취소됨(net::ERR_ABORTED) → 목록을 새로 불러온 뒤 다시 보냅니다(${attempt + 1}/${MAX_WAKE_ATTEMPTS}번째).")
            stage(WolStep.RouterResponse, StageStatus.Running, "요청이 공유기 화면에서 중간에 취소되어, 목록을 새로 불러온 뒤 다시 보냅니다(${attempt + 1}/${MAX_WAKE_ATTEMPTS}번째).")
            listSince = System.currentTimeMillis()
            attempt++
        }
    }

    private suspend fun matchClickAndObserve(s: AppSettings, target: WolTarget, wake: Regex, signal: CancelSignal): WolOutcome {
        // 목록이 그려질 때까지 잠시 대기(로딩 문구, 빈 목록 직후 채워짐)
        browser.waitFor(
            { p -> !p.markers.loadingOverlay && (p.markers.wakeButtons > 0 || p.containsText("등록된 WOL PC가 없습니다")) },
            6000,
            400,
            signal,
        )

        // 3) 대상 버튼 찾기
        stage(WolStep.Match, StageStatus.Running, "'${target.pcName}' 행의 [PC 켜기] 버튼 찾는 중...")
        browser.ensureSemantics(3000, signal)
        val snap = browser.probe(signal)
        val match = WolMatcher.match(snap, target, wake)
        for (d in match.details) log.debug("  $d")
        val t = match.target
        if (!match.isFound || t == null) {
            stage(WolStep.Match, StageStatus.Failed, match.message)
            return WolOutcome.fail(WolStep.Match, match.message, match)
        }
        stage(WolStep.Match, StageStatus.Done, match.message)

        // 4) 클릭 (직전 재검증)
        stage(WolStep.Click, StageStatus.Running, "버튼 클릭 중...")
        val clickWall = System.currentTimeMillis()
        val clickMono = Mono.now()
        val click = if (t.kind == "semantics") {
            browser.clickSemanticsNode(t.nodeId, WolMatcher.compact(t.label), t.rect, signal)
        } else {
            browser.clickVerifiedParagraph(t.label, t.rect, signal)
        }
        val how = (if (t.kind == "semantics") "접근성 노드 클릭: " else "화면 텍스트 위치 클릭: ") + click.reason
        if (!click.clicked) {
            stage(WolStep.Click, StageStatus.Failed, how)
            return WolOutcome.fail(
                WolStep.Click,
                "버튼을 누르기 직전 확인에 실패해 누르지 않았습니다(${click.reason}). 화면이 바뀌었을 수 있으니 다시 시도하세요.",
                match,
            )
        }
        stage(WolStep.Click, StageStatus.Done, how)

        // 5) 확인창 및 공유기 응답
        return observeAfterClick(s, match, clickWall, clickMono, signal)
    }

    private suspend fun observeAfterClick(s: AppSettings, match: WolMatchResult, clickWall: Long, clickMono: Long, signal: CancelSignal): WolOutcome {
        stage(WolStep.Confirm, StageStatus.Running, "확인창/공유기 응답 확인 중...")
        val confirmPattern = s.confirmDialogPattern
        val progressPattern = s.wakeProgressPattern
        var dialogSeen = false
        var dialogHandled = false
        var progressSeen = false
        val deadline = Mono.now() + 12_000
        var manualDeadline = Mono.now() + 120_000
        var abortedSeenAt: Long? = null

        while (true) {
            signal.throwIfCancelled()

            // 클릭 이후에 시작된 요청만 본다(직전 실행의 응답을 이번 결과로 오인하지 않도록)
            val calls = browser.network.since(clickWall, "wol/signal")
            // 결과로 삼는 요청: 정상 완료, 응답(HTTP 상태)을 받은 뒤 끊김, 응답 없이 난 진짜 네트워크 오류.
            // 응답 없이 끊긴 요청(ERR_ABORTED)만 결과로 삼지 않고, 다시 보낸 요청이 있는지 기다린다.
            val done = calls.firstOrNull { it.completed || (it.failed && (it.responseReceived || !NetworkObserver.isAborted(it))) }
            val aborted = calls.firstOrNull { NetworkObserver.isAborted(it) && !it.responseReceived }
            if (done != null && done.failed && done.responseReceived) {
                // 공유기 앱은 응답 본문을 다 읽은 뒤 통신 객체를 닫는다. 그래서 공유기가 이미 응답한 요청도 끊김으로 기록될 수 있다.
                if (!done.resultChecked) {
                    signal.delay(150)
                    continue
                }
                val body = when {
                    done.resultOk == true -> "정상"
                    done.errorCode != null -> "오류 ${done.errorCode}"
                    else -> "판독 불가"
                }
                log.info("wol/signal: 공유기 응답(HTTP ${done.status})을 받은 뒤 공유기 화면이 연결을 닫음(${done.errorText}), 본문 $body")
                if (done.errorCode == null && done.resultOk != true && (done.status ?: 0) in 200..299) {
                    // 본문은 읽지 못했지만 공유기가 정상 상태 코드로 응답함 → 처리된 것으로 본다(다시 보내지 않음)
                    done.paramsMasked?.takeIf { it.isNotEmpty() }?.let { log.info("wol/signal 요청 대상: $it") }
                    stage(WolStep.Confirm, StageStatus.Done, if (dialogSeen) "확인창 처리됨" else "확인창 없이 진행")
                    stage(WolStep.RouterResponse, StageStatus.Done, "공유기가 WOL 요청에 응답했습니다 (HTTP ${done.status}, 응답 받은 뒤 연결 닫힘)")
                    return WolOutcome(true, WolStep.RouterResponse, "공유기가 WOL 요청에 정상 응답했습니다. 실제 부팅은 [PC 접속]에서 원격 데스크톱 포트 응답으로 확인합니다.", true, StageStatus.Done, match)
                }
            }
            if (done == null && aborted != null) {
                val seen = abortedSeenAt ?: Mono.now().also { abortedSeenAt = it }
                val pending = calls.any { !it.failed && !it.completed }
                if (pending || Mono.now() - seen < 2500) {
                    signal.delay(250)
                    continue
                }
                stage(WolStep.Confirm, StageStatus.Done, if (dialogSeen) "확인창 처리됨" else "확인창 없이 진행")
                stage(WolStep.RouterResponse, StageStatus.Unknown, "wol/signal 요청이 공유기 화면에서 중간에 취소됨: ${aborted.errorText}")
                return WolOutcome(
                    false, WolStep.RouterResponse, "WOL 요청이 공유기 화면에서 중간에 취소되었습니다(${aborted.errorText}).",
                    true, StageStatus.Unknown, match, requestAborted = true,
                )
            }
            if (done != null && ((done.failed && !done.responseReceived) || done.resultChecked)) {
                if (done.failed && !done.responseReceived) {
                    stage(WolStep.RouterResponse, StageStatus.Failed, "wol/signal 요청 실패: ${done.errorText}")
                    return WolOutcome(false, WolStep.RouterResponse, "공유기에 WOL 요청을 보냈지만 네트워크 오류가 발생했습니다: ${done.errorText}", true, StageStatus.Failed, match)
                }
                if (done.errorCode != null) {
                    stage(WolStep.RouterResponse, StageStatus.Failed, "공유기 오류 응답 ${done.errorCode} ${done.errorMessage}")
                    val expired = done.errorCode == -31998
                    if (expired) browser.session.apply(SessionProbeResult.Unauthenticated, "wol/signal 응답: 인증되지 않음")
                    return WolOutcome(
                        false, WolStep.RouterResponse,
                        if (expired) "공유기가 '인증되지 않음'으로 응답했습니다. 다시 로그인하세요." else "공유기가 오류로 응답했습니다: ${done.errorCode} ${done.errorMessage}",
                        true, StageStatus.Failed, match,
                    )
                }
                done.paramsMasked?.takeIf { it.isNotEmpty() }?.let { log.info("wol/signal 요청 대상: $it") }
                if (done.resultOk != true) {
                    stage(WolStep.Confirm, StageStatus.Done, if (dialogSeen) "확인창 처리됨" else "확인창 없이 진행")
                    stage(WolStep.RouterResponse, StageStatus.Unknown, "wol/signal 응답을 판독하지 못했습니다 (HTTP ${done.status})")
                    return WolOutcome(false, WolStep.RouterResponse, "공유기에 WOL 요청은 전송됐지만 응답 내용을 확인하지 못했습니다. [PC 접속]으로 부팅 여부를 확인하세요.", true, StageStatus.Unknown, match)
                }
                stage(WolStep.Confirm, StageStatus.Done, if (dialogSeen) "확인창 처리됨" else "확인창 없이 진행")
                stage(WolStep.RouterResponse, StageStatus.Done, "공유기가 WOL 요청을 처리했습니다 (HTTP ${done.status})")
                return WolOutcome(true, WolStep.RouterResponse, "공유기가 WOL 요청을 정상 처리했습니다. 실제 부팅은 [PC 접속]에서 원격 데스크톱 포트 응답으로 확인합니다.", true, StageStatus.Done, match)
            }

            val snap = browser.probe(signal)
            if (!progressSeen && snap.containsText(progressPattern)) {
                progressSeen = true
                log.info("화면 문구 감지: '$progressPattern'")
            }
            if (!dialogSeen && snap.containsText(confirmPattern)) {
                dialogSeen = true
                log.info("확인창 감지: $confirmPattern")
                if (s.autoConfirmWakeDialog) {
                    stage(WolStep.Confirm, StageStatus.Running, "확인창의 [확인]을 자동으로 누르는 중...")
                    val (closed, how) = autoConfirm(confirmPattern, clickWall, signal)
                    dialogHandled = closed
                    if (closed) {
                        stage(WolStep.Confirm, StageStatus.Running, "확인창의 [확인]을 자동으로 눌렀습니다 ($how).")
                    } else {
                        // 자동으로 닫지 못했을 때만 공유기 화면을 띄워 사용자에게 맡긴다.
                        dialogAppeared?.invoke()
                        stage(WolStep.Confirm, StageStatus.Running, "확인창의 [확인]을 자동으로 누르지 못했습니다($how). 공유기 화면에서 [확인]을 누르세요.")
                        manualDeadline = Mono.now() + 120_000
                    }
                } else {
                    dialogAppeared?.invoke()
                    stage(WolStep.Confirm, StageStatus.Running, "공유기가 확인창을 표시했습니다. 공유기 화면에서 [확인]을 누르세요.")
                }
            }

            var limit = if (dialogSeen && !dialogHandled) manualDeadline else deadline
            if (dialogSeen && dialogHandled) limit = clickMono + 25_000
            if (Mono.now() >= limit) break
            signal.delay(500)
        }

        if (progressSeen) {
            stage(WolStep.Confirm, StageStatus.Done, if (dialogSeen) "확인창 처리됨" else "확인창 없음")
            stage(WolStep.RouterResponse, StageStatus.Unknown, "화면에 진행 문구는 표시되었지만 API 응답을 직접 관찰하지 못했습니다.")
            return WolOutcome(true, WolStep.RouterResponse, "화면에 'PC를 켜는 중' 문구가 표시되었습니다(공유기 API 응답은 직접 확인되지 않음).", true, StageStatus.Unknown, match)
        }
        if (dialogSeen) {
            stage(WolStep.Confirm, StageStatus.Failed, "확인창이 처리되지 않았습니다.")
            return WolOutcome(false, WolStep.Confirm, "확인창이 표시되었지만 제한 시간 안에 처리되지 않았습니다. 화면에서 직접 [확인]을 누르거나 다시 시도하세요.", true, StageStatus.Pending, match)
        }
        stage(WolStep.Confirm, StageStatus.Unknown, "확인창/응답 관찰 안 됨")
        stage(WolStep.RouterResponse, StageStatus.Unknown, "클릭 후 공유기 반응을 확인하지 못했습니다.")
        return WolOutcome(false, WolStep.RouterResponse, "버튼을 클릭했지만 확인창도 공유기 API 응답도 관찰되지 않았습니다. 공유기 화면에서 직접 [PC 켜기]를 눌러 보세요.", true, StageStatus.Unknown, match)
    }

    /**
     * 확인창의 [확인]을 누르고 창이 실제로 닫혔는지(또는 wol/signal 요청이 시작됐는지) 확인한다.
     * 순서: 접근성 버튼 click() → 라벨 있는 접근성 노드 위치 → 화면 텍스트 위치. [취소]는 절대 후보가 되지 않는다.
     */
    private suspend fun autoConfirm(confirmPattern: String, clickWall: Long, signal: CancelSignal): Pair<Boolean, String> {
        val tried = mutableListOf<String>()
        for (kind in listOf("semantics", "node", "paragraph")) {
            signal.throwIfCancelled()
            val snap = browser.probe(signal)
            if (dialogGone(snap, confirmPattern, clickWall)) return true to (if (tried.isEmpty()) "이미 닫힘" else tried.joinToString(" → "))

            val target = findConfirmTargets(snap, confirmPattern).firstOrNull { it.kind == kind } ?: continue

            val click: ClickResult
            when (kind) {
                "semantics" -> {
                    click = browser.clickSemanticsNode(target.nodeId, target.text, target.rect, signal)
                    tried.add("접근성 버튼")
                }
                "node" -> {
                    val why = RouterPages.unsafeReason(snap, target.text, target.rect.centerX, target.rect.centerY)
                    if (why != null) {
                        tried.add("노드 위치 거부: $why")
                        continue
                    }
                    click = ClickResult(browser.clickAt(target.rect.centerX, target.rect.centerY, signal), "clicked")
                    tried.add("접근성 노드 위치")
                }
                else -> {
                    click = browser.clickVerifiedParagraph(target.text, target.rect, signal)
                    tried.add("화면 텍스트 위치")
                }
            }
            if (!click.clicked) {
                tried[tried.size - 1] = tried.last() + "(안 누름: ${click.reason})"
                continue
            }

            val until = Mono.now() + 3000
            while (Mono.now() < until) {
                signal.delay(250)
                val after = browser.probe(signal)
                if (dialogGone(after, confirmPattern, clickWall)) return true to tried.joinToString(" → ")
            }
            tried[tried.size - 1] = tried.last() + "(창이 닫히지 않음)"
        }
        return false to (if (tried.isEmpty()) "[확인] 버튼을 찾지 못함" else tried.joinToString(" → "))
    }

    private suspend fun dialogGone(snap: ProbeSnapshot, confirmPattern: String, clickWall: Long): Boolean =
        browser.network.since(clickWall, "wol/signal").isNotEmpty() || (snap.isReadable && !snap.containsText(confirmPattern))

    data class ConfirmTarget(val kind: String, val nodeId: String, val text: String, val rect: ProbeRect)

    companion object {
        /** WOL 요청이 공유기 화면에서 취소될 때 보내는 최대 횟수(처음 포함). WOL 신호는 여러 번 가도 PC에는 같은 결과다. */
        const val MAX_WAKE_ATTEMPTS = 5

        private val confirmLabel = Regex("^(확인|예|OK|Yes)$", RegexOption.IGNORE_CASE)

        /**
         * 확인창 문구와 같은 창 안에 있는 [확인] 후보(선호 순서).
         * 창 안 판정: 문구보다 아래(또는 같은 줄)이고 문구 하단에서 250px 이내, 문구 중심에서 좌우 400px 이내.
         * 실기기 확인창(2026-09-14 화면): 문구 아래 한 줄에 [취소] [확인]이 나란히 있다.
         */
        fun findConfirmTargets(snap: ProbeSnapshot, confirmPattern: String): List<ConfirmTarget> {
            val textRect = snap.paragraphs.firstOrNull { !it.rect.isEmpty && it.text.contains(confirmPattern, ignoreCase = true) }?.rect
                ?: snap.nodes.filter { !it.rect.isEmpty && it.label.contains(confirmPattern, ignoreCase = true) }
                    .minByOrNull { it.rect.w * it.rect.h }?.rect

            fun inDialog(r: ProbeRect): Boolean {
                if (textRect == null || textRect.isEmpty) return false
                val dy = r.centerY - textRect.bottom
                return r.centerY > textRect.y && dy < 250 && abs(r.centerX - textRect.centerX) < 400
            }
            fun dist(r: ProbeRect): Double = if (textRect == null) 0.0 else abs(r.centerY - textRect.bottom)

            val list = mutableListOf<ConfirmTarget>()
            list += snap.nodes
                .filter { it.isButton && it.isUsable && confirmLabel.containsMatchIn(WolMatcher.compact(it.label)) && inDialog(it.rect) }
                .sortedBy { dist(it.rect) }
                .map { ConfirmTarget("semantics", it.id, WolMatcher.compact(it.label), it.rect) }
            list += snap.nodes
                .filter { !it.isButton && !it.hidden && !it.rect.isEmpty && confirmLabel.containsMatchIn(WolMatcher.compact(it.label)) && inDialog(it.rect) }
                .sortedBy { dist(it.rect) }
                .map { ConfirmTarget("node", it.id, WolMatcher.compact(it.label), it.rect) }
            list += snap.paragraphs
                .filter { !it.rect.isEmpty && confirmLabel.containsMatchIn(WolMatcher.compact(it.text)) && inDialog(it.rect) }
                .sortedBy { dist(it.rect) }
                .map { ConfirmTarget("paragraph", "", WolMatcher.compact(it.text), it.rect) }
            return list
        }

        /** 확인창 안의 '확인' 접근성 버튼(없으면 null). */
        fun findConfirmButton(snap: ProbeSnapshot, confirmPattern: String): SemanticNode? {
            val t = findConfirmTargets(snap, confirmPattern).firstOrNull { it.kind == "semantics" } ?: return null
            return snap.nodes.firstOrNull { it.id == t.nodeId }
        }

        /** 확인창 안의 '확인' 화면 텍스트(없으면 null). */
        fun findConfirmParagraph(snap: ProbeSnapshot, confirmPattern: String): Paragraph? {
            val t = findConfirmTargets(snap, confirmPattern).firstOrNull { it.kind == "paragraph" } ?: return null
            return snap.paragraphs.firstOrNull { !it.rect.isEmpty && it.rect == t.rect }
        }
    }
}
