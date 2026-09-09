package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSource
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult

sealed interface ProductionAndroidRuntimeStartupSourceInstallResult {
    data object Installed : ProductionAndroidRuntimeStartupSourceInstallResult
    data object AlreadyConfigured : ProductionAndroidRuntimeStartupSourceInstallResult
    data class SourceRejected(
        val reason: AndroidProductRuntimeStartupRequestSourceFailure
    ) : ProductionAndroidRuntimeStartupSourceInstallResult
    data class ProvisioningRejected(
        val result: AndroidProductRuntimeStartupProvisioningResult
    ) : ProductionAndroidRuntimeStartupSourceInstallResult
}

internal fun interface ProductionAndroidRuntimeStartupRequestSourcePort {
    fun create(): AndroidProductRuntimeStartupRequestSourceResult
}

/**
 * App entry from explicit startup-source inputs into the existing request/composition/install path.
 *
 * Startup Source Install != Provisioning Authority.
 * Startup Source Install != License/Capability Authority.
 * Startup Source Install != Retry/Recovery.
 */
object ProductionAndroidRuntimeStartupSourceInstall {
    fun prepareAndInstall(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): ProductionAndroidRuntimeStartupSourceInstallResult =
        prepareAndInstall(
            ProductionAndroidRuntimeStartupRequestSourcePort {
                AndroidProductRuntimeStartupRequestSource.create(input)
            }
        )

    internal fun prepareAndInstall(
        sourcePort: ProductionAndroidRuntimeStartupRequestSourcePort
    ): ProductionAndroidRuntimeStartupSourceInstallResult {
        if (ProductionAndroidRuntimeConfiguration.current() != null) {
            return ProductionAndroidRuntimeStartupSourceInstallResult.AlreadyConfigured
        }

        val sourced = try {
            sourcePort.create()
        } catch (_: Exception) {
            return ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(
                AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE
            )
        }

        return when (sourced) {
            is AndroidProductRuntimeStartupRequestSourceResult.Ready ->
                when (
                    val installed = ProductionAndroidRuntimeStartupCompositionInstall.prepareAndInstall(
                        sourced.request
                    )
                ) {
                    ProductionAndroidRuntimeStartupInstallResult.Installed ->
                        ProductionAndroidRuntimeStartupSourceInstallResult.Installed
                    ProductionAndroidRuntimeStartupInstallResult.AlreadyConfigured ->
                        ProductionAndroidRuntimeStartupSourceInstallResult.AlreadyConfigured
                    is ProductionAndroidRuntimeStartupInstallResult.Rejected ->
                        ProductionAndroidRuntimeStartupSourceInstallResult.ProvisioningRejected(
                            installed.provisioning
                        )
                }

            is AndroidProductRuntimeStartupRequestSourceResult.Rejected ->
                ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(sourced.reason)
        }
    }
}
