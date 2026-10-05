package io.github.jaehun6912.remoteaccesshub.router

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 공유기 화면(WebView)을 담는 틀.
 *
 * 공유기 관리 화면은 휴대폰 너비에서 다른 배치(메뉴 숨김 등)로 바뀔 수 있다. 화면 판정과 WOL 대상 찾기 규칙은
 * PC 화면 배치(실기기 진단 파일)에 맞춰져 있으므로, 공유기 화면을 숨긴 채 자동으로 조작할 때는 [cssWidth]만큼 넓게 그린 뒤
 * 틀 크기에 맞게 줄여 둔다. 사용자에게 보여 줄 때(로그인, 직접 누르기)는 [cssWidth]=0으로 휴대폰 너비 그대로 그린다.
 */
@SuppressLint("ViewConstructor")
class WideWebFrame(context: Context) : FrameLayout(context) {
    /** 0이면 틀 크기 그대로, 0보다 크면 이 너비(CSS px)로 그린 뒤 줄인다. */
    var cssWidth: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** 넓게 그릴 때의 높이(CSS px). Windows 버전 공유기 화면 창과 비슷한 비율. */
    var cssHeight: Int = 760
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** 지금 넓게 그리는 중인지(틀이 [cssWidth]보다 좁을 때만 넓게 그린다). */
    var isWide: Boolean = false
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val child = getChildAt(0) ?: return
        val density = resources.displayMetrics.density
        val wantW = (cssWidth * density).roundToInt()
        isWide = cssWidth > 0 && w in 1 until wantW
        if (isWide) {
            val wantH = (cssHeight * density).roundToInt()
            child.measure(MeasureSpec.makeMeasureSpec(wantW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(wantH, MeasureSpec.EXACTLY))
        } else {
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        val scale = if (isWide && child.measuredWidth > 0) (right - left).toFloat() / child.measuredWidth else 1f
        child.scaleX = scale
        child.scaleY = scale
    }
}
