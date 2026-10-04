package pro.liliya.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.runtime.AndroidHeartProductionPersonaDefinition
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelBudgetInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelStagingProvisioning
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveOutcomeMaterializationPort
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.persistence.PersistentStoreId
import sun.misc.Unsafe

@RunWith(AndroidJUnit4::class)
class PhysicalProductionOfflineResumeStartupInstallInstrumentedTest {
    @Test
    fun cold_process_installs_fresh_startup_and_authority_without_execution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            application.runtimeOwner.state()
        )
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
            application.observeApplicationChat { }
        )

        val principal = AuthorityPrincipal(PRINCIPAL)
        val capability = CapabilityId(CAPABILITY)
        val policy = ProductionAndroidOfflineResumeProcessPolicy(
            admission = ProductionAndroidOfflineResumeAdmissionPolicy(
                feature = FEATURE,
                principal = principal.value,
                capability = capability.value,
                authorityScope = AuthorityScope.GLOBAL.value
            ),
            authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = listOf(
                    CapabilityDescriptor(
                        id = capability,
                        providerId = CapabilityProviderId("physical-offline-resume-provider")
                    )
                ),
                directGrants = listOf(
                    DirectAuthorityGrant(
                        principal = principal,
                        capability = capability,
                        scope = AuthorityScope.GLOBAL
                    )
                )
            ),
            protectedModelBudgets = AndroidProductRuntimeProtectedModelBudgetInput(
                maxTotalPlaintextBytes = 2_000_000_000L,
                maxTotalCiphertextBodyBytes = 2_100_000_000L,
                maxTotalProtectedPayloadBytes = 2_200_000_000L,
                maxSegmentCount = 1024,
                minNonFinalSegmentPlaintextBytes = 64L * 1024L,
                maxSegmentPlaintextBytes = 8L * 1024L * 1024L,
                maxSegmentCiphertextBodyBytes = 8L * 1024L * 1024L + 64L,
                maxStructuralIdentifierChars = 256,
                maxCanonicalManifestBytes = 4L * 1024L * 1024L,
                maxModelProfileIdChars = 128,
                maxSignerIdChars = 128,
                maxCanonicalSignedManifestBytes = 4L * 1024L * 1024L,
                maxContainerBytes = 2_400_000_000L,
                maxSignatureBytes = 4096
            ),
            staging = stagingProvisioning(),
            preparedInputOwners = AndroidProductRuntimeStartupPreparedInputOwnerTemplate(
                memoryStoreId = PersistentStoreId("offline-resume-placeholder-memory"),
                knowledgeStoreId = PersistentStoreId("offline-resume-placeholder-knowledge"),
                llamaAssembly = allocateWithoutConstructor(),
                maxCandidatesPerSource = 1,
                personaDefinition = allocateWithoutConstructor<AndroidHeartProductionPersonaDefinition>(),
                scope = CognitiveRuntimeScopeId("physical-offline-resume-runtime"),
                cognitiveMaterialization = proxy(),
                outcomeMaterialization = proxy(),
                policies = allocateWithoutConstructor<LearningPolicyComposition>(),
                policyReference = allocateWithoutConstructor<LearningPolicyReference>(),
                principal = principal,
                governance = proxy(),
                learningMaterialization = proxy(),
                learningMutationStoreId = PersistentStoreId("offline-resume-placeholder-learning"),
                artifactIds = proxy(),
                timestamps = proxy(),
                limits = CognitiveRuntimeLimits()
            )
        )

        assertTrue(application.configureOfflineResumePolicy(policy))
        val attempt = application.attemptOfflineResume()
        if (attempt is ProductionAndroidOfflineResumeBootstrapResult.Rejected) {
            var installDiagnostic = "NOT_RUN"
            if (attempt.reason == ProductionAndroidOfflineResumeBootstrapFailure.INSTALL_REJECTED) {
                val material = assertIs<ProductionAndroidOfflineResumeMaterialLoadResult.Ready>(
                    ProductionAndroidOfflineResumeMaterialLoader.load(
                        instrumentation.targetContext
                    )
                ).material
                val reconstructed =
                    ProductionAndroidOfflineResumeDefaultProductInputFactory
                        .create(instrumentation.targetContext, policy)
                        .create(material)
                installDiagnostic = if (reconstructed == null) {
                    "RECONSTRUCTION_NULL"
                } else {
                    when (
                        val direct = ProductionAndroidFirstRunProductInstall
                            .prepareAndInstall(reconstructed)
                    ) {
                        is ProductionAndroidFirstRunProductInstallResult.Installed -> "INSTALLED"
                        is ProductionAndroidFirstRunProductInstallResult.ProductInputRejected ->
                            "PRODUCT_INPUT_REJECTED_" + direct.reason.name
                        ProductionAndroidFirstRunProductInstallResult.AlreadyConfigured ->
                            "ALREADY_CONFIGURED"
                        ProductionAndroidFirstRunProductInstallResult.TrustVerificationRejected ->
                            "TRUST_VERIFICATION_REJECTED"
                        is ProductionAndroidFirstRunProductInstallResult.AuthorityRejected ->
                            "AUTHORITY_REJECTED_" + direct.reason.name
                        ProductionAndroidFirstRunProductInstallResult.DurableLicenseRejected ->
                            "DURABLE_LICENSE_REJECTED"
                        ProductionAndroidFirstRunProductInstallResult.Failed -> "FAILED"
                    }
                }
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("offlineResumeStartup.rejection", attempt.reason.name)
                putString(
                    "offlineResumeStartup.materialFailure",
                    attempt.materialFailure?.name ?: "NONE"
                )
                putString("offlineResumeStartup.installDiagnostic", installDiagnostic)
            })
        }
        val result = assertIs<ProductionAndroidOfflineResumeBootstrapResult.Ready>(attempt)
        val installed = assertIs<ProductionAndroidFirstRunProductInstallResult.Installed>(
            result.install
        )

        assertNotNull(ProductionAndroidRuntimeStartupInputConfiguration.current())
        assertTrue(installed.ownership.authority.capabilities.isNotEmpty())
        assertTrue(installed.ownership.authority.directGrants.isNotEmpty())
        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            application.runtimeOwner.state()
        )
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
            application.observeApplicationChat { }
        )

        instrumentation.sendStatus(2, Bundle().apply {
            putString("offlineResumeStartup.materialLoaded", "true")
            putString("offlineResumeStartup.licenseReverified", "true")
            putString("offlineResumeStartup.freshAdmissionBuilt", "true")
            putString("offlineResumeStartup.startupInstalled", "true")
            putString("offlineResumeStartup.freshAuthorityCreated", "true")
            putString("offlineResumeStartup.executionStarted", "false")
            putString("offlineResumeStartup.chatReplay", "absent")
            putString("offlineResumeStartup.runtimeState", "CONFIGURATION_REQUIRED")
        })
    }

    private fun stagingProvisioning(): AndroidProductRuntimeProtectedModelStagingProvisioning {
        val constructor =
            AndroidProductRuntimeProtectedModelStagingProvisioning::class.java
                .declaredConstructors
                .single()
        constructor.isAccessible = true
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as Unsafe
        val arguments = constructor.parameterTypes
            .map { parameterType -> unsafe.allocateInstance(parameterType) }
            .toTypedArray()
        return constructor.newInstance(*arguments)
            as AndroidProductRuntimeProtectedModelStagingProvisioning
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }

    private inline fun <reified T> proxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Void.TYPE -> null
                else -> null
            }
        } as T
    }

    companion object {
        private const val FEATURE = "model.local"
        private const val PRINCIPAL = "liliya-product-runtime"
        private const val CAPABILITY = "model.local"
    }
}
