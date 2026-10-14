package pro.liliya.app

import android.content.ContextWrapper
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeAdvisoryLlamaConfiguration
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership
import sun.misc.Unsafe

/**
 * A caller-supplied ASF configuration must never bypass accepted offline-resume policy identity.
 * Absence of an explicit opt-in preserves the existing production fail-closed path.
 */
class ProductionAndroidOfflineResumeAdvisoryOptInContractTest {
    @Test
    fun rejected_deployment_identity_stays_rejected_without_advisory_opt_in() {
        assertNull(
            ProductionAndroidOfflineResumeAcceptedProcessPolicyFactory.create(
                context = uninitialized<ContextWrapper>(),
                trust = uninitialized(),
                deploymentProfile = uninitialized()
            )
        )
    }

    @Test
    fun rejected_deployment_identity_stays_rejected_with_explicit_advisory_opt_in() {
        assertNull(
            ProductionAndroidOfflineResumeAcceptedProcessPolicyFactory.create(
                context = uninitialized<ContextWrapper>(),
                trust = uninitialized(),
                deploymentProfile = uninitialized(),
                advisoryLlama = uninitialized<AndroidProductRuntimeAdvisoryLlamaConfiguration>()
            )
        )
    }

    @Test
    fun product_input_factory_creation_remains_inert_without_explicit_opt_in() {
        // The context is deliberately unusable: construction must not touch Android storage,
        // admission, signed License state, or protected-model infrastructure.
        assertNotNull(
            ProductionAndroidOfflineResumeAcceptedProductInputFactory.create(
                context = uninitialized<ContextWrapper>()
            )
        )
    }

    @Test
    fun product_input_factory_creation_remains_inert_with_explicit_opt_in() {
        // Capturing an advisory configuration cannot itself launch or admit ASF workers.
        assertNotNull(
            ProductionAndroidOfflineResumeAcceptedProductInputFactory.create(
                context = uninitialized<ContextWrapper>(),
                advisoryLlama = uninitialized<AndroidProductRuntimeAdvisoryLlamaConfiguration>()
            )
        )
    }

    private inline fun <reified T> uninitialized(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
