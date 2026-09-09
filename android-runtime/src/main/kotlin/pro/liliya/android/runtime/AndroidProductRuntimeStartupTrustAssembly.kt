package pro.liliya.android.runtime

import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseSignatureVerifier
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseVerificationComposition
import pro.liliya.core.license.LicenseVerificationRejection
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.observability.LoggerProvider

data class AndroidProductRuntimeStartupTrustInput(
    val diagnostics: DiagnosticRecorder,
    val loggerProvider: LoggerProvider,
    val supportedLicenseSchemaVersion: LicenseVersion,
    val trustedKeys: LicenseTrustedKeyResolver,
    val licenseEnvelope: LicenseSignedEnvelope,
    val supportedAlgorithms: Set<LicenseAlgorithm> = setOf(
        LicenseAlgorithm("ECDSA-P256-SHA256")
    ),
    val signatureVerifier: LicenseSignatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
)

data class AndroidProductRuntimeStartupTrustOwnership(
    val foundation: FoundationComposition,
    val capabilityAuthority: CapabilityAuthorityComposition,
    val verifiedLicense: LicenseVerificationResult.Verified
)

sealed interface AndroidProductRuntimeStartupTrustAssemblyResult {
    data class Ready(
        val ownership: AndroidProductRuntimeStartupTrustOwnership
    ) : AndroidProductRuntimeStartupTrustAssemblyResult

    data class VerificationRejected(
        val reason: LicenseVerificationRejection
    ) : AndroidProductRuntimeStartupTrustAssemblyResult

    data object Failed : AndroidProductRuntimeStartupTrustAssemblyResult
}

internal fun interface AndroidProductRuntimeStartupTrustVerificationPort {
    fun verify(): LicenseVerificationResult
}

/**
 * Creates startup trust infrastructure without creating capabilities or authority grants.
 *
 * Trust Assembly != License Issuer.
 * Trust Assembly != Capability Grant Authority.
 * Trust Assembly != Admission Authority.
 */
object AndroidProductRuntimeStartupTrustAssembly {
    fun create(
        input: AndroidProductRuntimeStartupTrustInput
    ): AndroidProductRuntimeStartupTrustAssemblyResult {
        val foundation = try {
            FoundationComposition(
                diagnostics = input.diagnostics,
                loggerProvider = input.loggerProvider
            )
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupTrustAssemblyResult.Failed
        }

        val authority = try {
            CapabilityAuthorityComposition(foundation)
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupTrustAssemblyResult.Failed
        }

        val verification = try {
            LicenseVerificationComposition(
                foundation = foundation,
                supportedSchemaVersion = input.supportedLicenseSchemaVersion,
                supportedAlgorithms = input.supportedAlgorithms,
                trustedKeys = input.trustedKeys,
                signatureVerifier = input.signatureVerifier
            )
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupTrustAssemblyResult.Failed
        }

        return resolve(
            foundation = foundation,
            authority = authority,
            verificationPort = AndroidProductRuntimeStartupTrustVerificationPort {
                verification.verify(input.licenseEnvelope)
            }
        )
    }

    internal fun resolve(
        foundation: FoundationComposition,
        authority: CapabilityAuthorityComposition,
        verificationPort: AndroidProductRuntimeStartupTrustVerificationPort
    ): AndroidProductRuntimeStartupTrustAssemblyResult {
        return when (val verified = try {
            verificationPort.verify()
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupTrustAssemblyResult.Failed
        }) {
            is LicenseVerificationResult.Verified ->
                AndroidProductRuntimeStartupTrustAssemblyResult.Ready(
                    AndroidProductRuntimeStartupTrustOwnership(
                        foundation = foundation,
                        capabilityAuthority = authority,
                        verifiedLicense = verified
                    )
                )

            is LicenseVerificationResult.Rejected ->
                AndroidProductRuntimeStartupTrustAssemblyResult.VerificationRejected(
                    verified.reason
                )
        }
    }
}
