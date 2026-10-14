package pro.liliya.app

import android.content.ContextWrapper
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test
    fun absent_asf_opt_in_does_not_block_standard_offline_resume() {
        assertTrue(ProductionAndroidAsfAvailabilityGate.accepts(null))
    }

    @Test
    fun explicit_asf_opt_in_must_be_available_and_fail_closed() {
        assertTrue(ProductionAndroidAsfAvailabilityGate.accepts { true })
        assertFalse(ProductionAndroidAsfAvailabilityGate.accepts { false })
        assertFalse(ProductionAndroidAsfAvailabilityGate.accepts { error("unavailable") })
    }

    @Test
    fun availability_is_rechecked_after_session_state_changes() {
        var ready = true
        var evaluations = 0
        val liveAvailability = {
            evaluations += 1
            ready
        }

        assertTrue(ProductionAndroidAsfAvailabilityGate.accepts(liveAvailability))
        ready = false
        assertFalse(ProductionAndroidAsfAvailabilityGate.accepts(liveAvailability))
        ready = true
        assertTrue(ProductionAndroidAsfAvailabilityGate.accepts(liveAvailability))
        assertTrue(evaluations == 3)
    }

    private inline fun <reified T> uninitialized(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
