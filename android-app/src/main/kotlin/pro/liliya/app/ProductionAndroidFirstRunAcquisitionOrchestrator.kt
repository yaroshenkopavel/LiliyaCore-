package pro.liliya.app

import java.io.File
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInputFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputOwnership
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure

sealed interface ProductionAndroidFirstRunAcquisitionResult {
    data object LocalModelRequired : ProductionAndroidFirstRunAcquisitionResult

    data class Installed(
        val ownership: AndroidProductRuntimeStartupInputOwnership
    ) : ProductionAndroidFirstRunAcquisitionResult

    data class LicenseServiceRejected(
        val reason: LicenseRemoteServiceFailure
    ) : ProductionAndroidFirstRunAcquisitionResult

    data class LicenseAcquisitionFailed(
        val reason: LicenseClientTransportFailure
    ) : ProductionAndroidFirstRunAcquisitionResult

    data class ProductInputRejected(
        val reason: AndroidProductRuntimeFirstRunProductInputFailure
    ) : ProductionAndroidFirstRunAcquisitionResult

    data object AlreadyConfigured : ProductionAndroidFirstRunAcquisitionResult
    data object TrustVerificationRejected : ProductionAndroidFirstRunAcquisitionResult

    data class AuthorityRejected(
        val reason: AndroidProductRuntimeStartupAuthorityAssemblyFailure
    ) : ProductionAndroidFirstRunAcquisitionResult

    data object Failed : ProductionAndroidFirstRunAcquisitionResult
}

internal fun interface ProductionAndroidFirstRunLicenseAcquisitionPort {
    fun acquire(): ProductionAndroidLicenseEnvelopeAcquisitionResult
}

internal fun interface ProductionAndroidFirstRunProductInputPort {
    fun create(
        envelope: LicenseSignedEnvelope,
        localModelFile: File
    ): AndroidProductRuntimeFirstRunProductInput
}

internal fun interface ProductionAndroidFirstRunSignedInstallPort {
    fun install(
        envelope: LicenseSignedEnvelope,
        localModelFile: File
    ): ProductionAndroidFirstRunProductInstallResult
}

/**
 * Credential-neutral application boundary for one first-run preparation attempt.
 *
 * Orchestration != License Verification.
 * Orchestration != Trusted Key Discovery.
 * Orchestration != License Issuance.
 * Orchestration != Authority Minting.
 * Orchestration != Model Discovery.
 *
 * The selected model must already be owned by the product host. License transport and all
 * policy-sensitive first-run inputs remain explicit outer-product ports. A signed envelope is
 * passed unchanged into the existing first-run factory/install path, where accepted trust,
 * admission and authority checks remain authoritative.
 */
object ProductionAndroidFirstRunAcquisitionOrchestrator {
    internal fun prepareAndInstall(
        localModelFile: File?,
        licenseAcquisition: ProductionAndroidFirstRunLicenseAcquisitionPort,
        productInput: ProductionAndroidFirstRunProductInputPort
    ): ProductionAndroidFirstRunAcquisitionResult =
        prepareAndInstall(
            localModelFile = localModelFile,
            licenseAcquisition = licenseAcquisition,
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { envelope, model ->
                val input = productInput.create(envelope, model)
                ProductionAndroidFirstRunProductInstall.prepareAndInstall(input)
            }
        )

    internal fun prepareAndInstall(
        localModelFile: File?,
        licenseAcquisition: ProductionAndroidFirstRunLicenseAcquisitionPort,
        signedInstall: ProductionAndroidFirstRunSignedInstallPort
    ): ProductionAndroidFirstRunAcquisitionResult {
        val model = localModelFile ?: return ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired
        if (!model.isFile) return ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired

        val acquired = try {
            licenseAcquisition.acquire()
        } catch (_: Exception) {
            return ProductionAndroidFirstRunAcquisitionResult.Failed
        }

        val envelope = when (acquired) {
            is ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed -> acquired.envelope
            is ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected ->
                return ProductionAndroidFirstRunAcquisitionResult.LicenseServiceRejected(
                    acquired.reason
                )
            is ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed ->
                return ProductionAndroidFirstRunAcquisitionResult.LicenseAcquisitionFailed(
                    acquired.reason
                )
        }

        val installed = try {
            signedInstall.install(envelope, model)
        } catch (_: Exception) {
            return ProductionAndroidFirstRunAcquisitionResult.Failed
        }

        return mapInstallResult(installed)
    }

    internal fun mapInstallResult(
        installed: ProductionAndroidFirstRunProductInstallResult
    ): ProductionAndroidFirstRunAcquisitionResult =
        when (installed) {
            is ProductionAndroidFirstRunProductInstallResult.Installed ->
                ProductionAndroidFirstRunAcquisitionResult.Installed(installed.ownership)
            is ProductionAndroidFirstRunProductInstallResult.ProductInputRejected ->
                ProductionAndroidFirstRunAcquisitionResult.ProductInputRejected(installed.reason)
            ProductionAndroidFirstRunProductInstallResult.AlreadyConfigured ->
                ProductionAndroidFirstRunAcquisitionResult.AlreadyConfigured
            ProductionAndroidFirstRunProductInstallResult.TrustVerificationRejected ->
                ProductionAndroidFirstRunAcquisitionResult.TrustVerificationRejected
            is ProductionAndroidFirstRunProductInstallResult.AuthorityRejected ->
                ProductionAndroidFirstRunAcquisitionResult.AuthorityRejected(installed.reason)
            ProductionAndroidFirstRunProductInstallResult.Failed ->
                ProductionAndroidFirstRunAcquisitionResult.Failed
        }
}
