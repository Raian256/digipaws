package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.blockers.AntiUninstallBlocker
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.viewblocker.ElementPickerNotification
import neth.iecal.curbox.blockers.viewblocker.ViewBlocker
import neth.iecal.curbox.receivers.PackageInstallReceiver
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker.ViewBlockerFragment

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val appBlocker: AppBlocker = AppBlocker()
    private val focusModeBlocker = FocusModeBlocker()
    private val reelBlocker = ReelBlocker()
    private var keywordBlocker = KeywordBlocker()
    private val viewBlocker = ViewBlocker()
    private val antiUninstallBlocker = AntiUninstallBlocker()
    private var pickerNotification: ElementPickerNotification? = null

    private val packageInstallReceiver = PackageInstallReceiver()

    private val pickerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ViewBlockerFragment.INTENT_ACTION_SHOW_PICKER_NOTIFICATION -> {
                    pickerNotification?.showNotification()
                }
                ElementPickerNotification.ACTION_START_PICKER -> {
                    val picker = viewBlocker.elementPicker
                    if (picker != null && !picker.isActive) {
                        picker.show()
                        pickerNotification?.showPickerActiveNotification()
                    }
                }
                ElementPickerNotification.ACTION_STOP_PICKER -> {
                    viewBlocker.elementPicker?.hide()
                    pickerNotification?.cancelNotification()
                }
            }
        }
    }


    private var grayScaleFilter = GrayScaleFilter()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val eventChannel = Channel<AccessibilityEvent>(Channel.CONFLATED) { droppedEvent ->
        droppedEvent.recycle()
    }

    private lateinit var crashLogger: CrashLogger

    override fun onCreate() {
        super.onCreate()
        crashLogger = CrashLogger(this)
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
        } catch (e: Exception) {
            Log.e("Shizuku", "Failed to bind Shizuku in non-provider process", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        try {
            appBlocker.doAppBlockerCheck(event)
            grayScaleFilter.doGrayscaleCheck(event)
            focusModeBlocker.doFocusModeCheck(event)
            antiUninstallBlocker.doAntiUninstallCheck(event)
        } catch (t: Throwable) {
            Log.e("error",t.message.toString())
            crashLogger.logNonFatalError(Exception(t))
        }

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            for (event in eventChannel) {
                try {
                    reelBlocker.doViewBlockerCheck(event)
                    keywordBlocker.checkIfUserGettingFreaky(event)
                    viewBlocker.doViewBlockerCheck(event)
                } catch (t: Throwable) {
                    // Don't log normal coroutine cancellations as crashes
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("Blocker", "Background worker error", t)
                } finally {
                    event.recycle()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        appBlocker.setupAppBlocker(this)
        focusModeBlocker.setupFocusMode(this)
        reelBlocker.setupBlocker(this)
        keywordBlocker.setupBlocker(this)
        viewBlocker.setupBlocker(this)
        viewBlocker.setupElementPicker()
        pickerNotification = ElementPickerNotification(this)
        grayScaleFilter.setup(this)
        antiUninstallBlocker.setupBlocker(this)

        focusModeBlocker.setupReceivers()
        appBlocker.setupReceivers()
        reelBlocker.setupReceivers()
        keywordBlocker.setupReceivers()
        grayScaleFilter.setupReceivers()
        viewBlocker.setupReceivers()

        val pickerFilter = IntentFilter().apply {
            addAction(ViewBlockerFragment.INTENT_ACTION_SHOW_PICKER_NOTIFICATION)
            addAction(ElementPickerNotification.ACTION_START_PICKER)
            addAction(ElementPickerNotification.ACTION_STOP_PICKER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pickerReceiver, pickerFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pickerReceiver, pickerFilter)
        }

        // PACKAGE_ADDED is not in the API 26+ implicit-broadcast exemption list,
        // so a manifest receiver is never delivered. Register at runtime instead.
        val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageInstallReceiver, packageFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageInstallReceiver, packageFilter)
        }

        startBackgroundWorker()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {

            focusModeBlocker.removeReceivers()
            reelBlocker.removeReceivers()
            appBlocker.onDestroy()
            keywordBlocker.removeReceivers()
            grayScaleFilter.unregisterReceivers()
            viewBlocker.removeReceivers()
            try { unregisterReceiver(pickerReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(packageInstallReceiver) } catch (_: Exception) {}
            pickerNotification?.cancelNotification()

            eventChannel.close()
            serviceScope.cancel()
        }catch (_: Exception){}
    }
}