package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioner
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningPorts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult

sealed interface ProductionAndroidRuntimeStartupInstallResult {
    data object Installed : ProductionAndroidRuntimeStartupInstallResult
    data object AlreadyConfigured : ProductionAndroidRuntimeStartupInstallResult
    data class Rejected(
        val provisioning: AndroidProductRuntimeStartupProvisioningResult
    ) : ProductionAndroidRuntimeStartupInstallResult
}

internal fun interface ProductionAndroidRuntimeStartupPreparePort {
    fun prepare(): AndroidProductRuntimeStartupProvisioningResult
}

/**
 * App-side publication bridge for an already-bounded startup provisioning pipeline.
 *
 * Startup Install != Provisioning Authority.
 * Startup Install != License/Authority ownership.
 * Startup Install != Runtime restart/retry.
 */
object ProductionAndroidRuntimeStartupInstall {
    fun prepareAndInstall(
        ports: AndroidProductRuntimeStartupProvisioningPorts
    ): ProductionAndroidRuntimeStartupInstallResult =
        prepareAndInstall(
            ProductionAndroidRuntimeStartupPreparePort {
                AndroidProductRuntimeStartupProvisioner.prepare(ports)
            }
        )

    internal fun prepareAndInstall(
        preparePort: ProductionAndroidRuntimeStartupPreparePort
    ): ProductionAndroidRuntimeStartupInstallResult {
        if (ProductionAndroidRuntimeConfiguration.current() != null) {
            return ProductionAndroidRuntimeStartupInstallResult.AlreadyConfigured
        }

        val prepared = try {
            preparePort.prepare()
        } catch (_: Exception) {
            return ProductionAndroidRuntimeStartupInstallResult.Rejected(
                AndroidProductRuntimeStartupProvisioningResult.Rejected(
                    AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE
                )
            )
        }

        val ready = prepared as? AndroidProductRuntimeStartupProvisioningResult.Ready
            ?: return ProductionAndroidRuntimeStartupInstallResult.Rejected(prepared)

        val sources = ProductionAndroidRuntimeWiringSources(
            admission = { ready.admission },
            preparedInputs = { ready.inputs }
        )

        return installPreparedSources(sources)
    }

    internal fun installPreparedSources(
        sources: ProductionAndroidRuntimeWiringSources
    ): ProductionAndroidRuntimeStartupInstallResult =
        if (ProductionAndroidRuntimeConfiguration.install(sources)) {
            ProductionAndroidRuntimeStartupInstallResult.Installed
        } else {
            ProductionAndroidRuntimeStartupInstallResult.AlreadyConfigured
        }
}
