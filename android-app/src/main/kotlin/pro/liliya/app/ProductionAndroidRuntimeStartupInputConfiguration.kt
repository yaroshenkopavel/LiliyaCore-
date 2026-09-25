package pro.liliya.app

import android.annotation.SuppressLint
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput

/**
 * Explicit process-local startup input ownership.
 *
 * Startup Input Configuration != Provisioning Authority.
 * Startup Input Configuration != License/Capability Authority.
 * Startup Input Configuration != DEK/Model Selection Policy.
 */
object ProductionAndroidRuntimeStartupInputConfiguration {
    // The installed input is process-scoped by design. install() canonicalizes any caller
    // Context to applicationContext before retaining it, so Activity/Service instances cannot
    // be leaked through this static owner.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var installed: AndroidProductRuntimeStartupRequestSourceInput? = null

    @Synchronized
    fun install(input: AndroidProductRuntimeStartupRequestSourceInput): Boolean {
        if (installed != null) return false
        installed = input.copy(context = input.context.applicationContext)
        return true
    }

    internal fun current(): AndroidProductRuntimeStartupRequestSourceInput? = installed

    @Synchronized
    internal fun clearForTests() {
        installed = null
    }
}
