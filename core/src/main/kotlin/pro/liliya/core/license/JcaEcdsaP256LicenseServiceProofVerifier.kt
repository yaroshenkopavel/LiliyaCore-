package pro.liliya.core.license

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Production verifier for signed Licensing Service security-state evidence.
 *
 * This verifier authenticates only service-state evidence. It does not create License,
 * Authority or Execution permission.
 */
object JcaEcdsaP256LicenseServiceProofVerifier : LicenseServiceProofVerifier {
    private val supportedProfile =
        LicenseServiceEvidenceProfile("ECDSA-P256-SHA256-SERVICE-STATE-V1")

    override fun verify(
        profile: LicenseServiceEvidenceProfile,
        key: LicenseServiceTrustedVerificationKey,
        transcript: LicenseServiceAuthenticationTranscript,
        proof: LicenseServiceAuthenticationProof
    ): Boolean {
        if (profile != supportedProfile || key.profile != supportedProfile) return false

        return try {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(key.copyMaterial())
            )
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(transcript.copyBytes())
            verifier.verify(proof.copyBytes())
        } catch (_: RuntimeException) {
            false
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }
}
