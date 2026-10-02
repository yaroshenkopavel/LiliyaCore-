package pro.liliya.app

import android.content.Context
import java.io.File
import pro.liliya.core.licensetransport.ActivationRedemptionHttpClient
import pro.liliya.core.licensetransport.ActivationRedemptionHttpRequest
import pro.liliya.core.licensetransport.ActivationRedemptionHttpResult
import pro.liliya.core.licensetransport.LicenseClientTransportFailure

internal sealed interface ProductionAndroidActivationResult {
    data object ProfileRequired : ProductionAndroidActivationResult
    data class Rejected(val reason: String) : ProductionAndroidActivationResult
    data class TransportFailed(
        val reason: LicenseClientTransportFailure
    ) : ProductionAndroidActivationResult
    data class FirstRun(
        val result: ProductionAndroidFirstRunAcquisitionResult
    ) : ProductionAndroidActivationResult
    data object Failed : ProductionAndroidActivationResult
}

/**
 * Fresh-install activation:
 * Activation Code -> redemption -> signed License -> existing trust/authority first-run path.
 */
internal object ProductionAndroidActivationFirstRun {
    fun activate(
        context: Context,
        activationCode: String,
        localModelFile: File?
    ): ProductionAndroidActivationResult {
        if (activationCode.isBlank()) {
            return ProductionAndroidActivationResult.Rejected("INVALID_CODE")
        }

        val profile = try {
            ProductionAndroidActivationProfileSourceOwner.current()?.load()
        } catch (_: Throwable) {
            return ProductionAndroidActivationResult.Failed
        } ?: return ProductionAndroidActivationResult.ProfileRequired

        val attemptId = try {
            ProductionAndroidActivationAttemptIdentity.loadOrCreate(context)
        } catch (_: Throwable) {
            return ProductionAndroidActivationResult.Failed
        }

        val redemption = try {
            ActivationRedemptionHttpClient(profile.transport).redeem(
                ActivationRedemptionHttpRequest(
                    activationCode = activationCode,
                    attemptId = attemptId
                )
            )
        } catch (_: Throwable) {
            return ProductionAndroidActivationResult.Failed
        }

        return when (redemption) {
            is ActivationRedemptionHttpResult.Rejected ->
                ProductionAndroidActivationResult.Rejected(redemption.reason)

            is ActivationRedemptionHttpResult.Failed ->
                ProductionAndroidActivationResult.TransportFailed(redemption.reason)

            is ActivationRedemptionHttpResult.Activated -> {
                val firstRun = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
                    localModelFile = localModelFile,
                    licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                        ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed(
                            redemption.license
                        )
                    },
                    productInput = profile.productInputTemplate.productInputPort()
                )
                ProductionAndroidActivationResult.FirstRun(firstRun)
            }
        }
    }
}
