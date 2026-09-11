package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith
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
import pro.liliya.packager.ProtectedModelOfflinePackager
import pro.liliya.packager.ProtectedModelPackagingContainerBudgets
import pro.liliya.packager.ProtectedModelPackagingRequest
import pro.liliya.packager.ProtectedModelPackagingResult
import pro.liliya.packager.ProtectedModelPackagingSignResult
import pro.liliya.packager.ProtectedModelPackagingSignature
import pro.liliya.packager.ProtectedModelPackagingSigner

@RunWith(AndroidJUnit4::class)
class ProtectedModelPackagerAndroidProbeInstrumentedTest {

    @Test
    fun exact_android_packager_rejection_reason_is_visible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val root = File(targetContext.filesDir, "protected-model-packager-android-probe")
        root.deleteRecursively()
        require(root.mkdirs())

        val source = File(root, STORIES_15M_ASSET)
        val destination = File(root, "stories15M-q4_0.lpm1")
        testContext.assets.open(STORIES_15M_ASSET).use { input ->
            FileOutputStream(source).use { output -> input.copyTo(output) }
        }

        val signerProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val signerKeyPair = KeyPairGenerator.getInstance("Ed25519", signerProvider).generateKeyPair()
        val dek = ByteArray(32) { index -> (index * 7 + 11).toByte() }

        try {
            val result = ProtectedModelOfflinePackager().packageFile(
                ProtectedModelPackagingRequest(
                    source = source,
                    destination = destination,
                    model = ProtectedModelReference(
                        ProtectedModelPackageId("first-working-liliya-stories15m"),
                        ProtectedModelGeneration(1)
                    ),
                    modelProfileId = ProtectedModelProfileId("first-working-liliya-real-model"),
                    modelDek = ModelDekReference(
                        ModelDekId("first-working-liliya-model-dek"),
                        ModelDekGeneration(1)
                    ),
                    modelDekMaterial = ProtectedModelDekMaterial(dek),
                    signerId = ProtectedModelSignerId("first-working-liliya-model-signer"),
                    signer = ProtectedModelPackagingSigner { input ->
                        val signer = Signature.getInstance("Ed25519", signerProvider)
                        signer.initSign(signerKeyPair.private)
                        signer.update(input)
                        ProtectedModelPackagingSignResult.Signed(
                            ProtectedModelPackagingSignature(signer.sign())
                        )
                    },
                    resourceBudgets = LargeProtectedModelResourceBudgets(
                        maxTotalPlaintextBytes = 32L * 1024L * 1024L,
                        maxTotalCiphertextBodyBytes = 32L * 1024L * 1024L,
                        maxTotalProtectedPayloadBytes = 33L * 1024L * 1024L,
                        maxSegmentCount = 128,
                        minNonFinalSegmentPlaintextBytes = 64L * 1024L,
                        maxSegmentPlaintextBytes = 1024L * 1024L,
                        maxSegmentCiphertextBodyBytes = 1024L * 1024L,
                        maxStructuralIdentifierChars = 256,
                        maxCanonicalManifestBytes = 2L * 1024L * 1024L
                    ),
                    packageBudgets = LargeProtectedModelPackageBudgets(
                        maxModelProfileIdChars = 128,
                        maxSignerIdChars = 128,
                        maxCanonicalSignedManifestBytes = 2L * 1024L * 1024L
                    ),
                    containerBudgets = ProtectedModelPackagingContainerBudgets(
                        maxContainerBytes = 24L * 1024L * 1024L,
                        maxSignatureBytes = 128
                    ),
                    segmentPlaintextBytes = 256 * 1024
                )
            )

            when (result) {
                is ProtectedModelPackagingResult.Packaged -> Unit
                is ProtectedModelPackagingResult.Rejected ->
                    fail("ANDROID_PACKAGER_REJECTED_REASON=${result.reason}")
            }
        } finally {
            dek.fill(0)
            root.deleteRecursively()
        }
    }

    private companion object {
        const val STORIES_15M_ASSET = "stories15M-q4_0.gguf"
    }
}
