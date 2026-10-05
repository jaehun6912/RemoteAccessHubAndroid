package io.github.jaehun6912.remoteaccesshub.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jaehun6912.remoteaccesshub.BuildConfig

/**
 * 앱의 유일한 화면. 화면 회전·다크 모드 전환으로 다시 만들어지지 않도록 매니페스트에서 설정 변경을 직접 받는다.
 * 그래도 시스템이 화면을 다시 만드는 경우(리소스 경로 변경 등)에는 제어부와 공유기 화면(WebView)을 새 화면에 이어 붙여
 * 로그인 세션을 잃지 않는다. 사용자가 [종료]하거나 화면이 완전히 닫힐 때만 정리한다.
 */
class MainActivity : ComponentActivity() {
    lateinit var controller: AppController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kept = retained
        if (kept != null) {
            controller = kept
            controller.attach(this)
            setContent { AppRoot(controller) }
            return
        }
        val factory = controllerFactory
        controller = if (factory != null) {
            factory(this)
        } else {
            val mock = BuildConfig.DEBUG && intent.getBooleanExtra(MainActivityExtras.MOCK, false)
            AppController(this, LaunchOptions(mock = mock))
        }
        retained = controller
        setContent { AppRoot(controller) }
        controller.start()
    }

    override fun onDestroy() {
        if (isFinishing || retained !== controller) {
            if (retained === controller) retained = null
            controller.dispose()
        } else {
            // 시스템이 화면만 다시 만드는 중: 제어부·WebView는 다음 화면에서 이어 쓴다.
            controller.detach()
        }
        super.onDestroy()
    }

    companion object {
        /** 계측 검사(자체검사)에서 가짜 실행기를 넣을 때만 쓴다. */
        @Volatile
        var controllerFactory: ((MainActivity) -> AppController)? = null

        /** 화면이 다시 만들어질 때 이어 쓸 제어부(프로세스가 살아 있는 동안만). */
        @Volatile
        private var retained: AppController? = null

        /** 다음 화면이 새 제어부로 시작하게 한다(모의 공유기 모드로 다시 열 때). */
        fun releaseRetained() {
            retained = null
        }
    }
}
