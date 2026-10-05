package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.router.ApiCall
import io.github.jaehun6912.remoteaccesshub.router.NetworkObserver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 페이지 안 관찰 스크립트(netwatch.js) 기록을 앱 쪽 기록으로 바꾸는 부분. */
class NetworkObserverTest {
    private fun observer(): NetworkObserver = NetworkObserver(AppLog(null)) { null }

    private fun call(id: Int, method: String, v: Long, extra: String = "") =
        "{\"id\":$id,\"t\":1000,\"sent\":true,\"method\":\"$method\",\"path\":\"http://r/cgi/service.cgi\",\"v\":$v$extra}"

    private fun batch(doc: String, ver: Long, vararg calls: String) = "{\"doc\":\"$doc\",\"ver\":$ver,\"calls\":[${calls.joinToString(",")}]}"

    @Test
    fun records_are_merged_and_announced_once_when_settled() {
        val o = observer()
        val seen = mutableListOf<ApiCall>()
        o.callCompleted = { seen.add(it) }
        o.merge(batch("d1", 1, call(1, "wol/signal", 1)))
        assertTrue(seen.isEmpty()) // 진행 중
        o.merge(batch("d1", 2, call(1, "wol/signal", 2, ",\"status\":200,\"done\":true")))
        assertTrue(seen.isEmpty()) // 판정이 필요한 호출은 본문 판정까지 기다린다
        o.merge(batch("d1", 3, call(1, "wol/signal", 3, ",\"status\":200,\"done\":true,\"checked\":true,\"ok\":true")))
        o.merge(batch("d1", 4, call(1, "wol/signal", 4, ",\"status\":200,\"done\":true,\"checked\":true,\"ok\":true")))
        assertEquals(1, seen.size)
        assertEquals(true, seen[0].resultOk)
        assertEquals(1, o.snapshot().size)
    }

    @Test
    fun params_are_masked_and_string_wrapped_results_are_accepted() {
        val o = observer()
        val inner = batch("d1", 1, call(1, "wol/signal", 1, ",\"params\":\"[\\\"00:11:22:33:44:55\\\"]\""))
        val wrapped = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        o.merge(wrapped)
        val c = o.snapshot().single()
        assertFalse(c.paramsMasked!!.contains("22:33:44"))
        assertTrue(c.paramsMasked!!.contains("00:11:**:**:**:55"))
    }

    @Test
    fun reloading_the_page_aborts_requests_left_in_flight() {
        val o = observer()
        val seen = mutableListOf<ApiCall>()
        o.callCompleted = { seen.add(it) }
        o.merge(batch("d1", 1, call(1, "wol/show", 1)))
        o.merge(batch("d2", 1, call(1, "session/info", 1)))
        val old = o.snapshot().first { it.key == "d1:1" }
        assertTrue(old.failed)
        assertTrue(NetworkObserver.isAborted(old))
        assertEquals(listOf("d1:1"), seen.map { it.key })
        assertEquals(2, o.snapshot().size)
    }

    @Test
    fun since_filters_by_time_and_method_after_syncing() = runBlocking {
        val net = FakeNet()
        val o = NetworkObserver(AppLog(null)) { net.json() }
        val start = System.currentTimeMillis()
        net.add("wol/show")
        net.add("wol/signal")
        assertEquals(1, o.since(start, "wol/signal").size)
        assertEquals(2, o.since(start).size)
        assertTrue(o.since(System.currentTimeMillis() + 60_000).isEmpty())
    }

    @Test
    fun only_page_cancelled_requests_count_as_aborted() {
        fun c(failed: Boolean, err: String?) = ApiCall("k", 0, "wol/signal", "", null, null, false, failed, err, null, null, null, false)
        assertTrue(NetworkObserver.isAborted(c(true, "net::ERR_ABORTED")))
        assertFalse(NetworkObserver.isAborted(c(true, "net::ERR_FAILED"))) // 진짜 네트워크 오류는 그대로 오류로 보고
        assertFalse(NetworkObserver.isAborted(c(false, "net::ERR_ABORTED")))
    }

    @Test
    fun broken_records_are_ignored() {
        val o = observer()
        o.merge("not json")
        o.merge(batch("d1", 1, "{\"id\":0,\"sent\":true,\"method\":\"x\",\"v\":1}", "{\"id\":2,\"sent\":false,\"method\":\"y\",\"v\":1}"))
        assertTrue(o.snapshot().isEmpty())
        assertNull(o.snapshot().firstOrNull())
    }
}
