package io.github.jaehun6912.remoteaccesshub.services

import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/** TCP 연결만 열었다가 바로 닫는다(데이터는 보내지 않는다). 연결되면 포트가 응답하는 것으로 본다. */
class TcpPortProbe : PortProbe {
    override suspend fun isOpen(host: String, port: Int, timeoutMs: Long, signal: CancelSignal): Boolean {
        signal.throwIfCancelled()
        // 이름 풀이(DNS)가 연결 제한 시간과 따로 오래 걸릴 수 있어 전체를 제한 시간으로 감싼다.
        val result = withTimeoutOrNull(timeoutMs + 500) {
            withContext(Dispatchers.IO) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), timeoutMs.toInt().coerceAtLeast(1))
                        true
                    }
                } catch (_: Exception) {
                    false
                }
            }
        } ?: false
        signal.throwIfCancelled()
        return result
    }
}
