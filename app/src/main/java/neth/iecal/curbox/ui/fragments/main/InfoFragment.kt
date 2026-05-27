package neth.iecal.curbox.ui.fragments.main

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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import neth.iecal.curbox.databinding.FragmentInfoBinding
import neth.iecal.curbox.ui.activity.ManageEssentialsActivity
import neth.iecal.curbox.utils.backup.BackupManager

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!

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
    }

    private fun setupClickListeners() {
        binding.btnSupport.setOnClickListener {
            // Replace with actual website/donation link
            openUrl("https://github.com/nethical6")
        }
        
        binding.btnDiscord.setOnClickListener {
            // Replace with actual Discord invite link
            openUrl("https://discord.com/invite/Vs9mwUtuCN")
        }

        binding.cardInstagram.setOnClickListener {
            openUrl("https://instagram.com/curbox.app")
        }

        binding.cardTiktok.setOnClickListener {
            openUrl("https://tiktok.com/@curbox.app")
        }

        binding.btnActionCrashLogs.setOnClickListener {
            showCrashLogs()
        }

        binding.btnExportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, BackupManager.suggestedFileName())
            }
            exportPicker.launch(intent)
        }

        binding.btnManageEssentials.setOnClickListener {
            startActivity(Intent(requireContext(), ManageEssentialsActivity::class.java))
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

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
