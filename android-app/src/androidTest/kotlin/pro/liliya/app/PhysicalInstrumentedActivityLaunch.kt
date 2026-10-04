package pro.liliya.app

import android.app.Instrumentation
import android.content.Intent

internal object PhysicalInstrumentedActivityLaunch {
    fun launchLiliyaActivity(
        instrumentation: Instrumentation,
        timeoutMillis: Long = 15_000L
    ): LiliyaActivity {
        val launcherMonitor = instrumentation.addMonitor(
            LiliyaProvisioningActivity::class.java.name,
            null,
            false
        )
        val runtimeMonitor = instrumentation.addMonitor(
            LiliyaActivity::class.java.name,
            null,
            false
        )

        val shellLaunch = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.targetContext.packageName}/.LiliyaProvisioningActivity"
        )
        shellLaunch.close()

        val launcher = launcherMonitor.waitForActivityWithTimeout(timeoutMillis)
            as? LiliyaProvisioningActivity
            ?: error("physical instrumented launcher did not launch")

        instrumentation.runOnMainSync {
            launcher.startActivity(
                Intent(launcher, LiliyaActivity::class.java)
            )
            launcher.finish()
        }

        return runtimeMonitor.waitForActivityWithTimeout(timeoutMillis) as? LiliyaActivity
            ?: error("physical instrumented LiliyaActivity did not launch")
    }
}
