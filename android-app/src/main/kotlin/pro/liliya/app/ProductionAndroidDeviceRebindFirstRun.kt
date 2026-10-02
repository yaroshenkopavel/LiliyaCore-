package pro.liliya.app

import android.content.Context
import java.io.File
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.licensetransport.DeviceRebindHttpClient
import pro.liliya.core.licensetransport.DeviceRebindHttpRequest
import pro.liliya.core.licensetransport.DeviceRebindHttpResult
import pro.liliya.core.licensetransport.LicenseClientTransportFailure

internal sealed interface ProductionAndroidDeviceRebindResult {
    data object ProfileRequired : ProductionAndroidDeviceRebindResult
    data class Rejected(val reason: String) : ProductionAndroidDeviceRebindResult
    data class TransportFailed(
        val reason: LicenseClientTransportFailure
    ) : ProductionAndroidDeviceRebindResult
    data class FirstRun(
        val result: ProductionAndroidFirstRunAcquisitionResult
    ) : ProductionAndroidDeviceRebindResult
    data object Failed : ProductionAndroidDeviceRebindResult
}

internal object ProductionAndroidDeviceRebindFirstRun {
    fun rebind(
        context: Context,
        rebindCode: String,
        localModelFile: File?
    ): ProductionAndroidDeviceRebindResult {
        if (rebindCode.isBlank()) {
            return ProductionAndroidDeviceRebindResult.Rejected("INVALID_CODE")
        }

        val profile = try {
            ProductionAndroidActivationProfileSourceOwner.current()?.load()
        } catch (_: Throwable) {
            return ProductionAndroidDeviceRebindResult.Failed
        } ?: return ProductionAndroidDeviceRebindResult.ProfileRequired

        val attemptId = try {
            ProductionAndroidDeviceRebindAttemptIdentity.loadOrCreate(context)
        } catch (_: Throwable) {
            return ProductionAndroidDeviceRebindResult.Failed
        }

        val deviceBinding = try {
            when (
                val result =
                    AndroidActivationDeviceBindingProvider(context).loadOrCreate()
            ) {
                is AndroidActivationDeviceBindingResult.Ready -> result.binding
                is AndroidActivationDeviceBindingResult.Rejected ->
                    return ProductionAndroidDeviceRebindResult.Rejected(
                        "DEVICE_BINDING_REJECTED"
                    )
                AndroidActivationDeviceBindingResult.MalformedLocalState ->
                    return ProductionAndroidDeviceRebindResult.Failed
            }
        } catch (_: Throwable) {
            return ProductionAndroidDeviceRebindResult.Failed
        }

        val rebind = try {
            DeviceRebindHttpClient(profile.transport).rebind(
                DeviceRebindHttpRequest(
                    rebindCode = rebindCode,
                    attemptId = attemptId,
                    installationId = deviceBinding.installationId,
                    deviceKeyFingerprint = deviceBinding.deviceKeyFingerprint
                )
            )
        } catch (_: Throwable) {
            return ProductionAndroidDeviceRebindResult.Failed
        }

        return when (rebind) {
            is DeviceRebindHttpResult.Rejected ->
                ProductionAndroidDeviceRebindResult.Rejected(rebind.reason)

            is DeviceRebindHttpResult.Failed ->
                ProductionAndroidDeviceRebindResult.TransportFailed(
                    rebind.reason
                )

            is DeviceRebindHttpResult.Rebound -> {
                val firstRun =
                    ProductionAndroidFirstRunAcquisitionOrchestrator
                        .prepareAndInstall(
                            localModelFile = localModelFile,
                            licenseAcquisition =
                                ProductionAndroidFirstRunLicenseAcquisitionPort {
                                    ProductionAndroidLicenseEnvelopeAcquisitionResult
                                        .Signed(rebind.license)
                                },
                            productInput =
                                profile.productInputTemplate.productInputPort(
                                    requiredDeviceBindingReference =
                                        LicenseDeviceBindingReferenceFactory
                                            .create(
                                                installationId =
                                                    deviceBinding.installationId,
                                                deviceKeyFingerprint =
                                                    deviceBinding
                                                        .deviceKeyFingerprint
                                            ).value
                                )
                        )

                ProductionAndroidDeviceRebindResult.FirstRun(firstRun)
            }
        }
    }
}
