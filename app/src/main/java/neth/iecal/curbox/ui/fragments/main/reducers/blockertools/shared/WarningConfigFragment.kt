package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.BarcodeFormat
import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.databinding.FragmentWarningConfigBinding
import java.util.UUID

class WarningConfigFragment : Fragment() {

    private var _binding: FragmentWarningConfigBinding? = null
    private val binding get() = _binding!!

    private var initialConfig: AppBlockerWarningScreenConfig? = null
    private var currentQrMap = mutableMapOf<String, Long>()
    private var pendingQrDuration = -1L
    
    private val behaviorOptions = arrayOf(
        "Skip warning screen",
        "User selects unlock time",
        "Fixed unlock time",
        "Disable unlocking entirely",
        "Unlock requires QR/Barcode scanning",
        "Unlock requires typing a sentence",
        "Delayed unlock (wait off-screen)"
    )

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val duration = pendingQrDuration
            currentQrMap[result.contents] = duration
            updateQrList()
            Toast.makeText(requireContext(), "QR Code Saved Successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configStr = arguments?.getString(ARG_CONFIG)
        initialConfig = if (configStr != null) {
            Gson().fromJson(configStr, AppBlockerWarningScreenConfig::class.java)
        } else null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWarningConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInitialState()
        setupListeners()
    }

    private fun setupInitialState() {
        val config = initialConfig ?: AppBlockerWarningScreenConfig()
        
        currentQrMap = config.qrKeys.toMutableMap()
        updateQrList()

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, behaviorOptions)
        binding.unlockBehaviorDropdown.setAdapter(adapter)

        val initialIndex = when {
            config.isDelayedUnlockEnabled -> 6
            config.isTypingRequirementEnabled -> 5
            config.isQrUnlockRequirementEnabled -> 4
            config.isWarningDialogHidden -> 0
            config.isProceedDisabled -> 3
            config.isDynamicIntervalSettingAllowed -> 1
            else -> 2 // Fixed time
        }
        binding.unlockBehaviorDropdown.setText(behaviorOptions[initialIndex], false)
        binding.intentRequirementSwitch.isChecked = config.isIntentRequirementEnabled
        updateUiVisibility(initialIndex)

        binding.typingSentenceEdit.setText(config.typingSentence)

        // Setup arbitrary time inputs
        binding.fixedTimeEdit.setText((config.timeInterval / 60000).coerceAtLeast(1).toString())
        binding.proceedDelayEdit.setText(config.proceedDelayInSecs.coerceAtLeast(0).toString())

        binding.delayedFactorEdit.setText(config.delayedUnlockFactor.toString())
        binding.delayedMinWaitEdit.setText(config.delayedUnlockMinWaitMn.coerceAtLeast(0).toString())

        binding.proceedLimitSwitch.isChecked = config.proceedLimitEnabled
        binding.proceedLimitContainer.visibility = if (config.proceedLimitEnabled) View.VISIBLE else View.GONE

        binding.allowedProceedsSlider.value = config.allowedProceeds.toFloat().coerceIn(1f, 20f)
        binding.allowedProceedsTitle.text = "Allowed proceeds: ${binding.allowedProceedsSlider.value.toInt()}"

        binding.proceedWindowSlider.value = config.proceedsTimeWindowMn.toFloat().coerceIn(1f, 240f)
        binding.proceedWindowTitle.text = "Time window: ${binding.proceedWindowSlider.value.toInt()} mins"

        binding.warningMsgEdit.setText(config.message)
        binding.switchVibrateBrightness.isChecked = config.vibrateAndIncBrightness
    }

    private fun setupListeners() {
        binding.unlockBehaviorDropdown.setOnItemClickListener { _, _, position, _ ->
            updateUiVisibility(position, animate = true)
        }

        binding.proceedLimitSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.proceedLimitContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.allowedProceedsSlider.addOnChangeListener { _, value, _ ->
            binding.allowedProceedsTitle.text = "Allowed proceeds: ${value.toInt()}"
        }

        binding.proceedWindowSlider.addOnChangeListener { _, value, _ ->
            binding.proceedWindowTitle.text = "Time window: ${value.toInt()} mins"
        }

        binding.btnGenerateQr.setOnClickListener {
            showQrConfigDialog { duration ->
                val uniqueStr = UUID.randomUUID().toString()
                currentQrMap[uniqueStr] = duration
                updateQrList()
                
                try {
                    val barcodeEncoder = BarcodeEncoder()
                    val bitmap = barcodeEncoder.encodeBitmap(uniqueStr, BarcodeFormat.QR_CODE, 800, 800)
                    val imageView = ImageView(requireContext()).apply {
                        setImageBitmap(bitmap)
                        setPadding(32, 32, 32, 32)
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("QR/Barcode Generated")
                        .setMessage("Please save or print this QR/Barcode. You will need it to unlock.")
                        .setView(imageView)
                        .setPositiveButton("Done", null)
                        .setNeutralButton("Save to Gallery") { _, _ ->
                            saveImageToGallery(bitmap)
                        }
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to generate QR image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnScanExistingQr.setOnClickListener {
            showQrConfigDialog { duration ->
                pendingQrDuration = duration
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                options.setPrompt("Scan a QR code or barcode to unlock the blocker later. You can use almost any code, even one from a product box at home, so there’s no need to print a new one!")
                options.setCameraId(0)
                options.setBeepEnabled(false)
                options.setBarcodeImageEnabled(true)
                options.setCaptureActivity(neth.iecal.curbox.ui.activity.PortraitCaptureActivity::class.java)
                barcodeLauncher.launch(options)
            }
        }
        
        binding.saveconfigs.setOnClickListener {
            val behaviorStr = binding.unlockBehaviorDropdown.text.toString()
            val bIdx = behaviorOptions.indexOf(behaviorStr).coerceAtLeast(0)

            val isWarningDialogHidden = bIdx == 0
            val isDynamicIntervalSettingAllowed = bIdx == 1
            val isProceedDisabled = bIdx == 3
            val isQrUnlockRequirementEnabled = bIdx == 4
            val isTypingRequirementEnabled = bIdx == 5
            val isDelayedUnlockEnabled = bIdx == 6
            val supportsIntent = bIdx == 1 || bIdx == 2
            val isIntentRequirementEnabled = supportsIntent && binding.intentRequirementSwitch.isChecked

            // Wait factor must stay positive, otherwise the wait collapses to 0
            // and the floor (minimum wait) becomes the only thing enforced.
            val delayedFactor = binding.delayedFactorEdit.text.toString().toFloatOrNull()
                ?.takeIf { it > 0f } ?: 0.1f
            val delayedMinWait = binding.delayedMinWaitEdit.text.toString().toIntOrNull()
                ?.coerceAtLeast(0) ?: 10

            val config = AppBlockerWarningScreenConfig(
                message = binding.warningMsgEdit.text.toString(),
                timeInterval = (binding.fixedTimeEdit.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1) * 60_000,
                isDynamicIntervalSettingAllowed = isDynamicIntervalSettingAllowed,
                isProceedDisabled = isProceedDisabled,
                isWarningDialogHidden = isWarningDialogHidden,
                isQrUnlockRequirementEnabled = isQrUnlockRequirementEnabled,
                qrKeys = if (isQrUnlockRequirementEnabled) currentQrMap else mapOf(),
                isTypingRequirementEnabled = isTypingRequirementEnabled,
                typingSentence = binding.typingSentenceEdit.text.toString(),
                isIntentRequirementEnabled = isIntentRequirementEnabled,
                proceedDelayInSecs = binding.proceedDelayEdit.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
                vibrateAndIncBrightness = binding.switchVibrateBrightness.isChecked,
                proceedLimitEnabled = binding.proceedLimitSwitch.isChecked,
                allowedProceeds = binding.allowedProceedsSlider.value.toInt(),
                proceedsTimeWindowMn = binding.proceedWindowSlider.value.toInt(),
                isDelayedUnlockEnabled = isDelayedUnlockEnabled,
                delayedUnlockFactor = delayedFactor,
                delayedUnlockMinWaitMn = delayedMinWait
            )
            
            val requestKey = arguments?.getString(ARG_REQUEST_KEY) ?: RESULT_KEY
            parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                putString(RESULT_CONFIG, Gson().toJson(config))
            })
            parentFragmentManager.popBackStack()
        }
    }

    private fun saveImageToGallery(bitmap: android.graphics.Bitmap) {
        val context = requireContext()
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Unlock_QR_${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Curbox")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Failed to create MediaStore entry", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiVisibility(behaviorIndex: Int, animate: Boolean = false) {
        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.mainContentContainer,
                AutoTransition()
            )
        }

        binding.apply {
            timingContainer.visibility = if (behaviorIndex == 2 || behaviorIndex == 5) View.VISIBLE else View.GONE
            proceedDelayContainer.visibility = if (behaviorIndex in listOf(1, 2, 4, 5)) View.VISIBLE else View.GONE
            warningMsgLayout.visibility = if (behaviorIndex != 0) View.VISIBLE else View.GONE
            qrSetupContainer.visibility = if (behaviorIndex == 4) View.VISIBLE else View.GONE
            typingSetupContainer.visibility = if (behaviorIndex == 5) View.VISIBLE else View.GONE
            intentToggleContainer.visibility = if (behaviorIndex == 1 || behaviorIndex == 2) View.VISIBLE else View.GONE
            delayedUnlockContainer.visibility = if (behaviorIndex == 6) View.VISIBLE else View.GONE
        }
    }
    
    private fun showQrConfigDialog(onConfigured: (Long) -> Unit) {
        val pickerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        
        val switchDynamic = com.google.android.material.switchmaterial.SwitchMaterial(requireContext()).apply {
            text = "Use dynamic timing (User selects time during unlock)"
            isChecked = true
        }

        val pickerInnerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        
        val timeLabel = TextView(requireContext()).apply {
            text = "Fixed unlock duration: 5 mins"
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        val slider = com.google.android.material.slider.Slider(requireContext()).apply {
            valueFrom = 1f
            valueTo = 120f
            stepSize = 1f
            value = 5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addOnChangeListener { _, value, _ ->
                timeLabel.text = "Fixed unlock duration: ${value.toInt()} mins"
            }
        }

        pickerInnerContainer.addView(timeLabel)
        pickerInnerContainer.addView(slider)

        switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            pickerInnerContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        pickerContainer.addView(switchDynamic)
        pickerContainer.addView(pickerInnerContainer)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("QR/Barcode Timing")
            .setMessage("Configure the time behavior for this code before continuing.")
            .setView(pickerContainer)
            .setPositiveButton("Continue") { _, _ ->
                val duration = if (switchDynamic.isChecked) -1L else slider.value.toLong() * 60_000L
                onConfigured(duration)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateQrList() {
        binding.qrListContainer.removeAllViews()
        if (!currentQrMap.isEmpty()) {
            currentQrMap.forEach { (uuid, duration) ->
                val itemView = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 16, 0, 16)
                    weightSum = 1f
                }
                
                val infoText = TextView(requireContext()).apply {
                    val durationText = if (duration == -1L) "Dynamic time" else "${duration / 60000} mins"
                    text = "QR/Barcode - $durationText"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(4, 4, 4, 4)
                }
                
                val removeBtn = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Remove"
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        currentQrMap.remove(uuid)
                        updateQrList()
                    }
                }
                
                itemView.addView(infoText)
                itemView.addView(removeBtn)
                binding.qrListContainer.addView(itemView)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "warning_config_fragment"
        const val ARG_CONFIG = "arg_config"
        const val ARG_REQUEST_KEY = "arg_request_key"
        const val RESULT_KEY = "request_key_warning_config"
        const val RESULT_CONFIG = "result_config"

        fun newInstance(config: AppBlockerWarningScreenConfig, requestKey: String = RESULT_KEY): WarningConfigFragment {
            return WarningConfigFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONFIG, Gson().toJson(config))
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }
    }
}
