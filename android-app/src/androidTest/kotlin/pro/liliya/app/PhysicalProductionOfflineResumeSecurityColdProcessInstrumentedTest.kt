package pro.liliya.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership
import pro.liliya.core.authority.AuthorityScope

@RunWith(AndroidJUnit4::class)
class PhysicalProductionOfflineResumeSecurityColdProcessInstrumentedTest {
    @Test
    fun cold_process_reverifies_durable_license_and_builds_fresh_admission_without_execution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as LiliyaApplication

        assertNull(ProductionAndroidRuntimeStartupInputConfiguration.current())
        assertNull(ProductionAndroidOfflineResumePolicyOwner.current())
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
            application.observeApplicationChat { }
        )
        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            application.runtimeOwner.state()
        )

        val material = assertIs<ProductionAndroidOfflineResumeMaterialLoadResult.Ready>(
            ProductionAndroidOfflineResumeMaterialLoader.load(context)
        ).material

        val trust = assertIs<ProductionAndroidOfflineResumeTrustResult.Ready>(
            ProductionAndroidOfflineResumeTrustVerifier.verify(
                context = context,
                material = material
            )
        ).ownership

        val admission = assertIs<ProductionAndroidOfflineResumeAdmissionResult.Ready>(
            ProductionAndroidOfflineResumeAdmissionBuilder.build(
                context = context,
                material = material,
                trust = trust,
                policy = ProductionAndroidOfflineResumeAdmissionPolicy(
                    feature = FEATURE,
                    principal = PRINCIPAL,
                    capability = CAPABILITY,
                    authorityScope = AuthorityScope.GLOBAL.value
                )
            )
        )

        assertEquals(material.deploymentProfile.productId, admission.input.productId)
        assertEquals(FEATURE, admission.input.feature)
        assertEquals(PRINCIPAL, admission.input.principal)
        assertEquals(CAPABILITY, admission.input.capability)
        assertNotNull(admission.input.requiredDeviceBindingReference)

        assertNull(ProductionAndroidRuntimeStartupInputConfiguration.current())
        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            application.runtimeOwner.state()
        )
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
            application.observeApplicationChat { }
        )

        instrumentation.sendStatus(2, Bundle().apply {
            putString("offlineResumeCold.materialLoaded", "true")
            putString("offlineResumeCold.licenseReverified", "true")
            putString("offlineResumeCold.deviceBindingMatched", "true")
            putString("offlineResumeCold.securityFloorsApplied", "true")
            putString("offlineResumeCold.freshAdmissionBuilt", "true")
            putString("offlineResumeCold.startupInstalled", "false")
            putString("offlineResumeCold.authorityGrantCreated", "false")
            putString("offlineResumeCold.executionStarted", "false")
            putString("offlineResumeCold.chatReplay", "absent")
            putString("offlineResumeCold.runtimeState", "CONFIGURATION_REQUIRED")
        })
    }

    companion object {
        private const val FEATURE = "model.local"
        private const val PRINCIPAL = "liliya-product-runtime"
        private const val CAPABILITY = "model.local"
    }
}
