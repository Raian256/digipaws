package neth.iecal.curbox.ui.fragments.main.reducers

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.GrayscaleFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.AntiUninstallFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment

class ReducersFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reducers, container, false)

        fun open(fragmentId: String): View.OnClickListener = View.OnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", fragmentId)
            }
            startActivity(intent)
        }

        view.findViewById<MaterialCardView>(R.id.card_app_blocker)
            .setOnClickListener(open(AppBlockerGroupsFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_reels_blocker)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_keyword_blocker)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_autofocus)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus.AutoFocusFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_reel_counter)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_grayscale)
            .setOnClickListener(open(GrayscaleFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_intents)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_view_blocker)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker.ViewBlockerFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_anti_uninstall)
            .setOnClickListener(open(AntiUninstallFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_anti_modifications)
            .setOnClickListener(open(AntiModificationsFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_logged_intents)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_blocked_apps_log)
            .setOnClickListener(open(neth.iecal.curbox.ui.fragments.main.reducers.analytics.BlockedAppsLogFragment.FRAGMENT_ID))

        return view
    }
}
