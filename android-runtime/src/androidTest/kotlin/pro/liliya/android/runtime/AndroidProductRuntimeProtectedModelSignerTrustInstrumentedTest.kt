package pro.liliya.android.runtime

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestFactory
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestResult
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerificationFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerificationResult
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.LargeProtectedModelPackagingPrimitives
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentDraft
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifest
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelEncryptionProfile
import pro.liliya.core.protectedmodel.ProtectedModelFormatVersion
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId

@RunWith(AndroidJUnit4::class)
class AndroidProductRuntimeProtectedModelSignerTrustInstrumentedTest {
    @Test
    fun portable_ed25519_x509_signer_is_accepted_on_android() {
        val fixtureProvider = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("Ed25519", fixtureProvider).generateKeyPair()

        val result = AndroidProductRuntimeProtectedModelSignerTrust.create(
            listOf(
                AndroidProductRuntimeProtectedModelSignerTrustKey(
                    signerId = "android-portable-model-signer",
                    material = pair.public.encoded
                )
            )
        )

        val ready = assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Ready>(result)
        val resolved = requireNotNull(
            ready.resolver.resolve(
                ProtectedModelSignerId("android-portable-model-signer"),
                ProtectedModelSignatureAlgorithm.ED25519
            )
        )
        assertContentEquals(pair.public.encoded, resolved.encoded)
    }

    @Test
    fun portable_ed25519_x509_signer_verifies_lpm1_signature_on_android() {
        val fixtureProvider = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("Ed25519", fixtureProvider).generateKeyPair()
        val ready = assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Ready>(
            AndroidProductRuntimeProtectedModelSignerTrust.create(
                listOf(
                    AndroidProductRuntimeProtectedModelSignerTrustKey(
                        signerId = SIGNER_ID,
                        material = pair.public.encoded
                    )
                )
            )
        )
        val manifest = signedManifest()
        val signatureInput = LargeProtectedModelPackagingPrimitives.signatureInput(manifest)
        val signature = try {
            Signature.getInstance("Ed25519", fixtureProvider).run {
                initSign(pair.private)
                update(signatureInput)
                sign()
            }
        } finally {
            signatureInput.fill(0)
        }
        val verifier = LargeProtectedModelPackageVerifier(
            signerResolver = ready.resolver,
            budgets = LargeProtectedModelPackageBudgets(
                maxModelProfileIdChars = 64,
                maxSignerIdChars = 64,
                maxCanonicalSignedManifestBytes = 64 * 1024L
            ),
            signatureProvider = AndroidProductRuntimeProtectedModelCryptoProvider.provider
        )

        val verified = verifier.verify(LargeProtectedModelPackageEnvelope(manifest, signature))
        assertIs<LargeProtectedModelPackageVerificationResult.Verified>(verified)

        val tampered = signature.copyOf().also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
        }
        val rejected = assertIs<LargeProtectedModelPackageVerificationResult.Rejected>(
            verifier.verify(LargeProtectedModelPackageEnvelope(manifest, tampered))
        )
        assertEquals(
            LargeProtectedModelPackageVerificationFailure.SIGNATURE_INVALID,
            rejected.reason
        )
    }

    private fun signedManifest(): LargeProtectedModelSignedManifest {
        val encryption = ProtectedModelEncryptionProfile.AES_256_GCM
        val digest = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1))
        val payload = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                LargeProtectedModelManifestRequest(
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    model = ProtectedModelReference(
                        ProtectedModelPackageId("android-portable-package"),
                        ProtectedModelGeneration(1)
                    ),
                    modelDek = ModelDekReference(
                        ModelDekId("android-portable-dek"),
                        ModelDekGeneration(1)
                    ),
                    totalPlaintextSizeBytes = 1,
                    totalCiphertextBodySizeBytes = 1,
                    totalProtectedPayloadSizeBytes =
                        1L + encryption.authenticationTagSizeBits / Byte.SIZE_BITS,
                    declaredSegmentCount = 1,
                    segments = listOf(
                        LargeProtectedModelSegmentDraft(
                            index = 0,
                            plaintextSizeBytes = 1,
                            ciphertextBodySizeBytes = 1,
                            nonce = ByteArray(encryption.nonceSizeBytes) { (it + 1).toByte() },
                            protectedPayloadDigest = digest
                        )
                    )
                ),
                LargeProtectedModelResourceBudgets(
                    maxTotalPlaintextBytes = 16,
                    maxTotalCiphertextBodyBytes = 16,
                    maxTotalProtectedPayloadBytes = 64,
                    maxSegmentCount = 1,
                    minNonFinalSegmentPlaintextBytes = 1,
                    maxSegmentPlaintextBytes = 16,
                    maxSegmentCiphertextBodyBytes = 16,
                    maxStructuralIdentifierChars = 64,
                    maxCanonicalManifestBytes = 64 * 1024L
                )
            )
        ).manifest
        return LargeProtectedModelSignedManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            modelProfileId = ProtectedModelProfileId("gguf-v1"),
            payload = payload,
            encryptionProfile = encryption,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId(SIGNER_ID)
        )
    }

    private companion object {
        const val SIGNER_ID = "android-portable-model-signer"
    }
}
