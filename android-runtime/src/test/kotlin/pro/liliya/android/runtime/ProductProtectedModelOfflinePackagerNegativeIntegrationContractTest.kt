package pro.liliya.android.runtime

import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedOpenFailure
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

class ProductProtectedModelOfflinePackagerNegativeIntegrationContractTest {
    @Test
    fun wrong_signer_and_wrong_dek_fail_closed_on_packaged_output() {
        val root = Files.createTempDirectory("liliya-packager-negative").toFile()
        try {
            val source = root.resolve("source.gguf").also {
                it.writeBytes(ByteArray(90_000) { index -> (index * 19 + 11).toByte() })
            }
            val destination = root.resolve("source.lpm1")
            val model = ProtectedModelReference(
                ProtectedModelPackageId("negative-package"),
                ProtectedModelGeneration(3)
            )
            val dek = ModelDekReference(
                ModelDekId("negative-dek"),
                ModelDekGeneration(8)
            )
            val exactDek = ByteArray(32) { index -> (index + 1).toByte() }
            val signerId = ProtectedModelSignerId("negative-signer")
            val signingPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val wrongPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

            val resourceBudgets = LargeProtectedModelResourceBudgets(
                maxTotalPlaintextBytes = 500_000,
                maxTotalCiphertextBodyBytes = 500_000,
                maxTotalProtectedPayloadBytes = 600_000,
                maxSegmentCount = 16,
                minNonFinalSegmentPlaintextBytes = 4_096,
                maxSegmentPlaintextBytes = 65_536,
                maxSegmentCiphertextBodyBytes = 65_536,
                maxStructuralIdentifierChars = 256,
                maxCanonicalManifestBytes = 500_000
            )
            val packageBudgets = LargeProtectedModelPackageBudgets(
                maxModelProfileIdChars = 128,
                maxSignerIdChars = 128,
                maxCanonicalSignedManifestBytes = 600_000
            )
            val localBudgets = ProductProtectedModelLocalPackageBudgets(
                maxContainerBytes = 800_000,
                maxSignatureBytes = 128
            )

            assertIs<ProtectedModelPackagingResult.Packaged>(
                ProtectedModelOfflinePackager(
                    ProtectedModelPackagingNonceSource { index ->
                        ByteArray(12) { offset -> (index * 23 + offset + 2).toByte() }
                    }
                ).packageFile(
                    ProtectedModelPackagingRequest(
                        source = source,
                        destination = destination,
                        model = model,
                        modelProfileId = ProtectedModelProfileId("negative-profile"),
                        modelDek = dek,
                        modelDekMaterial = ProtectedModelDekMaterial(exactDek),
                        signerId = signerId,
                        signer = ProtectedModelPackagingSigner { input ->
                            val signer = Signature.getInstance("Ed25519")
                            signer.initSign(signingPair.private)
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
                        segmentPlaintextBytes = 65_536
                    )
                )
            )

            val opened = assertIs<ProductProtectedModelLocalPackageOpenResult.Opened>(
                ProductProtectedModelLocalPackage.open(
                    destination,
                    resourceBudgets,
                    packageBudgets,
                    localBudgets
                )
            )

            val wrongSignerLoader = LargeProtectedModelSegmentedPayloadLoader(
                packageVerifier = LargeProtectedModelPackageVerifier(
                    signerResolver = ProtectedModelSignerResolver { requested, algorithm ->
                        if (
                            requested == signerId &&
                            algorithm == ProtectedModelSignatureAlgorithm.ED25519
                        ) {
                            wrongPair.public
                        } else {
                            null
                        }
                    },
                    budgets = packageBudgets
                ),
                dekResolver = ProtectedModelDekResolver { _, _ ->
                    SecretKeySpec(exactDek.copyOf(), "AES")
                }
            )
            val wrongSigner = assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(
                wrongSignerLoader.open(opened.envelope, opened.source) { _, _, _ -> }
            )
            assertEquals(
                LargeProtectedModelSegmentedOpenFailure.PACKAGE_SIGNATURE_INVALID,
                wrongSigner.reason
            )

            val exactVerifier = LargeProtectedModelPackageVerifier(
                signerResolver = ProtectedModelSignerResolver { requested, algorithm ->
                    if (
                        requested == signerId &&
                        algorithm == ProtectedModelSignatureAlgorithm.ED25519
                    ) {
                        signingPair.public
                    } else {
                        null
                    }
                },
                budgets = packageBudgets
            )
            val wrongDekLoader = LargeProtectedModelSegmentedPayloadLoader(
                packageVerifier = exactVerifier,
                dekResolver = ProtectedModelDekResolver { requestedModel, requestedDek ->
                    if (requestedModel == model && requestedDek == dek) {
                        SecretKeySpec(ByteArray(32) { 0x5a.toByte() }, "AES")
                    } else {
                        null
                    }
                }
            )
            val wrongDek = assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(
                wrongDekLoader.open(opened.envelope, opened.source) { _, _, _ -> }
            )
            assertEquals(
                LargeProtectedModelSegmentedOpenFailure.AUTHENTICATED_DECRYPTION_FAILED,
                wrongDek.reason
            )

            RandomAccessFile(destination, "rw").use { file ->
                file.readInt() // LPM1 magic
                file.readInt() // version
                val manifestBytes = file.readInt()
                file.seek(file.filePointer + manifestBytes)
                val signatureBytes = file.readInt()
                file.seek(file.filePointer + signatureBytes)
                val segmentCount = file.readInt()
                require(segmentCount > 0)
                file.readInt() // first segment index
                val bodyBytes = file.readLong()
                file.readInt() // tag bytes
                require(bodyBytes > 0L)
                val bodyOffset = file.filePointer
                val original = file.readByte()
                file.seek(bodyOffset)
                file.writeByte(original.toInt() xor 0x01)
            }

            val reopenedAfterTamper =
                assertIs<ProductProtectedModelLocalPackageOpenResult.Opened>(
                    ProductProtectedModelLocalPackage.open(
                        destination,
                        resourceBudgets,
                        packageBudgets,
                        localBudgets
                    )
                )
            val tampered = assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(
                LargeProtectedModelSegmentedPayloadLoader(
                    packageVerifier = exactVerifier,
                    dekResolver = ProtectedModelDekResolver { requestedModel, requestedDek ->
                        if (requestedModel == model && requestedDek == dek) {
                            SecretKeySpec(exactDek.copyOf(), "AES")
                        } else {
                            null
                        }
                    }
                ).open(
                    reopenedAfterTamper.envelope,
                    reopenedAfterTamper.source
                ) { _, _, _ -> }
            )
            assertEquals(
                LargeProtectedModelSegmentedOpenFailure.PROTECTED_PAYLOAD_DIGEST_MISMATCH,
                tampered.reason
            )

            exactDek.fill(0)
        } finally {
            root.deleteRecursively()
        }
    }
}
