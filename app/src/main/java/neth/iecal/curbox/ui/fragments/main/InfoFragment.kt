package neth.iecal.curbox.ui.fragments.main

import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.data.models.AntiModificationsConfig
import neth.iecal.curbox.databinding.FragmentInfoBinding
import neth.iecal.curbox.ui.activity.ManageEssentialsActivity
import neth.iecal.curbox.ui.fragments.main.reducers.anti_modifications.AntiModificationsGate
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.backup.BackupManager

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!

    private val dataStoreManager by lazy { DataStoreManager(requireContext().applicationContext) }
    private var antiMods = AntiModificationsConfig()

    /** True while the matching toggle is held on by an Anti-Modifications group. */
    private var locationFallbackLocked = false
    private var restrictGeofencingLocked = false
    private var delayedUnlockWeightLocked = false

    private val exportPicker: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let { uri -> runExport(uri) }
        }

    private val importPicker: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let { uri -> confirmAndRunImport(uri) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        setupGeofencingToggles()
        setupDelayedUnlockWeight()
        showBuildInfo()
    }

    /**
     * Global geofence toggles. Each reflects the current setting and, when an
     * Anti-Modifications group holds it, refuses changes with a snackbar instead
     * of applying them — mirroring how the per-group switches are gated.
     */
    private fun setupGeofencingToggles() {
        rebindLocationFallbackListener()
        rebindRestrictGeofencingListener()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collectLatest { settings ->
                    antiMods = settings.antiModificationsConfig

                    locationFallbackLocked = AntiModificationsGate.isGeofenceFailModeLocked(antiMods)
                    binding.switchLocationFallback.setOnCheckedChangeListener(null)
                    binding.switchLocationFallback.isChecked =
                        settings.blockGeofencedWhenLocationUnavailable
                    binding.switchLocationFallback.alpha = if (locationFallbackLocked) 0.5f else 1f
                    rebindLocationFallbackListener()

                    restrictGeofencingLocked =
                        AntiModificationsGate.isRestrictGeofencingLocked(antiMods)
                    binding.switchRestrictGeofencing.setOnCheckedChangeListener(null)
                    binding.switchRestrictGeofencing.isChecked = settings.restrictNewGeofencing
                    binding.switchRestrictGeofencing.alpha =
                        if (restrictGeofencingLocked) 0.5f else 1f
                    rebindRestrictGeofencingListener()

                    delayedUnlockWeightLocked =
                        AntiModificationsGate.isDelayedUnlockWeightLocked(antiMods)
                    // Don't clobber what the user is mid-typing.
                    if (!binding.delayedUnlockWeightEdit.hasFocus()) {
                        binding.delayedUnlockWeightEdit.setText(
                            formatWeight(settings.delayedUnlockOnScreenWeight)
                        )
                    }
                    binding.delayedUnlockWeightEdit.isEnabled = !delayedUnlockWeightLocked
                    binding.delayedUnlockWeightLayout.alpha =
                        if (delayedUnlockWeightLocked) 0.5f else 1f
                }
            }
        }
    }

    /**
     * The on-screen-wait weighting factor used by the delayed-unlock merge.
     * Commits on "Done" / focus loss, clamped to >= 1. When an Anti-Modifications
     * group locks it, the field is disabled so it can't be lowered.
     */
    private fun setupDelayedUnlockWeight() {
        binding.delayedUnlockWeightEdit.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commitDelayedUnlockWeight()
                v.clearFocus()
                true
            } else {
                false
            }
        }
        binding.delayedUnlockWeightEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitDelayedUnlockWeight()
        }
    }

    private fun commitDelayedUnlockWeight() {
        if (delayedUnlockWeightLocked) return
        val parsed = binding.delayedUnlockWeightEdit.text?.toString()?.toFloatOrNull()
        val value = (parsed ?: 1f).coerceAtLeast(1f)
        binding.delayedUnlockWeightEdit.setText(formatWeight(value))
        viewLifecycleOwner.lifecycleScope.launch {
            dataStoreManager.updateDelayedUnlockOnScreenWeight(value)
            requireContext().sendBroadcast(Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER))
        }
    }

    /** Drops a trailing ".0" so whole numbers read cleanly (e.g. "2" not "2.0"). */
    private fun formatWeight(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    private fun rebindLocationFallbackListener() {
        binding.switchLocationFallback.setOnCheckedChangeListener { buttonView, isChecked ->
            if (locationFallbackLocked) {
                buttonView.setOnCheckedChangeListener(null)
                buttonView.isChecked = !isChecked
                AntiModificationsGate.refuseWithSnackbar(buttonView)
                rebindLocationFallbackListener()
                return@setOnCheckedChangeListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                dataStoreManager.updateBlockGeofencedWhenLocationUnavailable(isChecked)
                requireContext().sendBroadcast(Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER))
            }
        }
    }

    private fun rebindRestrictGeofencingListener() {
        binding.switchRestrictGeofencing.setOnCheckedChangeListener { buttonView, isChecked ->
            if (restrictGeofencingLocked) {
                buttonView.setOnCheckedChangeListener(null)
                buttonView.isChecked = !isChecked
                AntiModificationsGate.refuseWithSnackbar(buttonView)
                rebindRestrictGeofencingListener()
                return@setOnCheckedChangeListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                dataStoreManager.updateRestrictNewGeofencing(isChecked)
            }
        }
    }

    private fun showBuildInfo() {
        binding.tvBuildInfo.text = getString(
            R.string.build_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.GIT_COMMIT
        )
    }

    private fun setupClickListeners() {
        binding.btnManageEssentials.setOnClickListener {
            startActivity(Intent(requireContext(), ManageEssentialsActivity::class.java))
        }

        binding.btnActionCrashLogs.setOnClickListener {
            showCrashLogs()
        }

        binding.btnRefreshLocation.setOnClickListener {
            requestLocationRefresh()
        }

        binding.btnExportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, BackupManager.suggestedFileName())
            }
            exportPicker.launch(intent)
        }

        binding.btnImportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
            }
            importPicker.launch(intent)
        }
    }

    private fun runExport(uri: Uri) {
        lifecycleScope.launch {
            BackupManager.export(requireContext(), uri)
                .onSuccess {
                    Toast.makeText(requireContext(), getString(R.string.backup_exported), Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.backup_export_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun confirmAndRunImport(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_import_confirm_title)
            .setMessage(R.string.backup_import_confirm_message)
            .setPositiveButton(R.string.import_backup) { _, _ ->
                lifecycleScope.launch {
                    BackupManager.import(requireContext(), uri)
                        .onSuccess { summary ->
                            val msg = if (summary.skippedIncompatible.isEmpty()) {
                                getString(R.string.backup_imported)
                            } else {
                                getString(
                                    R.string.backup_import_partial_skipped,
                                    summary.skippedIncompatible.joinToString()
                                )
                            }
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                        .onFailure { e ->
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.backup_import_failed, e.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestLocationRefresh() {
        val intent = Intent(AppBlocker.INTENT_ACTION_REFRESH_GEOFENCE_LOCATION)
            .setPackage(requireContext().packageName)
        requireContext().sendBroadcast(intent)
        Toast.makeText(
            requireContext(),
            getString(R.string.refresh_location_requested),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showCrashLogs() {
        val logFile = File(requireContext().filesDir, "crash_log.txt")
        val content = if (logFile.exists()) {
            try {
                val text = logFile.readText()
                if (text.isBlank()) "No crash logs available." else text
            } catch (e: Exception) {
                "Error reading crash logs."
            }
        } else {
            "No crash logs available."
        }
        
        val displayContent = if (content.length > 50000) {
            "...${content.takeLast(50000)}"
        } else {
            content
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Crash Logs")
            .setMessage(displayContent)
            .setPositiveButton("Share") { _, _ ->
                shareCrashLogs(content)
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                if (logFile.exists() && logFile.delete()) {
                    Toast.makeText(requireContext(), getString(R.string.crash_logs_cleared), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareCrashLogs(content: String) {
        if (content == "No crash logs available." || content == "Error reading crash logs.") run {
            Toast.makeText(requireContext(), getString(R.string.nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Curbox Crash Logs")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "Share Crash Logs"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
