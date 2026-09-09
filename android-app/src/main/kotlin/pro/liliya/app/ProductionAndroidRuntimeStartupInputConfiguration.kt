package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput

/**
 * Explicit process-local startup input ownership.
 *
 * Startup Input Configuration != Provisioning Authority.
 * Startup Input Configuration != License/Capability Authority.
 * Startup Input Configuration != DEK/Model Selection Policy.
 */
object ProductionAndroidRuntimeStartupInputConfiguration {
    @Volatile
    private var installed: AndroidProductRuntimeStartupRequestSourceInput? = null

    @Synchronized
    fun install(input: AndroidProductRuntimeStartupRequestSourceInput): Boolean {
        if (installed != null) return false
        installed = input
        return true
    }

    internal fun current(): AndroidProductRuntimeStartupRequestSourceInput? = installed

    @Synchronized
    internal fun clearForTests() {
        installed = null
    }
}
