package ua.pp.prema.NeuralPocket.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import ua.pp.prema.NeuralPocket.R

data class PreflightResult(
    val canRun: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

class PreflightChecker(private val context: Context) {

    fun check(): PreflightResult {
        val errors   = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 1. ABI
        val abis = Build.SUPPORTED_ABIS.toList()
        if (!abis.contains("arm64-v8a")) {
            errors.add(context.getString(
                R.string.preflight_error_abi, abis.joinToString()
            ))
        }

        // 2. Android version
        if (Build.VERSION.SDK_INT < 28) {
            errors.add(context.getString(
                R.string.preflight_error_android, Build.VERSION.RELEASE
            ))
        }

        // 3. RAM
        val actMgr  = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { actMgr.getMemoryInfo(it) }
        val ramGb   = memInfo.totalMem.toDouble() / GiB
        val ramStr  = "%.1f GB".format(ramGb)
        when {
            ramGb < 3.0 -> errors.add(context.getString(R.string.preflight_error_ram, ramStr))
            ramGb < 6.0 -> warnings.add(context.getString(R.string.preflight_warn_ram, ramStr))
        }

        // 4. Free storage
        val stat    = StatFs(context.filesDir.absolutePath)
        val freeGb  = (stat.availableBlocksLong * stat.blockSizeLong).toDouble() / GiB
        val freeStr = "%.1f GB".format(freeGb)
        when {
            freeGb < 1.5 -> errors.add(context.getString(R.string.preflight_error_storage, freeStr))
            freeGb < 3.0 -> warnings.add(context.getString(R.string.preflight_warn_storage, freeStr))
        }

        Log.d(TAG, "ABIs=$abis  RAM=$ramStr  Free=$freeStr  SDK=${Build.VERSION.SDK_INT}")
        return PreflightResult(canRun = errors.isEmpty(), errors = errors, warnings = warnings)
    }

    companion object {
        private const val TAG = "PreflightChecker"
        private const val GiB = 1024.0 * 1024 * 1024
    }
}
