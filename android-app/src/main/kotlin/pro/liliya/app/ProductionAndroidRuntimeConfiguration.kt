package pro.liliya.app

/**
 * Explicit process-local product configuration for the Android runtime wiring boundary.
 *
 * Configuration != Provisioning.
 * Configuration != License/Authority ownership.
 * Configuration != Model/DEK selection policy.
 */
object ProductionAndroidRuntimeConfiguration {
    @Volatile
    private var installed: ProductionAndroidRuntimeWiringSources? = null

    @Synchronized
    fun install(sources: ProductionAndroidRuntimeWiringSources): Boolean {
        if (installed != null) return false
        installed = sources
        return true
    }

    internal fun current(): ProductionAndroidRuntimeWiringSources? = installed

    @Synchronized
    internal fun clearForTests() {
        installed = null
    }
}

internal object ProductionAndroidRuntimeConfigurationInstaller {
    fun ensureTrustedWiringInstalled(): Boolean {
        if (ProductionAndroidAppTrustedWiring.current() != null) return true
        val sources = ProductionAndroidRuntimeConfiguration.current() ?: return false
        return ProductionAndroidRuntimeWiring.install(sources) ||
            ProductionAndroidAppTrustedWiring.current() != null
    }
}
