package pro.liliya.android.runtime

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedOpenResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedPayloadLoader
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.packager.ProtectedModelOfflinePackager
import pro.liliya.packager.ProtectedModelPackagingContainerBudgets
import pro.liliya.packager.ProtectedModelPackagingNonceSource
import pro.liliya.packager.ProtectedModelPackagingRequest
import pro.liliya.packager.ProtectedModelPackagingResult
import pro.liliya.packager.ProtectedModelPackagingSignResult
import pro.liliya.packager.ProtectedModelPackagingSignature
import pro.liliya.packager.ProtectedModelPackagingSigner

class ProductProtectedModelOfflinePackagerIntegrationContractTest {
    @Test
    fun packaged_multisegment_source_round_trips_through_production_parser_verifier_and_loader() {
        val root = Files.createTempDirectory("liliya-packager-roundtrip").toFile()
        try {
            val source = root.resolve("source.gguf")
            val destination = root.resolve("source.lpm1")
            val sourceBytes = ByteArray(150_000) { index ->
                ((index * 31 + 17) and 0xff).toByte()
            }
            source.writeBytes(sourceBytes)

            val model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("real-model-package"),
                generation = ProtectedModelGeneration(4)
            )
            val dek = ModelDekReference(
                id = ModelDekId("real-model-dek"),
                generation = ModelDekGeneration(7)
            )
            val dekBytes = ByteArray(32) { index ->
                (0x40 + index).toByte()
            }
            val signerId = ProtectedModelSignerId("offline-packager-signer")
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

            val resourceBudgets = LargeProtectedModelResourceBudgets(
                maxTotalPlaintextBytes = 1_000_000,
                maxTotalCiphertextBodyBytes = 1_000_000,
                maxTotalProtectedPayloadBytes = 1_100_000,
                maxSegmentCount = 64,
                minNonFinalSegmentPlaintextBytes = 4_096,
                maxSegmentPlaintextBytes = 65_536,
                maxSegmentCiphertextBodyBytes = 65_536,
                maxStructuralIdentifierChars = 256,
                maxCanonicalManifestBytes = 1_000_000
            )
            val packageBudgets = LargeProtectedModelPackageBudgets(
                maxModelProfileIdChars = 128,
                maxSignerIdChars = 128,
                maxCanonicalSignedManifestBytes = 1_100_000
            )
            val localBudgets = ProductProtectedModelLocalPackageBudgets(
                maxContainerBytes = 1_500_000,
                maxSignatureBytes = 128
            )

            val packaged = ProtectedModelOfflinePackager(
                nonceSource = ProtectedModelPackagingNonceSource { segmentIndex ->
                    ByteArray(12) { offset ->
                        (segmentIndex * 17 + offset + 1).toByte()
                    }
                }
            ).packageFile(
                ProtectedModelPackagingRequest(
                    source = source,
                    destination = destination,
                    model = model,
                    modelProfileId = ProtectedModelProfileId("liliya-offline-v1"),
                    modelDek = dek,
                    modelDekMaterial = ProtectedModelDekMaterial(dekBytes),
                    signerId = signerId,
                    signer = ProtectedModelPackagingSigner { canonicalInput ->
                        val signer = Signature.getInstance("Ed25519")
                        signer.initSign(pair.private)
                        signer.update(canonicalInput)
                        ProtectedModelPackagingSignResult.Signed(
                            ProtectedModelPackagingSignature(signer.sign())
                        )
                    },
                    resourceBudgets = resourceBudgets,
                    packageBudgets = packageBudgets,
                    containerBudgets = ProtectedModelPackagingContainerBudgets(
                        maxContainerBytes = localBudgets.maxContainerBytes,
                        maxSignatureBytes = localBudgets.maxSignatureBytes
                    ),
                    segmentPlaintextBytes = 65_536
                )
            )

            val packagedResult = assertIs<ProtectedModelPackagingResult.Packaged>(packaged)
            assertEquals(model, packagedResult.model)
            assertEquals(dek, packagedResult.modelDek)
            assertEquals(3, packagedResult.segmentCount)
            assertEquals(sourceBytes.size.toLong(), packagedResult.plaintextBytes)

            val packageBytes = destination.readBytes()
            assertFalse(containsSubsequence(packageBytes, dekBytes))

            val opened = assertIs<ProductProtectedModelLocalPackageOpenResult.Opened>(
                ProductProtectedModelLocalPackage.open(
                    file = destination,
                    manifestBudgets = resourceBudgets,
                    packageBudgets = packageBudgets,
                    containerBudgets = localBudgets
                )
            )
            assertEquals(
                LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                opened.envelope.manifest.payload.profile
            )

            val verifier = LargeProtectedModelPackageVerifier(
                signerResolver = ProtectedModelSignerResolver { requestedSigner, algorithm ->
                    if (
                        requestedSigner == signerId &&
                        algorithm == ProtectedModelSignatureAlgorithm.ED25519
                    ) {
                        pair.public
                    } else {
                        null
                    }
                },
                budgets = packageBudgets
            )
            val loader = LargeProtectedModelSegmentedPayloadLoader(
                packageVerifier = verifier,
                dekResolver = ProtectedModelDekResolver { requestedModel, requestedDek ->
                    if (requestedModel == model && requestedDek == dek) {
                        SecretKeySpec(dekBytes.copyOf(), "AES")
                    } else {
                        null
                    }
                }
            )

            val reconstructed = ArrayList<Byte>()
            val load = loader.open(
                envelope = opened.envelope,
                source = opened.source
            ) { consumedModel, _, plaintext ->
                assertEquals(model, consumedModel)
                plaintext.forEach { byte -> reconstructed.add(byte) }
            }

            val completed = assertIs<LargeProtectedModelSegmentedOpenResult.Completed>(load)
            assertEquals(model, completed.model)
            assertEquals(3, completed.segmentCount)
            assertEquals(sourceBytes.size.toLong(), completed.plaintextBytes)
            assertContentEquals(sourceBytes, reconstructed.toByteArray())

            packageBytes.fill(0)
            dekBytes.fill(0)
            sourceBytes.fill(0)
        } finally {
            root.deleteRecursively()
        }
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
