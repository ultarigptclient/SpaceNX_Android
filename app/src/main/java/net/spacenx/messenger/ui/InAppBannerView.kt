package net.spacenx.messenger.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * NHM-70: foreground 인앱 알림 배너
 *
 * WebView 위에 오버레이되는 커스텀 View.
 * 상단에서 슬라이드 다운 → 4초 후 자동 dismiss.
 * 클릭 시 콜백 실행 (해당 채팅방/쪽지 열기).
 */
class InAppBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var onClickCallback: (() -> Unit)? = null

    // UI
    private val cardView: LinearLayout
    private val titleView: TextView
    private val bodyView: TextView

    /** 현재 표시 중인 채널코드 (중복 방지) */
    var currentKey: String? = null
        private set

    init {
        visibility = View.GONE
        // 상단 여백 (status bar)
        val statusBarHeight = getStatusBarHeight()

        cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = createCardBackground()
            elevation = dp(8).toFloat()
        }

        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        bodyView = TextView(context).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        cardView.addView(titleView)
        cardView.addView(bodyView)

        val cardParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            setMargins(dp(12), statusBarHeight + dp(8), dp(12), 0)
        }
        addView(cardView, cardParams)

        // 클릭
        cardView.setOnClickListener {
            dismiss()
            onClickCallback?.invoke()
        }

        // 위로 스와이프 dismiss
        cardView.setOnTouchListener(SwipeDismissTouchListener())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAutoDismiss()
        cardView.animate().cancel()
        onClickCallback = null
    }

    fun show(
        title: String,
        body: String,
        key: String,
        autoDismissMs: Long = 4000L,
        onClick: (() -> Unit)? = null
    ) {
        // 같은 키의 배너가 이미 보이면 내용만 갱신
        cancelAutoDismiss()
        currentKey = key
        onClickCallback = onClick

        titleView.text = title
        bodyView.text = body.replace(Regex("<[^>]*>"), "").trim().ifEmpty { body }

        visibility = View.VISIBLE
        // 슬라이드 다운 애니메이션
        cardView.translationY = -dp(120).toFloat()
        ObjectAnimator.ofFloat(cardView, "translationY", -dp(120).toFloat(), 0f).apply {
            duration = 250
            start()
        }

        // 자동 dismiss
        dismissRunnable = Runnable { dismiss() }
        handler.postDelayed(dismissRunnable!!, autoDismissMs)
    }

    fun dismiss() {
        cancelAutoDismiss()
        ObjectAnimator.ofFloat(cardView, "translationY", 0f, -dp(120).toFloat()).apply {
            duration = 200
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    currentKey = null
                    onClickCallback = null
                }
            })
            start()
        }
    }

    private fun cancelAutoDismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#333333"))
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    /**
     * 위로 스와이프 시 배너 dismiss
     */
    private inner class SwipeDismissTouchListener : OnTouchListener {
        private var startY = 0f
        private var startTranslationY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startTranslationY = cardView.translationY
                    cancelAutoDismiss()
                    return false // allow click
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startY
                    if (dy < 0) { // 위로만
                        cardView.translationY = startTranslationY + dy
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (cardView.translationY < -dp(40)) {
                        dismiss()
                    } else {
                        ObjectAnimator.ofFloat(cardView, "translationY", cardView.translationY, 0f).apply {
                            duration = 150
                            start()
                        }
                        // re-schedule auto dismiss
                        dismissRunnable = Runnable { dismiss() }
                        handler.postDelayed(dismissRunnable!!, 3000L)
                    }
                }
            }
            return false
        }
    }
}
