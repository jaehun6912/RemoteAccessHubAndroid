package io.github.jaehun6912.remoteaccesshub.router

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.jaehun6912.remoteaccesshub.BuildConfig
import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.Mono
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.RouterPages
import io.github.jaehun6912.remoteaccesshub.core.SessionProbeResult
import io.github.jaehun6912.remoteaccesshub.core.SessionTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.coroutines.resume

/**
 * 공유기 화면을 담는 WebView 래퍼(Windows 버전 Router/RouterBrowser.cs에 해당).
 * - 사생활 보호: 시작·종료할 때 쿠키·저장소·캐시를 지우고, 비밀번호 저장·자동 완성을 끈다(Windows의 InPrivate 프로필 역할).
 * - 화면 판독(probe.js), 접근성 활성화, 세션 확인(session/info), 접근성 노드 클릭, 좌표 터치, 공유기 API 호출 관찰(netwatch.js).
 * - 스크립트 대화상자(alert/confirm)는 앱 대화상자로 대신 띄운다.
 * 모든 메서드는 화면(메인) 스레드에서 불러야 한다.
 */
@SuppressLint("SetJavaScriptEnabled")
class RouterBrowser(
    private val context: Context,
    private val log: AppLog,
    private val settings: () -> AppSettings,
) : RouterDriver {
    // 화면이 다시 만들어져도 WebView를 새 화면에 옮겨 붙일 수 있도록 바꿀 수 있는 Context로 감싼다.
    private val contextHolder = MutableContextWrapper(context)
    val webView: WebView = WebView(contextHolder)
    val frame: WideWebFrame = WideWebFrame(context).apply {
        addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private val probeScript: String = context.assets.open("probe.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    private val netScript: String = context.assets.open("netwatch.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    private var navWait: CompletableDeferred<Boolean>? = null
    private var mainFrameFailed = false
    private var docStartScript: ScriptHandler? = null
    private var evalSeq = 0

    override val session = SessionTracker()
    override val network = NetworkObserver(log) { js -> evaluateRaw(js) }
    override var isReady: Boolean = false
        private set

    var lastInitError: String? = null
        private set

    val currentUrl: String get() = webView.url ?: ""

    /** (종류, 메시지, 응답) — 응답(true=수락)을 꼭 한 번 불러야 한다. null이면 confirm/prompt는 거부, alert는 수락. */
    var scriptDialogHandler: ((kind: String, message: String, answer: (Boolean) -> Unit) -> Unit)? = null

    var urlChanged: ((String) -> Unit)? = null
    var navigationFinished: ((Boolean) -> Unit)? = null

    suspend fun initialize() {
        if (isReady) return
        try {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true // 공유기 앱(Flutter 웹)이 저장소를 쓴다. 시작·종료 때 지운다.
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false) // 새 창 요청은 같은 화면에서 연다(Windows 버전과 같음)
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                @Suppress("DEPRECATION")
                saveFormData = false
                setGeolocationEnabled(false)
                useWideViewPort = true
                loadWithOverviewMode = true // viewport 지정이 없는 PC 전용 화면은 화면 너비에 맞춰 축소해 보여 준다
                builtInZoomControls = true
                displayZoomControls = false
                textZoom = 100 // 휴대폰 글꼴 크기 설정이 공유기 화면 배치를 바꾸지 않도록
            }
            // 비밀번호 관리자·자동 완성이 공유기 아이디·비밀번호를 저장하지 않도록 한다.
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            webView.webViewClient = Client()
            webView.webChromeClient = Chrome()
            webView.setDownloadListener { _, _, _, _, _ -> log.warn("공유기 화면의 파일 내려받기 요청은 무시합니다.") }

            clearBrowsingData()
            installNetworkHook()
            isReady = true
            log.info("내장 브라우저 준비됨 (WebView ${WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "?"}, 시작할 때 쿠키·저장소 비움)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastInitError = e.message
            log.error("내장 브라우저 초기화 실패: ${e.message}")
            throw e
        }
    }

    /** 공유기 API 관찰 스크립트를 문서가 시작될 때 넣는다(공유기 앱이 통신 객체를 만들기 전에 감싸야 한다). */
    private fun installNetworkHook() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            docStartScript?.remove()
            // 관찰 스크립트는 페이지 안에 기록만 남기므로(밖으로 보내는 것 없음) 모든 출처에 넣는다.
            // 공유기 주소를 바꿔도 다시 등록할 필요가 없다.
            docStartScript = WebViewCompat.addDocumentStartJavaScript(webView, netScript, setOf("*"))
        } else {
            log.warn("이 WebView는 문서 시작 스크립트를 지원하지 않아 페이지를 불러올 때 관찰 스크립트를 넣습니다(첫 요청 일부를 놓칠 수 있음).")
        }
    }

    /** 쿠키·웹 저장소·캐시·자동 완성 기록을 지운다. 시작할 때와 [종료]할 때 부른다. */
    suspend fun clearBrowsingData() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        suspendCancellableCoroutine { cont -> cookies.removeAllCookies { if (cont.isActive) cont.resume(Unit) } }
        cookies.flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: ""
            if (scheme == "http" || scheme == "https" || scheme == "about") return false
            log.warn("공유기 화면에서 다른 앱 주소($scheme:)로 이동하려는 요청을 막았습니다.")
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(netScript, null)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            urlChanged?.invoke(url ?: "")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                log.warn("페이지 이동 실패: ${error.errorCode} ${error.description}")
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(netScript, null)
            val ok = !mainFrameFailed
            navWait?.complete(ok)
            navigationFinished?.invoke(ok)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val s = settings()
            val routerHost = s.routerUri?.host?.trim('[', ']')
            val reqHost = try {
                URI(error.url).host?.trim('[', ']')
            } catch (_: Exception) {
                null
            }
            if (s.allowRouterCertificateError && routerHost != null && routerHost.equals(reqHost, ignoreCase = true)) {
                log.warn("설정에 따라 공유기(${reqHost})의 인증서 오류(${error.primaryError})를 허용합니다.")
                handler.proceed()
            } else {
                log.warn("인증서 오류(${error.primaryError}) — 기본 동작(차단) 유지. 필요하면 설정에서 '공유기 인증서 오류 허용'을 켜세요.")
                handler.cancel()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 공유기 화면 프로세스가 죽어도 앱 전체가 꺼지지 않게 한다. 이 WebView는 더 쓸 수 없다.
            isReady = false
            lastInitError = "공유기 화면 프로세스가 종료되었습니다. 앱을 다시 시작하세요."
            log.error("공유기 화면 프로세스 종료(충돌=${detail.didCrash()})")
            navWait?.complete(false)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        private fun ask(kind: String, message: String?, result: JsResult): Boolean {
            val msg = message ?: ""
            log.info("페이지 대화상자($kind): $msg")
            val handler = scriptDialogHandler
            if (handler == null) {
                if (kind == "Alert") result.confirm() else result.cancel()
            } else {
                var answered = false
                handler(kind, msg) { accept ->
                    if (answered) return@handler
                    answered = true
                    if (accept) result.confirm() else result.cancel()
                }
            }
            return true
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult) = ask("Alert", message, result)
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult) = ask("Confirm", message, result)
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult) = ask("Prompt", message, result)
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    // ------------------------------------------------------------------ 주소

    val isOnRouterOrigin: Boolean
        get() {
            val origin = settings().routerOrigin ?: return false
            return originOf(currentUrl)?.equals(origin, ignoreCase = true) == true
        }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val default = (scheme == "http" && u.port == 80) || (scheme == "https" && u.port == 443) || u.port == -1
        if (default) "$scheme://$host" else "$scheme://$host:${u.port}"
    } catch (_: Exception) {
        null
    }

    override suspend fun navigate(url: String, timeoutMs: Long, signal: CancelSignal): Boolean {
        ensureReady()
        val wait = CompletableDeferred<Boolean>()
        navWait = wait
        log.info("페이지 이동: $url")
        try {
            webView.loadUrl(url)
        } catch (e: Exception) {
            log.error("페이지 이동 오류: ${e.message}")
            return false
        }
        val deadline = Mono.now() + timeoutMs
        while (!wait.isCompleted) {
            signal.throwIfCancelled()
            if (Mono.now() >= deadline) return false
            delay(50)
        }
        return wait.getCompleted()
    }

    fun reload() {
        ensureReady()
        webView.reload()
    }

    // ------------------------------------------------------------------ 스크립트

    /** evaluateJavascript 결과(JSON 문자열)를 그대로 돌려준다. 응답이 없으면 null. */
    suspend fun evaluateRaw(script: String, timeoutMs: Long = 15_000): String? {
        if (!isReady) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont -> webView.evaluateJavascript(script) { r -> if (cont.isActive) cont.resume(r) } }
        }
    }

    /** 동기 스크립트 실행(결과는 JSON 문자열). Promise를 기다리지 않으므로 async 스크립트에는 [evaluateAsync]를 쓴다. */
    suspend fun execute(script: String, signal: CancelSignal = CancelSignal.None): String? {
        ensureReady()
        signal.throwIfCancelled()
        return evaluateRaw(script)
    }

    /**
     * 스크립트를 실행하고 Promise면 완료값을 기다린다(Windows의 DevTools Runtime.evaluate(awaitPromise) 역할).
     * 반환값은 문자열 값 자체(JSON 감싸기 없음). 예외·시간 초과 시 null.
     */
    suspend fun evaluateAsync(expression: String, signal: CancelSignal = CancelSignal.None, timeoutMs: Long = 15_000): String? {
        ensureReady()
        signal.throwIfCancelled()
        val id = "r" + (++evalSeq) + "_" + SystemClock.uptimeMillis()
        val start = "(function(){var R=window.__rahEval||(window.__rahEval={});var id=" + quote(id) + ";" +
            "try{Promise.resolve().then(function(){return (" + expression + ");}).then(function(v){" +
            "R[id]={ok:true,v:(typeof v==='string')?v:(v===undefined?null:JSON.stringify(v))};}," +
            "function(e){R[id]={ok:false,e:String(e&&e.message||e).slice(0,200)};});}catch(e){R[id]={ok:false,e:String(e).slice(0,200)};}" +
            "return id;})()"
        if (evaluateRaw(start) == null) return null
        val poll = "(function(id){var R=window.__rahEval;if(!R||!R[id])return null;var r=R[id];delete R[id];return JSON.stringify(r);})(" + quote(id) + ")"
        val deadline = Mono.now() + timeoutMs
        while (Mono.now() < deadline) {
            signal.throwIfCancelled()
            val raw = evaluateRaw(poll)
            if (raw != null && raw != "null") {
                val obj = try {
                    json.parseToJsonElement(unwrapString(raw)).jsonObject
                } catch (_: Exception) {
                    return null
                }
                if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    log.debug("스크립트 예외: " + (obj["e"]?.jsonPrimitive?.content ?: "").lineSequence().first())
                    return null
                }
                val v = obj["v"]
                return if (v is JsonPrimitive && v.isString) v.content else null
            }
            delay(40)
        }
        return null
    }

    // ------------------------------------------------------------------ 판독

    override suspend fun probe(signal: CancelSignal): ProbeSnapshot {
        if (!isReady) return ProbeSnapshot(error = "브라우저 준비 안 됨")
        return try {
            signal.throwIfCancelled()
            ProbeSnapshot.parse(evaluateRaw(probeScript))
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            ProbeSnapshot(error = "판독 실패: ${e.message}")
        }
    }

    override suspend fun ensureSemantics(waitMs: Long, signal: CancelSignal): Boolean {
        val deadline = Mono.now() + waitMs
        var clicks = 0
        while (Mono.now() < deadline) {
            signal.throwIfCancelled()
            val snap = probe(signal)
            if (snap.semanticsCount > 0) return true
            if (!snap.flutter) return snap.nodes.isNotEmpty() // 일반 HTML 페이지
            if (snap.placeholder && clicks < 3) {
                val js = "(function(){var p=document.querySelector('flt-semantics-placeholder');if(!p)return 'none';try{p.click();}catch(e){return 'err:'+e;}return 'clicked';})()"
                val r = unwrapString(execute(js, signal))
                clicks++
                log.debug("접근성 활성화 시도 $clicks: $r")
            }
            signal.delay(300)
        }
        return probe(signal).semanticsCount > 0
    }

    // ------------------------------------------------------------------ 세션

    override suspend fun probeSession(signal: CancelSignal): SessionProbeDetail {
        if (!isReady) return SessionProbeDetail(SessionProbeResult.Unavailable, "브라우저 준비 안 됨", 0, null)
        if (!isOnRouterOrigin) return SessionProbeDetail(SessionProbeResult.Unavailable, "현재 페이지가 공유기 주소가 아님", 0, null)
        val js = "(async()=>{try{const r=await fetch('/cgi/service.cgi',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'},body:JSON.stringify({method:'session/info'})});const t=await r.text();let j=null;try{j=JSON.parse(t)}catch(e){}const err=(j&&j.error)?j.error:null;return JSON.stringify({status:r.status,ok:!!(j&&j.result!=null&&!err),code:err?err.code:null,msg:err?String(err.message||'').slice(0,80):null,parsed:!!j});}catch(e){return JSON.stringify({status:0,ok:false,code:null,msg:String(e).slice(0,100),parsed:false});}})()"
        return try {
            val raw = evaluateAsync(js, signal)
            if (raw.isNullOrEmpty()) return SessionProbeDetail(SessionProbeResult.Unavailable, "세션 확인 스크립트가 결과를 돌려주지 않음", 0, null)
            val root = json.parseToJsonElement(raw).jsonObject
            val status = root.int("status") ?: 0
            val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
            val code = root.int("code")
            val msg = (root["msg"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val parsed = root["parsed"]?.jsonPrimitive?.booleanOrNull == true

            when {
                ok -> SessionProbeDetail(SessionProbeResult.Ok, "session/info 정상", status, null)
                code == -31998 || (msg != null && msg.contains("Unauthenticated", ignoreCase = true)) || status == 401 || status == 403 ->
                    SessionProbeDetail(SessionProbeResult.Unauthenticated, "공유기 응답: 인증되지 않음(${code ?: status})", status, code)
                parsed && code != null -> SessionProbeDetail(SessionProbeResult.Unavailable, "공유기 오류 응답 $code $msg", status, code)
                else -> SessionProbeDetail(
                    SessionProbeResult.Unavailable,
                    if (status == 0) "네트워크 오류: $msg" else "응답 형식 불명(HTTP $status)",
                    status,
                    code,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            SessionProbeDetail(SessionProbeResult.Unavailable, "세션 확인 스크립트 실패: ${e.message}", 0, null)
        }
    }

    /**
     * 공유기 관리 세션을 끊는다: 페이지 안에서 session/logout 호출 → session/info로 "인증되지 않음"을 확인.
     * 확인되지 않으면 confirmed=false로 알려 준다(추측으로 성공 처리하지 않음).
     */
    suspend fun logout(signal: CancelSignal): LogoutResult {
        if (!isReady) return LogoutResult(false, false, "내장 브라우저가 준비되지 않았습니다.")
        if (!isOnRouterOrigin) return LogoutResult(false, false, "공유기 페이지가 열려 있지 않아 로그아웃을 요청할 수 없습니다.")

        val before = probeSession(signal)
        if (before.result == SessionProbeResult.Unauthenticated) {
            session.apply(before.result, "종료 전 확인: 이미 로그아웃 상태")
            return LogoutResult(true, true, "이미 로그아웃 상태입니다.")
        }

        val js = "(async()=>{try{const r=await fetch('/cgi/service.cgi',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'},body:JSON.stringify({method:'session/logout'})});const t=await r.text();let j=null;try{j=JSON.parse(t)}catch(e){}const err=(j&&j.error)?j.error:null;return JSON.stringify({status:r.status,code:err?err.code:null,msg:err?String(err.message||'').slice(0,80):null});}catch(e){return JSON.stringify({status:0,code:null,msg:String(e).slice(0,100)});}})()"
        val note = try {
            evaluateAsync(js, signal) ?: "응답 없음"
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            "요청 실패: ${e.message}"
        }
        log.info("공유기 로그아웃 요청 결과: " + AppLog.redact(note))

        // 로그아웃 반영 확인(짧게 재시도)
        repeat(3) {
            val after = probeSession(signal)
            if (after.result == SessionProbeResult.Unauthenticated) {
                session.apply(after.result, "사용자 종료: 공유기 로그아웃 확인")
                return LogoutResult(true, false, "공유기 로그아웃을 확인했습니다.")
            }
            signal.delay(400)
        }
        return LogoutResult(false, false, "로그아웃 요청 후에도 공유기 세션이 남아 있습니다.")
    }

    // ------------------------------------------------------------------ 누르기

    override suspend fun clickSemanticsNode(nodeId: String, expectedLabel: String, expectedRect: ProbeRect, signal: CancelSignal): ClickResult {
        ensureReady()
        val payload = "{\"id\":${quote(nodeId)},\"label\":${quote(expectedLabel)},\"x\":${expectedRect.x},\"y\":${expectedRect.y},\"w\":${expectedRect.w},\"h\":${expectedRect.h}}"
        val js = "(function(p){function find(root){var el=root.getElementById?root.getElementById(p.id):null;if(el)return el;var all=root.querySelectorAll?root.querySelectorAll('*'):[];for(var i=0;i<all.length;i++){if(all[i].shadowRoot){var f=find(all[i].shadowRoot);if(f)return f;}}return null;}\n" +
            "var el=find(document);if(!el)return 'missing';var role=el.getAttribute('role')||'';if(role!=='button')return 'role:'+role;var label=(el.getAttribute('aria-label')||el.textContent||'').replace(/\\s+/g,' ').trim();if(label!==p.label)return 'label:'+label;var r=el.getBoundingClientRect();if(Math.abs(r.x-p.x)>6||Math.abs(r.y-p.y)>6||Math.abs(r.width-p.w)>6||Math.abs(r.height-p.h)>6)return 'moved:'+Math.round(r.x)+','+Math.round(r.y);if(el.getAttribute('aria-disabled')==='true')return 'disabled';try{el.click();}catch(e){return 'err:'+e;}return 'clicked';})(" + payload + ")"
        val r = unwrapString(execute(js, signal))
        return ClickResult(r == "clicked", r)
    }

    override suspend fun pushRoute(path: String, signal: CancelSignal): Boolean {
        ensureReady()
        val js = "(function(p){try{history.pushState(null,'',p);window.dispatchEvent(new PopStateEvent('popstate',{state:null}));return location.pathname;}catch(e){return 'err:'+e;}})(" + quote(path) + ")"
        val r = evaluateAsync(js, signal)
        log.debug("앱 내부 경로 이동 $path → $r")
        return r != null && !r.startsWith("err:")
    }

    override suspend fun clickVerifiedParagraph(text: String, expected: ProbeRect, signal: CancelSignal): ClickResult {
        val snap = probe(signal)
        val want = RouterPages.compact(text)
        val p = snap.paragraphs.firstOrNull {
            !it.rect.isEmpty && RouterPages.compact(it.text) == want &&
                kotlin.math.abs(it.rect.x - expected.x) <= 6 && kotlin.math.abs(it.rect.y - expected.y) <= 6
        } ?: return ClickResult(false, "텍스트 위치가 바뀜")
        val x = p.rect.centerX
        val y = p.rect.centerY
        RouterPages.unsafeReason(snap, want, x, y)?.let { return ClickResult(false, it) }
        val ok = clickAt(x, y, signal)
        return ClickResult(ok, if (ok) "clicked" else "클릭 전송 실패")
    }

    /**
     * 실제 터치(누름 → 뗌)를 보낸다. 좌표는 CSS px(뷰포트 기준)이고, 기기 배율과 확대 상태를 반영해 화면 픽셀로 바꾼다.
     * WebView에 직접 전달하므로 공유기 화면이 다른 화면 뒤에 숨어 있어도 공유기 화면이 받는다.
     */
    override suspend fun clickAt(x: Double, y: Double, signal: CancelSignal): Boolean {
        ensureReady()
        return try {
            val info = evaluateRaw(
                "(function(){var v=window.visualViewport;return JSON.stringify({d:window.devicePixelRatio||1,x:v?v.offsetLeft:0,y:v?v.offsetTop:0,s:v?v.scale:1});})()",
            ) ?: return false
            val o = json.parseToJsonElement(unwrapString(info)).jsonObject
            val dpr = o.double("d") ?: 1.0
            val ox = o.double("x") ?: 0.0
            val oy = o.double("y") ?: 0.0
            val scale = o.double("s") ?: 1.0
            val vx = ((x - ox) * scale * dpr).toFloat()
            val vy = ((y - oy) * scale * dpr).toFloat()
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, vx, vy, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            webView.dispatchTouchEvent(down)
            down.recycle()
            try {
                delay(60)
            } finally {
                // 누름만 보내고 끝나면 페이지가 계속 눌린 상태로 여긴다: 취소·오류와 상관없이 항상 뗀다.
                val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, vx, vy, 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                webView.dispatchTouchEvent(up)
                up.recycle()
            }
            signal.throwIfCancelled()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("좌표 터치 실패: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------ 기타

    private fun ensureReady() {
        if (!isReady) throw IllegalStateException(lastInitError ?: "내장 브라우저가 아직 준비되지 않았습니다.")
    }

    /** 새 화면(액티비티)으로 옮긴다(대화상자·키보드가 새 화면 기준으로 뜨도록). */
    fun attachTo(activity: Context) {
        contextHolder.baseContext = activity
    }

    fun destroy() {
        try {
            docStartScript?.remove()
            webView.stopLoading()
            frame.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {
        }
        isReady = false
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun quote(s: String): String = json.encodeToString(String.serializer(), s)

        private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

        /** evaluateJavascript가 돌려준 JSON 문자열 리터럴을 실제 문자열로 푼다. */
        fun unwrapString(scriptResult: String?): String {
            if (scriptResult.isNullOrEmpty() || scriptResult == "null") return ""
            if (scriptResult.startsWith("\"")) {
                return try {
                    json.decodeFromString(String.serializer(), scriptResult)
                } catch (_: Exception) {
                    scriptResult
                }
            }
            return scriptResult
        }

        /** 공유기 주소가 바뀌었는지 비교할 때 쓴다. */
        fun sameRouter(a: String?, b: String?): Boolean =
            InputRules.parseRouterUrl(a)?.text.equals(InputRules.parseRouterUrl(b)?.text, ignoreCase = true)
    }
}
