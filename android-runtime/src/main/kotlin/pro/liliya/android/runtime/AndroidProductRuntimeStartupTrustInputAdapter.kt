package pro.liliya.android.runtime

import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

data class AndroidProductRuntimeStartupTrustChoice(
    val supportedSchemaVersion: Long,
    val licenseEnvelope: LicenseSignedEnvelope
)

sealed interface AndroidProductRuntimeStartupTrustInputAdapterResult {
    data class Ready(
        val input: AndroidProductRuntimeStartupTrustInput
    ) : AndroidProductRuntimeStartupTrustInputAdapterResult

    data object Rejected : AndroidProductRuntimeStartupTrustInputAdapterResult
}

/**
 * Connects already-owned production observability and exact entitlement trust material into the
 * existing startup trust input.
 *
 * Trust Input Adapter != License Acquisition.
 * Trust Input Adapter != Trusted Key Discovery.
 * Trust Input Adapter != License Policy.
 */
object AndroidProductRuntimeStartupTrustInputAdapter {
    private val productionAlgorithm = LicenseAlgorithm("ECDSA-P256-SHA256")

    fun create(
        observability: AndroidProductRuntimeObservability,
        trustMaterial: AndroidProductRuntimeLicenseTrustMaterialResult.Ready,
        choice: AndroidProductRuntimeStartupTrustChoice
    ): AndroidProductRuntimeStartupTrustInputAdapterResult =
        try {
            AndroidProductRuntimeStartupTrustInputAdapterResult.Ready(
                AndroidProductRuntimeStartupTrustInput(
                    diagnostics = observability.diagnostics,
                    loggerProvider = observability.loggerProvider,
                    supportedLicenseSchemaVersion = LicenseVersion(
                        choice.supportedSchemaVersion
                    ),
                    trustedKeys = trustMaterial.resolver,
                    licenseEnvelope = choice.licenseEnvelope,
                    supportedAlgorithms = setOf(productionAlgorithm),
                    signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
                )
            )
        } catch (_: IllegalArgumentException) {
            AndroidProductRuntimeStartupTrustInputAdapterResult.Rejected
        }
}
