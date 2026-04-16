package net.spacenx.messenger.ui.call

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.launch

/**
 * 통화 오버레이 UI (Flutter CallOverlay 동일 패턴)
 *
 * 전체화면 모드: 비디오 그리드 + 컨트롤 바
 * PIP 모드: 드래그 가능한 미니 오버레이
 */
class CallOverlayFragment : Fragment() {

    companion object {
        private const val TAG = "CallOverlay"
        private const val ARG_CALL_TYPE = "callType"
        private const val ARG_IS_RECEIVER = "isReceiver"

        fun newInstance(callType: String, isReceiver: Boolean = false): CallOverlayFragment {
            return CallOverlayFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CALL_TYPE, callType)
                    putBoolean(ARG_IS_RECEIVER, isReceiver)
                }
            }
        }
    }

    var callService: CallService? = null
    var onEndCall: (() -> Unit)? = null

    private var minimized = false
    private var micOn = true
    private var camOn = true
    private var screenOn = false
    private var elapsedSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            updateTimerText()
            timerHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var rootLayout: FrameLayout

    // 전체화면 뷰
    private lateinit var fullView: LinearLayout
    private lateinit var timerText: TextView
    private lateinit var participantCountText: TextView
    private lateinit var videoContainer: FrameLayout

    // PIP 뷰
    private lateinit var pipView: LinearLayout
    private lateinit var pipTimerText: TextView
    private lateinit var pipInfoText: TextView

    private val videoRenderers = mutableListOf<Pair<SurfaceViewRenderer, VideoTrack>>()

    private val callType: String
        get() = arguments?.getString(ARG_CALL_TYPE) ?: "audio"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootLayout = FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        buildFullView()
        buildPipView()
        rootLayout.addView(fullView)
        rootLayout.addView(pipView)
        updateViewMode()

        camOn = callType == "video" && !(arguments?.getBoolean(ARG_IS_RECEIVER) ?: false)

        callService?.onCallEvent = { event, _ ->
            activity?.runOnUiThread {
                when (event) {
                    "disconnected", "allLeft" -> onEndCall?.invoke()
                    else -> refreshParticipants()
                }
            }
        }

        timerHandler.postDelayed(timerRunnable, 1000)
        refreshParticipants()
        return rootLayout
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerHandler.removeCallbacks(timerRunnable)
        callService?.onCallEvent = null
        releaseRenderers()
    }

    // ── UI 구축 ──

    private fun buildFullView() {
        val ctx = requireContext()
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        val bgColor = 0xFF1a1a2e.toInt()
        val textColor = 0xFFFFFFFF.toInt()
        val textDimColor = 0x88FFFFFF.toInt()

        fullView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 헤더
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val headerText = TextView(ctx).apply {
            text = if (callType == "video") "영상통화" else "음성통화"
            setTextColor(textColor)
            textSize = 15f
        }
        timerText = TextView(ctx).apply {
            text = "00:00"
            setTextColor(textDimColor)
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
        }
        participantCountText = TextView(ctx).apply {
            text = ""
            setTextColor(textDimColor)
            textSize = 12f
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(headerText)
        header.addView(timerText)
        header.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        header.addView(participantCountText)
        fullView.addView(header)

        // 비디오 컨테이너
        videoContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fullView.addView(videoContainer)

        // 컨트롤 바
        val controlBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }

        val btnMic = createControlButton(android.R.drawable.ic_btn_speak_now) { toggleMic() }
        val btnCam = createControlButton(android.R.drawable.ic_menu_camera) { toggleCam() }
        val btnScreen = createControlButton(android.R.drawable.ic_menu_share) { toggleScreen() }
        val btnMinimize = createControlButton(android.R.drawable.ic_menu_crop) {
            minimized = true; updateViewMode()
        }
        val btnEnd = createControlButton(android.R.drawable.ic_menu_close_clear_cancel, isDanger = true) {
            endCall()
        }

        for (btn in listOf(btnMic, btnCam, btnScreen, btnMinimize, btnEnd)) {
            controlBar.addView(btn, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(8); marginEnd = dp(8)
            })
        }
        fullView.addView(controlBar)
    }

    private fun buildPipView() {
        val ctx = requireContext()
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }

        pipView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1a1a2e.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(dp(160), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16); topMargin = dp(80)
            }
            elevation = 8f

            setOnClickListener {
                minimized = false; updateViewMode()
            }
        }

        pipInfoText = TextView(ctx).apply {
            text = "통화 중"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pipTimerText = TextView(ctx).apply {
            text = "00:00"
            setTextColor(0x88FFFFFF.toInt())
            textSize = 10f
        }
        pipView.addView(pipInfoText)
        pipView.addView(pipTimerText)

        // PIP 드래그
        var dX = 0f; var dY = 0f
        pipView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX; dY = v.y - event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    lp.leftMargin = (event.rawX + dX).toInt()
                    lp.topMargin = (event.rawY + dY).toInt()
                    v.layoutParams = lp; true
                }
                MotionEvent.ACTION_UP -> { v.performClick(); true }
                else -> false
            }
        }
    }

    private fun createControlButton(iconRes: Int, isDanger: Boolean = false, onClick: () -> Unit): ImageButton {
        val ctx = requireContext()
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        return ImageButton(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(0xFFFFFFFF.toInt())
            if (isDanger) setBackgroundColor(0xFFFF5252.toInt())
            else setBackgroundColor(0x40FFFFFF)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }
    }

    // ── 비디오 그리드 ──

    private fun refreshParticipants() {
        if (!isAdded) return
        val parts = callService?.participants ?: return
        participantCountText.text = "${parts.size}명"
        pipInfoText.text = "통화 중 · ${parts.size}명"

        videoContainer.removeAllViews()
        releaseRenderers()

        if (parts.isEmpty()) {
            videoContainer.addView(TextView(requireContext()).apply {
                text = "���결 중..."
                setTextColor(0x88FFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            return
        }

        val cols = if (parts.size <= 2) 1 else 2
        val ctx = requireContext()
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }

        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val rows = parts.chunked(cols)
        for (row in rows) {
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            for (participant in row) {
                val tile = createParticipantTile(participant)
                rowLayout.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            if (row.size < cols) {
                rowLayout.addView(View(ctx), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            grid.addView(rowLayout)
        }
        videoContainer.addView(grid)
    }

    private fun createParticipantTile(participant: Participant): FrameLayout {
        val ctx = requireContext()
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        val isLocal = participant is LocalParticipant

        val tile = FrameLayout(ctx).apply { setBackgroundColor(0xFF2d2d44.toInt()) }

        // videoTrackPublications는 List<Pair<TrackPublication, Track>>
        val videoTrack = participant.videoTrackPublications
            .firstOrNull { (pub, _) -> pub.source == Track.Source.CAMERA && pub.track != null }
            ?.second as? VideoTrack
        val screenTrack = participant.videoTrackPublications
            .firstOrNull { (pub, _) -> pub.source == Track.Source.SCREEN_SHARE && pub.track != null }
            ?.second as? VideoTrack
        val track = screenTrack ?: videoTrack

        if (track != null) {
            val renderer = SurfaceViewRenderer(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                if (isLocal) setMirror(true)
            }
            track.addRenderer(renderer)
            videoRenderers.add(renderer to track)
            tile.addView(renderer)
        } else {
            tile.addView(TextView(ctx).apply {
                text = "\uD83D\uDC64" // 👤
                textSize = 36f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }

        // 이름 라벨
        val name = participant.name?.ifEmpty { participant.identity?.value } ?: ""
        val label = "$name${if (isLocal) " (나)" else ""}"
        val isMuted = participant.audioTrackPublications
            .any { (pub, _) -> pub.source == Track.Source.MICROPHONE && pub.muted }

        tile.addView(TextView(ctx).apply {
            text = if (isMuted) "\uD83D\uDD07 $label" else label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setBackgroundColor(0x70000000)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(dp(6), 0, 0, dp(6))
            }
        })

        return tile
    }

    private fun releaseRenderers() {
        for ((renderer, track) in videoRenderers) {
            try {
                track.removeRenderer(renderer)
                renderer.release()
            } catch (_: Exception) {}
        }
        videoRenderers.clear()
    }

    // ── 컨트롤 ──

    private fun toggleMic() {
        micOn = !micOn
        lifecycleScope.launch { callService?.toggleMicrophone(micOn) }
    }

    private fun toggleCam() {
        camOn = !camOn
        lifecycleScope.launch { callService?.toggleCamera(camOn) }
    }

    private fun toggleScreen() {
        screenOn = !screenOn
        lifecycleScope.launch { callService?.toggleScreenShare(screenOn) }
    }

    private fun endCall() {
        onEndCall?.invoke()
    }

    private fun updateViewMode() {
        fullView.visibility = if (minimized) View.GONE else View.VISIBLE
        pipView.visibility = if (minimized) View.VISIBLE else View.GONE
        if (!minimized) refreshParticipants()
    }

    private fun updateTimerText() {
        val m = (elapsedSeconds / 60).toString().padStart(2, '0')
        val s = (elapsedSeconds % 60).toString().padStart(2, '0')
        val text = "$m:$s"
        timerText.text = text
        pipTimerText.text = text
    }
}
