package pro.liliya.app

import pro.liliya.core.licensetransport.LicenseHttpTransportConfig

/**
 * Explicit non-secret deployment inputs for fresh-install activation.
 *
 * Activation Profile != Activation Code.
 * Activation Profile != Entitlement.
 * Activation Profile != License Trust Discovery.
 */
internal data class ProductionAndroidActivationProfile(
    val transport: LicenseHttpTransportConfig,
    val productInputTemplate: ProductionAndroidFirstRunProductInputTemplate
)

internal fun interface ProductionAndroidActivationProfileSource {
    fun load(): ProductionAndroidActivationProfile
}

internal object ProductionAndroidActivationProfileSourceOwner {
    @Volatile
    private var source: ProductionAndroidActivationProfileSource? = null

    @Synchronized
    fun install(value: ProductionAndroidActivationProfileSource): Boolean {
        if (source != null) return false
        source = value
        return true
    }

    fun current(): ProductionAndroidActivationProfileSource? = source

    @Synchronized
    internal fun clearForTests() {
        source = null
    }
}
