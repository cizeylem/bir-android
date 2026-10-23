package com.cizeylem.sample.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import com.cizeylem.bir.Cizeylem
import com.cizeylem.bir.Consent

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { Cizeylem.handleDeepLink(it.toString()) }
        if (savedInstanceState == null) GuidedStepSupportFragment.addAsRoot(this, SampleStep(), android.R.id.content)
    }
}

/** Tek ekran: üç rıza anahtarı (işaret kutusu), event düğmeleri, stats ve deep link denemesi. */
class SampleStep : GuidedStepSupportFragment() {
    private companion object {
        const val ANALYTICS = 1L; const val ATTRIBUTION = 2L; const val AD_SHARING = 3L
        const val TRACK = 10L; const val IDENTIFY = 11L; const val FLUSH = 12L; const val RESET = 13L
        const val DEEP_LINK = 14L; const val STATS = 15L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        "Çizeylem TV örneği", statsText(), "bir-android", null,
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        fun check(id: Long, title: String) = GuidedAction.Builder(requireContext())
            .id(id).title(title).checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).build()
        fun button(id: Long, title: String) = GuidedAction.Builder(requireContext()).id(id).title(title).build()
        actions.add(check(ANALYTICS, "Rıza: analytics"))
        actions.add(check(ATTRIBUTION, "Rıza: attribution"))
        actions.add(check(AD_SHARING, "Rıza: adSharing"))
        actions.add(button(TRACK, "track"))
        actions.add(button(IDENTIFY, "identify"))
        actions.add(button(FLUSH, "flush"))
        actions.add(button(RESET, "reset"))
        actions.add(button(DEEP_LINK, "deep link dene"))
        actions.add(button(STATS, "stats yenile"))
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ANALYTICS, ATTRIBUTION, AD_SHARING -> Cizeylem.setConsent(
                Consent(
                    analytics = isChecked(ANALYTICS),
                    attribution = isChecked(ATTRIBUTION),
                    adSharing = isChecked(AD_SHARING),
                ),
            )
            TRACK -> Cizeylem.track("ornek_dugme", mapOf("n" to 1))
            IDENTIFY -> Cizeylem.identify("ornek-kullanici")
            FLUSH -> Cizeylem.flush()
            RESET -> Cizeylem.reset()
            DEEP_LINK -> Cizeylem.handleDeepLink("cizeylemsample://ac?utm_source=ornek&utm_campaign=deneme&click_id=c1")
        }
        guidanceStylist.descriptionView?.text = statsText()
    }

    // findActionById: onGuidedActionClicked'te işaret kutusunun güncel durumu action.isChecked'te tutulur.
    private fun isChecked(id: Long) = findActionById(id)?.isChecked == true

    private fun statsText() = "${Cizeylem.stats()}  distinct_id=${Cizeylem.getDistinctId() ?: "yok"}"
}
