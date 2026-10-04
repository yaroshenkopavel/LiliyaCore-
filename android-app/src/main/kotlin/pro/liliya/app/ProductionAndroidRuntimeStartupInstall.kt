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

internal fun interface ProductionAndroidRuntimeStartupProvisioningReadyCommitPort {
    fun commit(
        ready: AndroidProductRuntimeStartupProvisioningResult.Ready
    ): Boolean
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
            ports = ports,
            readyCommit = ProductionAndroidRuntimeStartupProvisioningReadyCommitPort { true }
        )

    internal fun prepareAndInstall(
        ports: AndroidProductRuntimeStartupProvisioningPorts,
        readyCommit: ProductionAndroidRuntimeStartupProvisioningReadyCommitPort
    ): ProductionAndroidRuntimeStartupInstallResult =
        prepareAndInstall(
            preparePort = ProductionAndroidRuntimeStartupPreparePort {
                AndroidProductRuntimeStartupProvisioner.prepare(ports)
            },
            readyCommit = readyCommit
        )

    internal fun prepareAndInstall(
        preparePort: ProductionAndroidRuntimeStartupPreparePort,
        readyCommit: ProductionAndroidRuntimeStartupProvisioningReadyCommitPort =
            ProductionAndroidRuntimeStartupProvisioningReadyCommitPort { true }
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

        val committed = try {
            readyCommit.commit(ready)
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            return ProductionAndroidRuntimeStartupInstallResult.Rejected(
                AndroidProductRuntimeStartupProvisioningResult.Rejected(
                    AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE
                )
            )
        }

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
