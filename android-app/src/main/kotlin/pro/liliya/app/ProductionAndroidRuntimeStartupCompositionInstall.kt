package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeStartupCompositionRequest
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPortsComposition
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult

internal fun interface ProductionAndroidRuntimeStartupCompositionInstallPort {
    fun prepareAndInstall(): ProductionAndroidRuntimeStartupInstallResult
}

/**
 * App entry from one explicit production startup request into the already-owned provisioning/install
 * pipeline.
 *
 * Startup Composition Install != Provisioning Authority.
 * Startup Composition Install != License/Capability Authority.
 * Startup Composition Install != Retry/Recovery.
 */
object ProductionAndroidRuntimeStartupCompositionInstall {
    fun prepareAndInstall(
        request: AndroidProductRuntimeStartupCompositionRequest
    ): ProductionAndroidRuntimeStartupInstallResult =
        prepareAndInstall(
            ProductionAndroidRuntimeStartupCompositionInstallPort {
                ProductionAndroidRuntimeStartupInstall.prepareAndInstall(
                    AndroidProductRuntimeStartupPortsComposition.create(request)
                )
            }
        )

    internal fun prepareAndInstall(
        port: ProductionAndroidRuntimeStartupCompositionInstallPort
    ): ProductionAndroidRuntimeStartupInstallResult =
        try {
            port.prepareAndInstall()
        } catch (_: Exception) {
            ProductionAndroidRuntimeStartupInstallResult.Rejected(
                AndroidProductRuntimeStartupProvisioningResult.Rejected(
                    AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE
                )
            )
        }
}
