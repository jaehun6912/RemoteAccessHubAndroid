package io.github.jaehun6912.remoteaccesshub.router

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 공유기 API 호출 1건의 관찰 기록. 본문은 저장하지 않고 method 이름과 결과만 남긴다. */
data class ApiCall(
    val key: String,
    /** 요청을 보낸 시각(벽시계 ms). */
    val startedAt: Long,
    val method: String,
    val path: String,
    val paramsMasked: String?,
    val status: Int?,
    val completed: Boolean,
    val failed: Boolean,
    /** 통신 오류(예: net::ERR_ABORTED). */
    val errorText: String?,
    /** 공유기가 돌려준 오류 코드(예: -31998). */
    val errorCode: Int?,
    /** 공유기가 돌려준 오류 문구. */
    val errorMessage: String?,
    /** 응답 본문을 판독한 경우에만 값이 있다(true=정상, false=result 없음). */
    val resultOk: Boolean?,
    /** 응답 본문 판독을 시도했는지(성공·실패 무관). */
    val resultChecked: Boolean,
) {
    /** 공유기가 응답(HTTP 상태)을 돌려줬는지. "끊김"으로 기록돼도 응답을 받았을 수 있다. */
    val responseReceived: Boolean get() = status != null

    /** 결과가 정해졌는지(완료·실패, 그리고 판정이 필요한 호출은 본문 판정까지). */
    val settled: Boolean
        get() = (completed || failed) && (!NetworkObserver.isBodyMethod(method) || resultChecked || !responseReceived)

    override fun toString(): String {
        val t = TIME.format(Instant.ofEpochMilli(startedAt))
        val state = when {
            completed -> status?.toString() ?: "?"
            failed -> if (status != null) "$status 받은 뒤 끊김($errorText)" else "실패($errorText)"
            else -> "진행중"
        }
        val tail = when {
            errorCode != null -> " 오류 $errorCode $errorMessage"
            resultOk == true -> " 정상"
            else -> ""
        }
        return "$t $method → $state$tail"
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}

/**
 * 페이지 안 관찰 스크립트(assets/netwatch.js)가 남긴 공유기 API 호출 기록을 읽어 온다.
 * Windows 버전 Router/NetworkObserver.cs(DevTools 프로토콜)와 같은 정보를 같은 모양으로 돌려준다.
 */
class NetworkObserver(private val log: AppLog, private val fetchRaw: suspend (String) -> String?) {
    @Serializable
    private class RawCall(
        val id: Int = 0,
        val t: Long = 0,
        val sent: Boolean = true,
        val method: String = "",
        val params: String? = null,
        val path: String = "",
        val status: Int? = null,
        val done: Boolean = false,
        val failed: Boolean = false,
        val err: String? = null,
        val checked: Boolean = false,
        val ok: Boolean? = null,
        val code: Int? = null,
        val msg: String? = null,
        val v: Long = 0,
    )

    @Serializable
    private class RawBatch(val doc: String = "", val ver: Long = 0, val calls: List<RawCall> = emptyList())

    private val gate = Any()
    private val calls = ArrayList<ApiCall>()
    private val byKey = HashMap<String, Int>()
    private val announced = HashSet<String>()
    private var doc: String? = null
    private var lastVer = 0L

    /** 결과가 정해진 호출을 한 번씩 알린다. */
    var callCompleted: ((ApiCall) -> Unit)? = null

    /** 페이지의 기록을 읽어 반영한다. 실패해도 예외를 내지 않는다. */
    suspend fun sync() {
        val since: Long
        val d: String
        synchronized(gate) {
            since = lastVer
            d = doc ?: ""
        }
        val script = "(function(s,d){var n=window.__rahNet;if(!n)return null;var f=(n.doc===d)?s:0;var o=[];" +
            "for(var i=0;i<n.calls.length;i++){var c=n.calls[i];if(c.v>f)o.push(c);}" +
            "return JSON.stringify({doc:n.doc,ver:n.ver,calls:o});})(" + since + "," + json.encodeToString(String.serializer(), d) + ")"
        val raw = try {
            fetchRaw(script)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("공유기 API 기록 읽기 실패: ${e.message}")
            null
        }
        if (raw.isNullOrEmpty() || raw == "null") return
        merge(raw)
    }

    /** 페이지에서 읽은 기록(JSON 문자열 또는 그것을 다시 감싼 문자열)을 반영한다. */
    internal fun merge(raw: String) {
        val batch = try {
            val text = if (raw.startsWith("\"")) json.decodeFromString(String.serializer(), raw) else raw
            json.decodeFromString(RawBatch.serializer(), text)
        } catch (e: Exception) {
            log.debug("공유기 API 기록 해석 실패: ${e.message}")
            return
        }
        val done = ArrayList<ApiCall>()
        synchronized(gate) {
            if (batch.doc != doc) {
                // 페이지를 새로 불러오면 이전 페이지에서 진행 중이던 요청은 브라우저가 취소한다(Windows의 loadingFailed와 같은 처리).
                for (i in calls.indices) {
                    val c = calls[i]
                    if (!c.completed && !c.failed) {
                        calls[i] = c.copy(failed = true, errorText = "net::ERR_ABORTED")
                        if (announced.add(c.key)) done.add(calls[i])
                    }
                }
                doc = batch.doc
                lastVer = 0
            }
            for (r in batch.calls) {
                if (!r.sent || r.id <= 0) continue
                val key = batch.doc + ":" + r.id
                val call = ApiCall(
                    key = key,
                    startedAt = r.t,
                    method = r.method,
                    path = r.path,
                    paramsMasked = r.params?.let { p ->
                        val m = InputRules.maskMac(p).replace("\n", " ")
                        if (m.length > 120) m.substring(0, 120) + "…" else m
                    },
                    status = r.status,
                    completed = r.done,
                    failed = r.failed,
                    errorText = r.err,
                    errorCode = r.code,
                    errorMessage = r.msg,
                    resultOk = r.ok,
                    resultChecked = r.checked,
                )
                val idx = byKey[key]
                if (idx == null) {
                    calls.add(call)
                    byKey[key] = calls.size - 1
                } else {
                    calls[idx] = call
                }
                if (call.settled && announced.add(key)) done.add(call)
            }
            lastVer = maxOf(lastVer, batch.ver)
            if (calls.size > MAX_CALLS) {
                val drop = calls.size - MAX_CALLS
                for (i in 0 until drop) {
                    byKey.remove(calls[i].key)
                    announced.remove(calls[i].key)
                }
                calls.subList(0, drop).clear()
                byKey.clear()
                calls.forEachIndexed { i, c -> byKey[c.key] = i }
            }
        }
        done.forEach { callCompleted?.invoke(it) }
    }

    /** 지금까지 읽어 온 기록(최근 [max]건). 페이지를 다시 읽지 않는다. */
    fun snapshot(max: Int = 50): List<ApiCall> = synchronized(gate) { calls.takeLast(max) }

    /** [time](벽시계 ms) 이후에 시작된 호출. 먼저 페이지 기록을 새로 읽는다. */
    suspend fun since(time: Long, method: String? = null): List<ApiCall> {
        sync()
        return synchronized(gate) {
            calls.filter { it.startedAt >= time && (method == null || it.method.equals(method, ignoreCase = true)) }
        }
    }

    companion object {
        private const val MAX_CALLS = 300
        private val bodyMethods = setOf("wol/signal", "session/login", "session/logout", "session/update", "session/info")
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        fun isBodyMethod(method: String): Boolean = bodyMethods.any { it.equals(method, ignoreCase = true) }

        /** 응답(HTTP 상태)을 받지 못한 채 끊긴 요청인지. */
        fun isAborted(c: ApiCall): Boolean = c.failed && (c.errorText ?: "").contains("ERR_ABORTED", ignoreCase = true)
    }
}
