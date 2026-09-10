package pro.liliya.packager

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelSignerId

class ProtectedModelOfflinePackagerContractTest {
    @Test
    fun packages_streamed_source_without_embedding_plaintext_dek_or_private_key() {
        val root = Files.createTempDirectory("liliya-packager-success").toFile()
        try {
            val source = root.resolve("source.gguf")
            val destination = root.resolve("model.lpm1")
            val sourceBytes = ByteArray(96 * 1024 + 17) { index ->
                ((index * 31 + 11) and 0xff).toByte()
            }
            source.writeBytes(sourceBytes)

            val dekBytes = ByteArray(32) { index ->
                ((index * 7 + 3) and 0xff).toByte()
            }
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

            val result = ProtectedModelOfflinePackager(
                nonceSource = deterministicNonceSource()
            ).packageFile(
                request(
                    source = source,
                    destination = destination,
                    containerBytes = 3_000_000,
                    dekBytes = dekBytes,
                    signer = ed25519Signer(keyPair.private)
                )
            )

            val packaged = assertIs<ProtectedModelPackagingResult.Packaged>(result)
            assertTrue(destination.isFile)
            assertEquals(destination.length(), packaged.packageBytes)

            val packageBytes = destination.readBytes()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(packageBytes)
            assertContentEquals(digest, packaged.packageSha256.copyBytes())
            assertFalse(containsSubsequence(packageBytes, dekBytes))
            assertFalse(containsSubsequence(packageBytes, keyPair.private.encoded))

            packageBytes.fill(0)
            digest.fill(0)
            dekBytes.fill(0)
            sourceBytes.fill(0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun same_size_source_mutation_after_preflight_digest_is_rejected() {
        val root = Files.createTempDirectory("liliya-packager-source-mutation").toFile()
        try {
            val source = root.resolve("source.gguf")
            val destination = root.resolve("source.lpm1")
            source.writeBytes(ByteArray(90_000) { index -> (index * 7 + 3).toByte() })
            val originalModified = source.lastModified()
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            var mutated = false

            val result = ProtectedModelOfflinePackager(
                nonceSource = ProtectedModelPackagingNonceSource { segmentIndex ->
                    if (!mutated) {
                        val bytes = source.readBytes()
                        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                        source.writeBytes(bytes)
                        source.setLastModified(originalModified)
                        bytes.fill(0)
                        mutated = true
                    }
                    ByteArray(12) { offset ->
                        (segmentIndex * 13 + offset + 1).toByte()
                    }
                }
            ).packageFile(
                request(
                    source = source,
                    destination = destination,
                    containerBytes = 500_000,
                    signer = ed25519Signer(keyPair.private)
                )
            )

            assertEquals(
                ProtectedModelPackagingFailure.SOURCE_REJECTED,
                assertIs<ProtectedModelPackagingResult.Rejected>(result).reason
            )
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicate_segment_nonce_is_rejected_before_output_publication() {
        val root = Files.createTempDirectory("packager-duplicate-nonce").toFile()
        try {
            val source = root.resolve("source.gguf").also {
                it.writeBytes(ByteArray(90_000) { index -> index.toByte() })
            }
            val destination = root.resolve("source.lpm1")
            val request = request(
                source = source,
                destination = destination,
                containerBytes = 500_000
            )

            val result = ProtectedModelOfflinePackager(
                nonceSource = ProtectedModelPackagingNonceSource {
                    ByteArray(12) { 7 }
                }
            ).packageFile(request)

            assertEquals(
                ProtectedModelPackagingFailure.NONCE_REJECTED,
                assertIs<ProtectedModelPackagingResult.Rejected>(result).reason
            )
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun predicted_container_over_budget_is_rejected_before_signing_or_publication() {
        val root = Files.createTempDirectory("packager-container-budget").toFile()
        try {
            val source = root.resolve("source.gguf").also {
                it.writeBytes(ByteArray(90_000) { index -> (index * 3).toByte() })
            }
            val destination = root.resolve("source.lpm1")
            var signerCalled = false
            val base = request(
                source = source,
                destination = destination,
                containerBytes = 128
            )
            val request = base.copy(
                signer = ProtectedModelPackagingSigner {
                    signerCalled = true
                    ProtectedModelPackagingSignResult.Rejected
                }
            )

            val result = ProtectedModelOfflinePackager(
                nonceSource = deterministicNonceSource()
            ).packageFile(request)

            assertEquals(
                ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED,
                assertIs<ProtectedModelPackagingResult.Rejected>(result).reason
            )
            assertEquals(false, signerCalled)
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(
        source: java.io.File,
        destination: java.io.File,
        containerBytes: Long,
        dekBytes: ByteArray = ByteArray(32) { index -> (index + 1).toByte() },
        signer: ProtectedModelPackagingSigner =
            ProtectedModelPackagingSigner {
                error("signer must not be called by this contract")
            }
    ): ProtectedModelPackagingRequest =
        ProtectedModelPackagingRequest(
            source = source,
            destination = destination,
            model = ProtectedModelReference(
                ProtectedModelPackageId("packager-contract-model"),
                ProtectedModelGeneration(1)
            ),
            modelProfileId = ProtectedModelProfileId("packager-contract-profile"),
            modelDek = ModelDekReference(
                ModelDekId("packager-contract-dek"),
                ModelDekGeneration(1)
            ),
            modelDekMaterial = ProtectedModelDekMaterial(dekBytes),
            signerId = ProtectedModelSignerId("packager-contract-signer"),
            signer = signer,
            resourceBudgets = LargeProtectedModelResourceBudgets(
                maxTotalPlaintextBytes = 3_000_000,
                maxTotalCiphertextBodyBytes = 3_000_000,
                maxTotalProtectedPayloadBytes = 3_100_000,
                maxSegmentCount = 64,
                minNonFinalSegmentPlaintextBytes = 4_096,
                maxSegmentPlaintextBytes = 65_536,
                maxSegmentCiphertextBodyBytes = 65_536,
                maxStructuralIdentifierChars = 256,
                maxCanonicalManifestBytes = 500_000
            ),
            packageBudgets = LargeProtectedModelPackageBudgets(
                maxModelProfileIdChars = 128,
                maxSignerIdChars = 128,
                maxCanonicalSignedManifestBytes = 600_000
            ),
            containerBudgets = ProtectedModelPackagingContainerBudgets(
                maxContainerBytes = containerBytes,
                maxSignatureBytes = 128
            ),
            segmentPlaintextBytes = 65_536
        )

    private fun deterministicNonceSource() =
        ProtectedModelPackagingNonceSource { segmentIndex ->
            ByteArray(12) { offset ->
                (segmentIndex * 13 + offset + 1).toByte()
            }
        }

    private fun ed25519Signer(
        privateKey: java.security.PrivateKey
    ) = ProtectedModelPackagingSigner { input ->
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(input)
        ProtectedModelPackagingSignResult.Signed(
            ProtectedModelPackagingSignature(signer.sign())
        )
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
