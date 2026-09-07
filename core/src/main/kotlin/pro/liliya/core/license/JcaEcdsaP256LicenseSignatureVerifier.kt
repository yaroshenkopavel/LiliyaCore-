package pro.liliya.core.license

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Production JCA verifier for the Licensing Service v0.1 ECDSA P-256 profile.
 *
 * Accepted algorithm identity:
 * `ECDSA-P256-SHA256`
 *
 * Trusted key material is expected as an X.509 SubjectPublicKeyInfo encoded EC public key.
 */
object JcaEcdsaP256LicenseSignatureVerifier : LicenseSignatureVerifier {
    private val supportedAlgorithm = LicenseAlgorithm("ECDSA-P256-SHA256")

    override fun verify(
        algorithm: LicenseAlgorithm,
        key: LicenseTrustedVerificationKey,
        payload: LicenseCanonicalPayload,
        signature: LicenseSignature
    ): Boolean {
        if (algorithm != supportedAlgorithm) return false
        if (key.algorithm != algorithm) return false

        return try {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(key.copyMaterial())
            )
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payload.copyBytes())
            verifier.verify(signature.copyBytes())
        } catch (_: RuntimeException) {
            false
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }
}
