package pro.liliya.android.runtime

import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

sealed interface AndroidProductRuntimeStartupTrustInputFactoryResult {
    data class Ready(
        val input: AndroidProductRuntimeStartupTrustInput
    ) : AndroidProductRuntimeStartupTrustInputFactoryResult

    data object Rejected : AndroidProductRuntimeStartupTrustInputFactoryResult
}

/**
 * Builds the exact startup trust input from already-acquired product-owned trust material.
 *
 * Trust Input Factory != License Acquisition.
 * Trust Input Factory != License Verification.
 * Trust Input Factory != Key Discovery.
 * Trust Input Factory != Observability Storage Policy.
 */
object AndroidProductRuntimeStartupTrustInputFactory {
    fun create(
        observability: AndroidProductRuntimeObservability,
        supportedLicenseSchemaVersion: Long,
        keys: List<AndroidProductRuntimeLicenseTrustKey>,
        licenseEnvelope: LicenseSignedEnvelope
    ): AndroidProductRuntimeStartupTrustInputFactoryResult {
        val version = try {
            LicenseVersion(supportedLicenseSchemaVersion)
        } catch (_: IllegalArgumentException) {
            return AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected
        }

        val trust = when (
            val result = AndroidProductRuntimeLicenseTrustMaterial.create(keys)
        ) {
            is AndroidProductRuntimeLicenseTrustMaterialResult.Ready -> result
            AndroidProductRuntimeLicenseTrustMaterialResult.Rejected ->
                return AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected
        }

        return AndroidProductRuntimeStartupTrustInputFactoryResult.Ready(
            AndroidProductRuntimeStartupTrustInput(
                diagnostics = observability.diagnostics,
                loggerProvider = observability.loggerProvider,
                supportedLicenseSchemaVersion = version,
                trustedKeys = trust.resolver,
                licenseEnvelope = licenseEnvelope
            )
        )
    }
}
