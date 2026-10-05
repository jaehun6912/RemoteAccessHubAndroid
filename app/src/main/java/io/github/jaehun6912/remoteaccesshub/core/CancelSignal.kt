package io.github.jaehun6912.remoteaccesshub.core

import kotlinx.coroutines.delay as coroutineDelay

/** 사용자가 [취소]를 눌렀거나 제한 시간이 지나 작업을 멈출 때 던진다(코루틴 취소와 구분한다). */
class OperationCanceledException(message: String = "작업이 취소되었습니다.") : Exception(message)

/**
 * 작업 취소 신호(Windows 버전의 CancellationToken 역할).
 * 코루틴 Job 취소를 쓰지 않는 이유: 취소돼도 작업이 "취소됨" 결과를 돌려줘야 화면 단계 표시를 정확히 바꿀 수 있다.
 */
class CancelSignal private constructor(private val parent: CancelSignal?, private val deadlineNanos: Long?) {
    constructor() : this(null, null)

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled || (deadlineNanos != null && System.nanoTime() - deadlineNanos >= 0) || parent?.isCancelled == true

    fun cancel() {
        cancelled = true
    }

    fun throwIfCancelled() {
        if (isCancelled) throw OperationCanceledException()
    }

    /** 이 신호가 취소되거나 [ms]가 지나면 취소되는 새 신호. */
    fun withTimeout(ms: Long): CancelSignal = CancelSignal(this, System.nanoTime() + ms * 1_000_000)

    /** 취소되면 곧바로(최대 50ms 안에) [OperationCanceledException]을 던지는 대기. */
    suspend fun delay(ms: Long) {
        var left = ms
        while (left > 0) {
            throwIfCancelled()
            val step = minOf(left, 50L)
            coroutineDelay(step)
            left -= step
        }
        throwIfCancelled()
    }

    companion object {
        /** 취소되지 않는 신호. */
        val None = CancelSignal()
    }
}

/** 제한 시간 계산용 단조 시계(ms). 벽시계가 바뀌어도 영향을 받지 않는다. */
object Mono {
    fun now(): Long = System.nanoTime() / 1_000_000
}
