package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceFailure

sealed interface ProductionAndroidAppStartupOutcome {
    data object ConfigurationRequired : ProductionAndroidAppStartupOutcome
    data class Runtime(
        val state: ProductionAndroidAppRuntimeState
    ) : ProductionAndroidAppStartupOutcome
    data class SourceRejected(
        val reason: AndroidProductRuntimeStartupRequestSourceFailure
    ) : ProductionAndroidAppStartupOutcome
    data class ProvisioningRejected(
        val result: AndroidProductRuntimeStartupProvisioningResult
    ) : ProductionAndroidAppStartupOutcome
}

internal fun interface ProductionAndroidRuntimeConfiguredPort {
    fun isConfigured(): Boolean
}

internal fun interface ProductionAndroidRuntimeStartupProvisionPort {
    fun provision(): ProductionAndroidRuntimeStartupSourceInstallResult
}

internal fun interface ProductionAndroidRuntimeStartStatePort {
    fun start(): ProductionAndroidAppRuntimeState
}

/**
 * Application-scope one-shot startup coordinator.
 *
 * Coordinator != Provisioning Authority.
 * Coordinator != Runtime State Authority.
 * Coordinator != Retry/Recovery Engine.
 */
class ProductionAndroidRuntimeStartupCoordinator {
    @Volatile
    private var terminalRejection: ProductionAndroidAppStartupOutcome? = null

    @Synchronized
    internal fun start(
        hasStartupInput: Boolean,
        configuredPort: ProductionAndroidRuntimeConfiguredPort,
        provisionPort: ProductionAndroidRuntimeStartupProvisionPort,
        runtimeStartPort: ProductionAndroidRuntimeStartStatePort
    ): ProductionAndroidAppStartupOutcome {
        terminalRejection?.let { return it }

        if (configuredPort.isConfigured()) {
            return ProductionAndroidAppStartupOutcome.Runtime(runtimeStartPort.start())
        }

        if (!hasStartupInput) {
            return ProductionAndroidAppStartupOutcome.ConfigurationRequired
        }

        val provisioned = try {
            provisionPort.provision()
        } catch (_: Exception) {
            ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(
                AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE
            )
        }

        return when (provisioned) {
            ProductionAndroidRuntimeStartupSourceInstallResult.Installed,
            ProductionAndroidRuntimeStartupSourceInstallResult.AlreadyConfigured ->
                ProductionAndroidAppStartupOutcome.Runtime(runtimeStartPort.start())

            is ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected ->
                ProductionAndroidAppStartupOutcome.SourceRejected(provisioned.reason)
                    .also { terminalRejection = it }

            is ProductionAndroidRuntimeStartupSourceInstallResult.ProvisioningRejected ->
                ProductionAndroidAppStartupOutcome.ProvisioningRejected(provisioned.result)
                    .also { terminalRejection = it }
        }
    }
}
