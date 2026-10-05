package io.github.jaehun6912.remoteaccesshub

import android.content.Context
import io.github.jaehun6912.remoteaccesshub.core.AppLog

/** 배포 빌드에는 모의 공유기가 없다. */
object DebugTools {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun startMockRouter(context: Context, log: AppLog): MockRouterHandle? = null
}
