package pro.liliya.app

import android.content.Context
import java.io.File
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
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

        val deviceBinding = try {
            when (
                val result = AndroidActivationDeviceBindingProvider(context).loadOrCreate()
            ) {
                is AndroidActivationDeviceBindingResult.Ready -> result.binding
                is AndroidActivationDeviceBindingResult.Rejected ->
                    return ProductionAndroidActivationResult.Rejected(
                        "DEVICE_BINDING_REJECTED"
                    )
                AndroidActivationDeviceBindingResult.MalformedLocalState ->
                    return ProductionAndroidActivationResult.Failed
            }
        } catch (_: Throwable) {
            return ProductionAndroidActivationResult.Failed
        }

        val redemption = try {
            ActivationRedemptionHttpClient(profile.transport).redeem(
                ActivationRedemptionHttpRequest(
                    activationCode = activationCode,
                    attemptId = attemptId,
                    installationId = deviceBinding.installationId,
                    deviceKeyFingerprint = deviceBinding.deviceKeyFingerprint
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
                    productInput = profile.productInputTemplate.productInputPort(
                        requiredDeviceBindingReference =
                            LicenseDeviceBindingReferenceFactory.create(
                                installationId = deviceBinding.installationId,
                                deviceKeyFingerprint = deviceBinding.deviceKeyFingerprint
                            ).value
                    )
                )
                ProductionAndroidActivationResult.FirstRun(firstRun)
            }
        }
    }
}
