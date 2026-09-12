package pro.liliya.app

import java.io.File

/**
 * Explicit process-local ownership of the outer product dependencies required to acquire and
 * assemble one trusted first-run configuration.
 *
 * Configuration != License Issuance.
 * Configuration != License Verification.
 * Configuration != Trust-Key Discovery.
 * Configuration != Authority Minting.
 * Configuration != Model Selection.
 */
internal data class ProductionAndroidFirstRunConfiguration(
    val licenseAcquisition: ProductionAndroidFirstRunLicenseAcquisitionPort,
    val productInput: ProductionAndroidFirstRunProductInputPort
)

internal object ProductionAndroidFirstRunConfigurationOwner {
    @Volatile
    private var installed: ProductionAndroidFirstRunConfiguration? = null

    @Synchronized
    fun install(configuration: ProductionAndroidFirstRunConfiguration): Boolean {
        if (installed != null) return false
        installed = configuration
        return true
    }

    fun current(): ProductionAndroidFirstRunConfiguration? = installed

    @Synchronized
    fun clearForTests() {
        installed = null
    }
}

/**
 * Resolves only the explicitly installed outer-product first-run dependencies and delegates to
 * the accepted acquisition orchestrator. It does not discover trust, credentials, authority, or
 * models and does not persist those dependencies.
 */
internal object ProductionAndroidFirstRunConfiguredAcquisition {
    fun prepareAndInstall(
        localModelFile: File?
    ): ProductionAndroidFirstRunAcquisitionResult {
        val configuration = ProductionAndroidFirstRunConfigurationOwner.current()
            ?: return ProductionAndroidFirstRunAcquisitionResult.HostConfigurationRequired
        return ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = localModelFile,
            licenseAcquisition = configuration.licenseAcquisition,
            productInput = configuration.productInput
        )
    }
}
