package net.spacenx.messenger.ui.call

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

//2026-04-18 livekit 당장 미사용으로 의존성 제거함. 추후 주석 풀어야함
/*
 * 실제 구현은 git history 또는 io.livekit:livekit-android:2.5.0 의존성 복원 후 사용
 */
class CallOverlayFragment : Fragment() {

    companion object {
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = View(requireContext())
}
