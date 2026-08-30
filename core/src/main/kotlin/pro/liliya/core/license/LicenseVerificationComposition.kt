package pro.liliya.core.license

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class LicenseVerificationComposition(
    private val foundation: FoundationComposition,
    supportedSchemaVersion: LicenseVersion,
    supportedAlgorithms: Set<LicenseAlgorithm>,
    trustedKeys: LicenseTrustedKeyResolver,
    signatureVerifier: LicenseSignatureVerifier
) {
    private val verifier = LicenseVerifier(
        supportedSchemaVersion = supportedSchemaVersion,
        supportedAlgorithms = supportedAlgorithms,
        trustedKeys = trustedKeys,
        signatureVerifier = signatureVerifier
    )

    fun verify(envelope: LicenseSignedEnvelope): LicenseVerificationResult {
        val context = foundation.rootContext(
            operation = "verifyLicenseEnvelope",
            component = "License",
            metadata = metadata(envelope)
        )
        val result = verifier.verify(envelope)
        when (result) {
            is LicenseVerificationResult.Verified -> foundation.observability.record(
                severity = DiagnosticSeverity.INFO,
                code = "LICENSE_VERIFICATION_SUCCEEDED",
                message = "license envelope verified",
                context = context,
                metadata = metadata(envelope)
            )

            is LicenseVerificationResult.Rejected -> foundation.observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LICENSE_VERIFICATION_REJECTED",
                message = "license envelope verification rejected",
                context = context,
                metadata = metadata(envelope) +
                    ("licenseVerificationRejection" to result.reason.name.lowercase())
            )
        }
        return result
    }

    private fun metadata(envelope: LicenseSignedEnvelope): Map<String, String> = mapOf(
        "licenseSchemaVersion" to envelope.schemaVersion.value.toString(),
        "licenseAlgorithm" to envelope.algorithm.value,
        "licenseSigningKeyId" to envelope.signingKeyId.value
    )
}
