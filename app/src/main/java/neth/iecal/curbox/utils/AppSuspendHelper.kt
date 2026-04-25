package neth.iecal.curbox.utils

import android.content.Context
import rikka.shizuku.Shizuku
import neth.iecal.curbox.data.models.FocusBlockMode

object AppSuspendHelper {

    fun suspendApps(packages: List<String>) {
        executePmCommand(packages, "suspend")
    }

    fun unsuspendApps(packages: List<String>) {
        executePmCommand(packages, "unsuspend")
    }

    fun unsuspendAllApps(context: Context) {
        if (!isShizukuAvailable()) return
        Thread {
            try {
                val allPackages = context.packageManager.getInstalledPackages(0).map { it.packageName }
                executePmCommand(allPackages, "unsuspend")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun getPackagesToSuspend(
        context: Context,
        blockMode: FocusBlockMode,
        groupPackages: Set<String>,
        essentialPackages: Set<String>
    ): List<String> {
        return if (blockMode == FocusBlockMode.BLOCK_SELECTED) {
            groupPackages.filter { it !in essentialPackages }
        } else {
            val allPackages = context.packageManager.getInstalledPackages(0).map { it.packageName }
            allPackages.filter { it !in groupPackages && it !in essentialPackages }
        }
    }

    private fun executePmCommand(packages: List<String>, commandType: String) {
        if (!isShizukuAvailable() || packages.isEmpty()) return
        Thread {
            packages.chunked(40).forEach { chunk ->
                val command = "pm $commandType ${chunk.joinToString(" ")}"
                ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
                    override fun onCommandError(error: String) {
                        super.onCommandError(error)
                    }
                })
            }
        }.start()
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}
