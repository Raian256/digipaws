package neth.iecal.curbox.ui.fragments.main.reducers

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.GrayscaleFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_uninstall.AntiUninstallFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment
import neth.iecal.curbox.utils.DataStoreManager

class ReducersFragment : Fragment() {

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }
    private var configLocked = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reducers, container, false)

        fun gated(fragmentId: String): View.OnClickListener = View.OnClickListener {
            if (configLocked) {
                Snackbar.make(view, R.string.anti_uninstall_config_locked, Snackbar.LENGTH_SHORT).show()
                return@OnClickListener
            }
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", fragmentId)
            }
            startActivity(intent)
        }

        view.findViewById<MaterialCardView>(R.id.card_app_blocker)
            .setOnClickListener(gated(AppBlockerGroupsFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_reels_blocker)
            .setOnClickListener(gated(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_keyword_blocker)
            .setOnClickListener(gated(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_autofocus)
            .setOnClickListener(gated(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus.AutoFocusFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_reel_counter)
            .setOnClickListener(gated(neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_grayscale)
            .setOnClickListener(gated(GrayscaleFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_intents)
            .setOnClickListener(gated(neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID))

        view.findViewById<MaterialCardView>(R.id.card_view_blocker)
            .setOnClickListener(gated(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker.ViewBlockerFragment.FRAGMENT_ID))

        // Anti-uninstall entry point is never gated — you always need access to remove it.
        view.findViewById<MaterialCardView>(R.id.card_anti_uninstall).setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AntiUninstallFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        // Logged intents is informational / read-only — not gated.
        view.findViewById<MaterialCardView>(R.id.card_logged_intents).setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collectLatest { settings ->
                    val cfg = settings.antiUninstallConfig
                    configLocked = cfg.isEnabled && cfg.blockConfigChanges
                }
            }
        }
    }
}
