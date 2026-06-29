package neth.iecal.curbox.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.databinding.DialogWarningOverlayBinding
import neth.iecal.curbox.services.AppBlockerService
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.core.content.edit
import neth.iecal.curbox.anti_stimulants.MindfulMessageTracker

class WarningActivity : AppCompatActivity() {

    private var proceedTimer: CountDownTimer? = null
    private var dialog: AlertDialog? = null

    private var vibrator: Vibrator? = null

    private var isQrScanned = false
    private var scannedValidDuration = -1L

    private lateinit var binding: DialogWarningOverlayBinding
    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents == null) {
            Toast.makeText(this@WarningActivity, "Cancelled", Toast.LENGTH_LONG).show()
        } else {
            val warningScreenConfig = Gson().fromJson<AppBlockerWarningScreenConfig>(
                intent.getStringExtra("warning_config"),
                AppBlockerWarningScreenConfig::class.java
            )
            if (warningScreenConfig.qrKeys.containsKey(result.contents)) {
                isQrScanned = true
                scannedValidDuration = warningScreenConfig.qrKeys[result.contents] ?: -1L
                
                binding.btnProceed.isEnabled = true
                binding.btnProceed.setText(R.string.proceed)
                
                if (scannedValidDuration == -1L) {
                    binding.minsPicker.visibility = View.VISIBLE
                } else {
                    binding.minsPicker.visibility = View.GONE
                }
            } else {
                 Toast.makeText(this@WarningActivity, "Invalid QR Code - Pattern does not match", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getIntExtra("mode", 0)

val warningScreenConfig = Gson().fromJson<AppBlockerWarningScreenConfig>(
            intent.getStringExtra("warning_config"),
            AppBlockerWarningScreenConfig::class.java
        )

        val targetId = intent.getStringExtra("result_id") ?: ""
        var isProceedLimitExceeded = false
        var timeUntilNextProceedMn = 0L

        if (warningScreenConfig.proceedLimitEnabled && targetId.isNotEmpty()) {
            val limitPrefs = getSharedPreferences("proceed_limits", Context.MODE_PRIVATE)
            val historyString = limitPrefs.getString("proceeds_$targetId", "") ?: ""
            val history = historyString.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
            
            val nowTime = System.currentTimeMillis()
            val windowMillis = warningScreenConfig.proceedsTimeWindowMn * 60_000L
            val validHistory = history.filter { nowTime - it < windowMillis }
            
            if (validHistory.size >= warningScreenConfig.allowedProceeds) {
                isProceedLimitExceeded = true
                val oldestProceed = validHistory.minOrNull() ?: nowTime
                val expirationTime = oldestProceed + windowMillis
                timeUntilNextProceedMn = (expirationTime - nowTime + 59_999) / 60_000L
            }
        }

        if (warningScreenConfig.vibrateAndIncBrightness) {
            val layoutParams = window.attributes
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            window.attributes = layoutParams

            triggerRandomizedVibration(maxOf(3000L, (warningScreenConfig.proceedDelayInSecs / 2) * 1000L))
        }

        binding = DialogWarningOverlayBinding.inflate(layoutInflater)
        val isHomePressRequested = intent.getBooleanExtra("is_press_home", false)
        binding.minsPicker.setValue(3)
        binding.minsPicker.minValue = 2
        val isDialogCancelable =
            mode != Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested

        if (warningScreenConfig.isProceedDisabled || isProceedLimitExceeded) {
            binding.btnProceed.visibility = View.GONE
            if (isProceedLimitExceeded) {
                binding.proceedSeconds.visibility = View.VISIBLE
                binding.proceedSeconds.text = "Proceed limit of ${warningScreenConfig.allowedProceeds} per ${warningScreenConfig.proceedsTimeWindowMn} minutes has been reached. Try again in $timeUntilNextProceedMn minutes."
            } else {
                binding.proceedSeconds.visibility = View.GONE
            }

        } else {
            var timerFinished = false

            fun onTimerFinished() {
                timerFinished = true
                binding.proceedSeconds.visibility = View.GONE
                binding.btnProceed.let { button ->
                    if (!warningScreenConfig.isQrUnlockRequirementEnabled && warningScreenConfig.isDynamicIntervalSettingAllowed) {
                        binding.minsPicker.visibility = View.VISIBLE
                    }

                    if (warningScreenConfig.isIntentRequirementEnabled) {
                        // Intent input was shown upfront and triggered the timer.
                        // Only enable proceed if the user hasn't cleared the field while waiting.
                        button.setText(R.string.proceed)
                        button.isEnabled = binding.intentInputEdit.text?.toString()?.trim()?.isNotEmpty() == true
                    } else if (warningScreenConfig.isTypingRequirementEnabled) {
                        binding.typingTargetSentence.visibility = View.VISIBLE
                        binding.typingTargetSentence.text = "\"${warningScreenConfig.typingSentence}\""
                        binding.typingInputLayout.visibility = View.VISIBLE
                        button.isEnabled = false
                        button.setText(R.string.proceed)

                        binding.typingInputEdit.addTextChangedListener(object: android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: android.text.Editable?) {
                                button.isEnabled = s?.toString() == warningScreenConfig.typingSentence
                            }
                        })
                    } else if (warningScreenConfig.isQrUnlockRequirementEnabled && !isQrScanned) {
                        button.text = "Scan QR Code"
                        button.isEnabled = true
                    } else {
                        button.setText(R.string.proceed)
                        button.isEnabled = true
                    }
                }
            }

            fun startProceedTimer() {
                proceedTimer = object : CountDownTimer(warningScreenConfig.proceedDelayInSecs * 1000L, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        binding.proceedSeconds.text =
                            getString(R.string.proceed_in, millisUntilFinished / 1000)
                    }
                    override fun onFinish() {
                        onTimerFinished()
                    }
                }.start()
            }

            if (warningScreenConfig.isIntentRequirementEnabled) {
                // Intent first, then wait: input is visible immediately,
                // and the countdown only begins once the user starts typing.
                binding.intentInputLayout.visibility = View.VISIBLE
                binding.proceedSeconds.text = getString(R.string.proceed_state_intent_first)
                binding.btnProceed.isEnabled = false
                binding.btnProceed.setText(R.string.proceed)

                var timerStarted = false
                binding.intentInputEdit.addTextChangedListener(object: android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val intentText = s?.toString()?.trim() ?: ""
                        if (!timerStarted && intentText.isNotEmpty()) {
                            timerStarted = true
                            startProceedTimer()
                        }
                        if (timerFinished) {
                            binding.btnProceed.isEnabled = intentText.isNotEmpty()
                        }
                    }
                })
            } else {
                startProceedTimer()
            }
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(isDialogCancelable)
            .setOnCancelListener {
                finishAffinity()
            }
            .show()

        val targetLabel = if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER) {
            resolveAppLabel(targetId)
        } else {
            "this content"
        }

        binding.warningTitle.text = when (mode) {
            Constants.WARNING_SCREEN_MODE_APP_BLOCKER -> "$targetLabel is blocked"
            Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER -> "Short-form content is blocked"
            else -> "Access blocked"
        }

        binding.warningDetails.text =
            buildWarningDetails(mode, targetLabel, warningScreenConfig, isProceedLimitExceeded)

        val customMessage = warningScreenConfig.message.trim()
        if (customMessage.isEmpty()) {
            binding.warningMsg.visibility = View.GONE
        } else {
            binding.warningMsg.visibility = View.VISIBLE
            binding.warningMsg.text = customMessage
        }

        binding.minsPicker.setValue(warningScreenConfig.timeInterval / 60000)

        binding.btnCancel.setOnClickListener {
            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested) {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            dialog?.dismiss()
            finishAffinity()
        }

        binding.btnProceed.setOnClickListener {
            if (warningScreenConfig.isQrUnlockRequirementEnabled && !isQrScanned) {
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                options.setPrompt("Scan a QR Code to unlock")
                options.setCameraId(0) // Use a specific camera of the device
                options.setBeepEnabled(false)
                options.setBarcodeImageEnabled(true)
                options.setCaptureActivity(neth.iecal.curbox.ui.activity.PortraitCaptureActivity::class.java)
                barcodeLauncher.launch(options)
                return@setOnClickListener
            }

            if (warningScreenConfig.proceedLimitEnabled && targetId.isNotEmpty()) {
                val limitPrefs = getSharedPreferences("proceed_limits", Context.MODE_PRIVATE)
                val historyString = limitPrefs.getString("proceeds_$targetId", "") ?: ""
                val history = historyString.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
                val nowTime = System.currentTimeMillis()
                val windowMillis = warningScreenConfig.proceedsTimeWindowMn * 60_000L
                val validHistory = history.filter { nowTime - it < windowMillis }.toMutableList()
                
                validHistory.add(nowTime)
                limitPrefs.edit { putString("proceeds_$targetId", validHistory.joinToString(",")) }
            }

            if (warningScreenConfig.isIntentRequirementEnabled) {
                val intentText = binding.intentInputEdit.text.toString().trim()
                val pkg = targetId
                val time = binding.minsPicker.getValue() * 60_000L
                
                CoroutineScope(Dispatchers.IO).launch {
                    val log = neth.iecal.curbox.data.db.IntentLogEntity(
                        timestamp = System.currentTimeMillis(),
                        packageName = pkg,
                        intentText = intentText,
                        unlockedDurationMs = time
                    )
                    neth.iecal.curbox.data.db.AppDatabase.getInstance(this@WarningActivity).intentLogDao().insert(log)
                }

                val broadcastIntent = Intent(MindfulMessageTracker.ADD_NEW_INTENT)
                broadcastIntent.putExtra("package_name", pkg)
                broadcastIntent.putExtra("intent_text", intentText)
                broadcastIntent.putExtra("duration_ms", time)
                sendBroadcast(broadcastIntent)
            }

            if (mode == Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER) {
                intent.getStringExtra("result_id")
                    ?.let { it1 ->
                        val finalTime = if (warningScreenConfig.isQrUnlockRequirementEnabled && scannedValidDuration != -1L) {
                            (scannedValidDuration / 60000).toInt()
                        } else {
                            binding.minsPicker.getValue()
                        }
                        sendRefreshRequest(
                            it1,
                            ReelBlocker.INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN,
                            finalTime
                        )
                    }
            }

            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER) {
                intent.getStringExtra("result_id")
                    ?.let { it1 ->
                         val finalTime = if (warningScreenConfig.isQrUnlockRequirementEnabled && scannedValidDuration != -1L) {
                            (scannedValidDuration / 60000).toInt()
                        } else {
                            binding.minsPicker.getValue()
                        }
                        sendRefreshRequest(
                            it1,
                            AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN,
                            finalTime
                        )
                        val intent = packageManager.getLaunchIntentForPackage(it1)
                        if (intent != null) {
                            startActivity(intent)
                        }
                    }
            }

            dialog?.dismiss()
            finishAffinity()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proceedTimer?.cancel()
        vibrator?.cancel()
        dialog?.dismiss()
    }

    private fun resolveAppLabel(packageName: String): String {
        if (packageName.isEmpty()) return "this app"
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Builds the human-readable explanation shown on the warning screen:
     * why access was stopped, what proceeding will do, and any extra
     * requirements (intent, QR, typing, proceed limits) the user must meet.
     */
    private fun buildWarningDetails(
        mode: Int,
        targetLabel: String,
        config: AppBlockerWarningScreenConfig,
        isProceedLimitExceeded: Boolean
    ): String {
        val lines = mutableListOf<String>()

        lines += when (mode) {
            Constants.WARNING_SCREEN_MODE_APP_BLOCKER ->
                "You opened $targetLabel, which you've chosen to restrict."
            Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER ->
                "You reached a short-form video feed you've chosen to restrict."
            else -> "This was opened in a restricted context."
        }

        when {
            config.isProceedDisabled ->
                lines += "Bypassing is disabled here — close this and step away."
            isProceedLimitExceeded -> {
                // The remaining-time message is shown separately in proceedSeconds.
            }
            config.isDynamicIntervalSettingAllowed ->
                lines += "If you proceed, choose below how long to unlock for."
            else ->
                lines += "Proceeding unlocks access for ${config.timeInterval / 60000} min."
        }

        if (!config.isProceedDisabled && !isProceedLimitExceeded) {
            if (config.isIntentRequirementEnabled) {
                lines += "You must first state why you need access; the wait only starts after that."
            }
            if (config.isQrUnlockRequirementEnabled) {
                lines += "You must scan your unlock QR/barcode to continue."
            }
            if (config.isTypingRequirementEnabled) {
                lines += "You must type the required sentence exactly to continue."
            }
            if (config.proceedLimitEnabled) {
                lines += "Limited to ${config.allowedProceeds} unlocks per ${config.proceedsTimeWindowMn} min."
            }
        }

        return lines.joinToString("\n\n")
    }

    private fun sendRefreshRequest(id: String, action: String, time: Int) {
        val intent = Intent(action)
        intent.putExtra("result_id", id)
        intent.putExtra("selected_time", time * 60_000)
        sendBroadcast(intent)
    }

    /**
     * Triggers a jagged, unpredictable vibration waveform.
     * The lack of a steady rhythm prevents habituation and breaks focus.
     */
    private fun triggerRandomizedVibration(durationMillis: Long) {
        // Initialize the class-level vibrator variable
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        vibrator?.let { currentVibrator ->
            if (currentVibrator.hasVibrator()) {
                val patternList = mutableListOf<Long>()
                patternList.add(0L) // Start immediately (0ms initial delay)

                var elapsedTime = 0L

                while (elapsedTime < durationMillis) {
                    val vibrateDuration = Random.nextLong(40, 250)
                    val pauseDuration = Random.nextLong(40, 150)

                    if (elapsedTime + vibrateDuration >= durationMillis) {
                        patternList.add(durationMillis - elapsedTime) // Cap exactly at duration
                        break
                    }
                    patternList.add(vibrateDuration)
                    elapsedTime += vibrateDuration

                    if (elapsedTime + pauseDuration >= durationMillis) {
                        patternList.add(durationMillis - elapsedTime) // Cap exactly at duration
                        break
                    }
                    patternList.add(pauseDuration)
                    elapsedTime += pauseDuration
                }

                val jaggedPattern = patternList.toLongArray()

                currentVibrator.vibrate(VibrationEffect.createWaveform(jaggedPattern, -1))
            }
        }
    }
}