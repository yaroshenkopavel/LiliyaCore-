package pro.liliya.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.ActivationRedemptionHttpClient
import pro.liliya.core.licensetransport.ActivationRedemptionHttpRequest
import pro.liliya.core.licensetransport.ActivationRedemptionHttpResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.persistence.PersistentStoreId

@RunWith(AndroidJUnit4::class)
class PhysicalProductionOfflineResumeSecurityBaselineInstrumentedTest {
    @Test
    fun real_activation_seeds_only_durable_offline_resume_security_material() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try {
            LicenseHttpTlsTrust.ofCertificate(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(context).loadOrCreate()
        ).binding
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )

        val codeFile = File(context.filesDir, ACTIVATION_CODE_FILE)
        assertTrue(codeFile.isFile)
        val activationCode = codeFile.readText(Charsets.UTF_8).trim()
        assertTrue(activationCode.isNotBlank())
        assertTrue(codeFile.delete())

        val activated = assertIs<ActivationRedemptionHttpResult.Activated>(
            ActivationRedemptionHttpClient(
                LicenseHttpTransportConfig(
                    endpoint = endpoint,
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 60_000,
                    developmentAllowInsecureHttp = false,
                    tlsTrust = tlsTrust
                )
            ).redeem(
                ActivationRedemptionHttpRequest(
                    activationCode = activationCode,
                    attemptId = ProductionAndroidActivationAttemptIdentity.loadOrCreate(context),
                    installationId = binding.installationId,
                    deviceKeyFingerprint = binding.deviceKeyFingerprint
                )
            )
        )

        val publicKey = Base64.getDecoder().decode(
            required(args.getString(ARG_LICENSE_KEY_BASE64))
        )
        val trusted = LicenseTrustedVerificationKey.of(
            keyId = activated.license.signingKeyId,
            algorithm = LicenseAlgorithm(ALGORITHM),
            material = publicKey
        )
        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(activated.license)
        )

        val entitlement = verified.entitlement
        assertEquals(required(args.getString(ARG_PRODUCT)), entitlement.productId.value)
        assertEquals(expectedBinding, assertNotNull(entitlement.deviceBindingReference))
        assertNull(entitlement.expiresAt)
        assertNull(entitlement.offlineLeaseUntil)
        assertTrue(entitlement.features.any { it.value == FEATURE })

        assertIs<ProductionAndroidActivatedLicenseStoreResult.Stored>(
            ProductionAndroidActivatedLicenseEncryptedStore.create(context).store(
                activated.license
            )
        )

        assertIs<ProductionAndroidOfflineDeploymentProfileStoreResult.Stored>(
            ProductionAndroidOfflineDeploymentProfileEncryptedStore.create(context).store(
                ProductionAndroidOfflineDeploymentProfile(
                    productId = entitlement.productId.value,
                    endpoint = endpoint.toExternalForm(),
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 60_000,
                    tlsCertificates = tlsTrust.copyCertificates(),
                    supportedLicenseSchemaVersion = 1,
                    licenseTrustKeys = listOf(
                        ProductionAndroidOfflineDeploymentLicenseTrustKey(
                            keyId = activated.license.signingKeyId.value,
                            material = publicKey
                        )
                    ),
                    semanticDirectoryName = SEMANTIC_DIRECTORY,
                    cognitiveStorageDirectoryName = COGNITIVE_DIRECTORY
                )
            )
        )

        assertIs<ProductionAndroidOfflineResumeMetadataStoreResult.Stored>(
            ProductionAndroidOfflineResumeMetadataEncryptedStore.create(context).store(
                ProductionAndroidOfflineResumeMetadata(
                    activeDek = CognitiveDekReference(
                        id = CognitiveDekId("physical-offline-resume-security-dek"),
                        generation = CognitiveDekGeneration(1)
                    ),
                    memoryStoreId = PersistentStoreId("physical-offline-resume-memory"),
                    knowledgeStoreId = PersistentStoreId("physical-offline-resume-knowledge"),
                    learningMutationStoreId =
                        PersistentStoreId("physical-offline-resume-learning-mutations"),
                    cognitiveStorageDirectoryName = COGNITIVE_DIRECTORY,
                    semanticDirectoryName = SEMANTIC_DIRECTORY
                )
            )
        )

        val modelSelection = ProductionAndroidLocalModelSelection.importSelected(
            directory = File(context.filesDir, "models"),
            openInput = {
                ByteArrayInputStream("offline-resume-security-placeholder".encodeToByteArray())
            }
        )
        assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(modelSelection)

        publicKey.fill(0)

        instrumentation.sendStatus(2, Bundle().apply {
            putString("offlineResumeBaseline.realSignedLicense", "true")
            putString("offlineResumeBaseline.deviceBound", "true")
            putString("offlineResumeBaseline.unlimitedOffline", "true")
            putString("offlineResumeBaseline.activatedLicenseDurable", "true")
            putString("offlineResumeBaseline.deploymentProfileDurable", "true")
            putString("offlineResumeBaseline.resumeMetadataDurable", "true")
            putString("offlineResumeBaseline.modelPointerDurable", "true")
            putString("offlineResumeBaseline.authorityCreated", "false")
            putString("offlineResumeBaseline.executionStarted", "false")
        })
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical offline-resume argument")

    companion object {
        private const val ARG_ENDPOINT = "activationEndpoint"
        private const val ARG_CA_BASE64 = "activationCaBase64"
        private const val ARG_LICENSE_KEY_BASE64 = "activationLicenseKeyDerBase64"
        private const val ARG_PRODUCT = "activationProductId"
        private const val ACTIVATION_CODE_FILE = "physical-activation-code.once"
        private const val ALGORITHM = "ECDSA-P256-SHA256"
        private const val FEATURE = "model.local"
        private const val SEMANTIC_DIRECTORY = "physical-offline-resume-semantic"
        private const val COGNITIVE_DIRECTORY = "physical-offline-resume-cognitive"
    }
}
