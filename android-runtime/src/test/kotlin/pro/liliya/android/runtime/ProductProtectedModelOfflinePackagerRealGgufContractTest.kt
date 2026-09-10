package pro.liliya.android.runtime

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerifier
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
import pro.liliya.packager.ProtectedModelPackagingRequest
import pro.liliya.packager.ProtectedModelPackagingResult
import pro.liliya.packager.ProtectedModelPackagingSignResult
import pro.liliya.packager.ProtectedModelPackagingSignature
import pro.liliya.packager.ProtectedModelPackagingSigner

class ProductProtectedModelOfflinePackagerRealGgufContractTest {
    @Test
    fun pinned_real_gguf_round_trips_through_production_protected_model_chain() {
        val required = System.getenv("LILIYA_REQUIRE_REAL_GGUF") == "1"
        val sourcePath = System.getenv("LILIYA_REAL_GGUF")
        if (!required && sourcePath.isNullOrBlank()) return
        require(!sourcePath.isNullOrBlank()) {
            "LILIYA_REAL_GGUF is required for real-GGUF packaging acceptance"
        }

        val source = File(sourcePath)
        require(source.isFile) { "real GGUF fixture is missing" }
        assertEquals(19_077_344L, source.length())

        val root = Files.createTempDirectory("liliya-real-gguf-packaging").toFile()
        val destination = root.resolve("stories15M-q4_0.lpm1")
        val model = ProtectedModelReference(
            ProtectedModelPackageId("stories15m-real"),
            ProtectedModelGeneration(1)
        )
        val dek = ModelDekReference(
            ModelDekId("stories15m-real-dek"),
            ModelDekGeneration(1)
        )
        val dekBytes = ByteArray(32) { index -> (index * 5 + 3).toByte() }
        val signerId = ProtectedModelSignerId("real-gguf-ci-signer")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val resourceBudgets = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = 32L * 1024L * 1024L,
            maxTotalCiphertextBodyBytes = 32L * 1024L * 1024L,
            maxTotalProtectedPayloadBytes = 33L * 1024L * 1024L,
            maxSegmentCount = 64,
            minNonFinalSegmentPlaintextBytes = 64L * 1024L,
            maxSegmentPlaintextBytes = 1024L * 1024L,
            maxSegmentCiphertextBodyBytes = 1024L * 1024L,
            maxStructuralIdentifierChars = 256,
            maxCanonicalManifestBytes = 2L * 1024L * 1024L
        )
        val packageBudgets = LargeProtectedModelPackageBudgets(
            maxModelProfileIdChars = 128,
            maxSignerIdChars = 128,
            maxCanonicalSignedManifestBytes = 2L * 1024L * 1024L
        )
        val localBudgets = ProductProtectedModelLocalPackageBudgets(
            maxContainerBytes = 24L * 1024L * 1024L,
            maxSignatureBytes = 128
        )

        try {
            val packaged = assertIs<ProtectedModelPackagingResult.Packaged>(
                ProtectedModelOfflinePackager().packageFile(
                    ProtectedModelPackagingRequest(
                        source = source,
                        destination = destination,
                        model = model,
                        modelProfileId = ProtectedModelProfileId("real-gguf-ci-profile"),
                        modelDek = dek,
                        modelDekMaterial = ProtectedModelDekMaterial(dekBytes),
                        signerId = signerId,
                        signer = ProtectedModelPackagingSigner { input ->
                            val signer = Signature.getInstance("Ed25519")
                            signer.initSign(pair.private)
                            signer.update(input)
                            ProtectedModelPackagingSignResult.Signed(
                                ProtectedModelPackagingSignature(signer.sign())
                            )
                        },
                        resourceBudgets = resourceBudgets,
                        packageBudgets = packageBudgets,
                        containerBudgets = ProtectedModelPackagingContainerBudgets(
                            localBudgets.maxContainerBytes,
                            localBudgets.maxSignatureBytes
                        ),
                        segmentPlaintextBytes = 1024 * 1024
                    )
                )
            )
            assertEquals(source.length(), packaged.plaintextBytes)

            val opened = assertIs<ProductProtectedModelLocalPackageOpenResult.Opened>(
                ProductProtectedModelLocalPackage.open(
                    destination,
                    resourceBudgets,
                    packageBudgets,
                    localBudgets
                )
            )

            val loader = LargeProtectedModelSegmentedPayloadLoader(
                packageVerifier = LargeProtectedModelPackageVerifier(
                    signerResolver = ProtectedModelSignerResolver { requested, algorithm ->
                        if (
                            requested == signerId &&
                            algorithm == ProtectedModelSignatureAlgorithm.ED25519
                        ) {
                            pair.public
                        } else {
                            null
                        }
                    },
                    budgets = packageBudgets
                ),
                dekResolver = ProtectedModelDekResolver { requestedModel, requestedDek ->
                    if (requestedModel == model && requestedDek == dek) {
                        SecretKeySpec(dekBytes.copyOf(), "AES")
                    } else {
                        null
                    }
                }
            )

            FileInputStream(source).use { expectedInput ->
                var comparedBytes = 0L
                val result = loader.open(opened.envelope, opened.source) { _, _, plaintext ->
                    val expected = ByteArray(plaintext.size)
                    var offset = 0
                    while (offset < expected.size) {
                        val count = expectedInput.read(expected, offset, expected.size - offset)
                        require(count > 0) { "real GGUF source truncated during comparison" }
                        offset += count
                    }
                    try {
                        assertContentEquals(expected, plaintext)
                    } finally {
                        expected.fill(0)
                    }
                    comparedBytes += plaintext.size
                }

                val completed = assertIs<LargeProtectedModelSegmentedOpenResult.Completed>(result)
                assertEquals(source.length(), completed.plaintextBytes)
                assertEquals(source.length(), comparedBytes)
                assertEquals(-1, expectedInput.read())
            }
        } finally {
            dekBytes.fill(0)
            root.deleteRecursively()
        }
    }
}
