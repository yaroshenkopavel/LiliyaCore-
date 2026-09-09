package pro.liliya.android.runtime

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidProductRuntimeStartupAdmissionChoiceFactoryContractTest {
    @Test
    fun explicit_choice_maps_exact_license_and_authority_inputs() {
        val now = Instant.parse("2026-09-09T20:00:00Z")
        val result = AndroidProductRuntimeStartupAdmissionChoiceFactory.create(
            AndroidProductRuntimeStartupAdmissionChoice(
                productId = "liliya-product",
                feature = "runtime.start",
                subject = "device-subject",
                now = now,
                minimumRevocationEpoch = 3,
                minimumReplaySequence = 11,
                suspiciousTimeOrReplayState = false,
                principal = "liliya",
                admissionCapabilityId = "runtime.start",
                admissionScope = "global",
                grants = listOf(
                    AndroidProductRuntimeStartupAuthorityGrantChoice(
                        capabilityId = "runtime.start",
                        providerId = "liliya.runtime",
                        scope = "global"
                    ),
                    AndroidProductRuntimeStartupAuthorityGrantChoice(
                        capabilityId = "learning.application.apply",
                        providerId = "liliya.runtime",
                        scope = "learning.application.memory"
                    )
                )
            )
        )

        val ready = assertIs<AndroidProductRuntimeStartupAdmissionChoiceResult.Ready>(result)
        assertEquals("liliya-product", ready.licenseRequest.productId.value)
        assertEquals("runtime.start", ready.licenseRequest.feature.value)
        assertEquals("device-subject", ready.licenseRequest.subject?.value)
        assertEquals(now, ready.policyContext.now)
        assertEquals(3L, ready.policyContext.minimumRevocationEpoch.value)
        assertEquals(11L, ready.policyContext.minimumReplaySequence?.value)
        assertEquals("liliya", ready.authorityRequest.principal.value)
        assertEquals("runtime.start", ready.authorityRequest.capability.value)
        assertEquals("global", ready.authorityRequest.scope.value)
        assertEquals(
            listOf("runtime.start", "learning.application.apply"),
            ready.authorityPlan.capabilities.map { it.id.value }
        )
        assertEquals(
            listOf("global", "learning.application.memory"),
            ready.authorityPlan.directGrants.map { it.scope.value }
        )
    }

    @Test
    fun admission_grant_must_be_explicitly_present() {
        val result = AndroidProductRuntimeStartupAdmissionChoiceFactory.create(
            AndroidProductRuntimeStartupAdmissionChoice(
                productId = "liliya-product",
                feature = "runtime.start",
                subject = null,
                now = Instant.parse("2026-09-09T20:00:00Z"),
                minimumRevocationEpoch = 0,
                minimumReplaySequence = null,
                suspiciousTimeOrReplayState = false,
                principal = "liliya",
                admissionCapabilityId = "runtime.start",
                admissionScope = "global",
                grants = listOf(
                    AndroidProductRuntimeStartupAuthorityGrantChoice(
                        capabilityId = "learning.application.apply",
                        providerId = "liliya.runtime",
                        scope = "learning.application.memory"
                    )
                )
            )
        )

        assertIs<AndroidProductRuntimeStartupAdmissionChoiceResult.Rejected>(result)
    }

    @Test
    fun conflicting_provider_for_same_capability_is_rejected() {
        val result = AndroidProductRuntimeStartupAdmissionChoiceFactory.create(
            AndroidProductRuntimeStartupAdmissionChoice(
                productId = "liliya-product",
                feature = "runtime.start",
                subject = null,
                now = Instant.parse("2026-09-09T20:00:00Z"),
                minimumRevocationEpoch = 0,
                minimumReplaySequence = null,
                suspiciousTimeOrReplayState = false,
                principal = "liliya",
                admissionCapabilityId = "runtime.start",
                admissionScope = "global",
                grants = listOf(
                    AndroidProductRuntimeStartupAuthorityGrantChoice(
                        capabilityId = "runtime.start",
                        providerId = "provider-a",
                        scope = "global"
                    ),
                    AndroidProductRuntimeStartupAuthorityGrantChoice(
                        capabilityId = "runtime.start",
                        providerId = "provider-b",
                        scope = "global"
                    )
                )
            )
        )

        assertIs<AndroidProductRuntimeStartupAdmissionChoiceResult.Rejected>(result)
    }

    @Test
    fun invalid_explicit_values_are_rejected_without_defaults() {
        val result = AndroidProductRuntimeStartupAdmissionChoiceFactory.create(
            AndroidProductRuntimeStartupAdmissionChoice(
                productId = "",
                feature = "runtime.start",
                subject = null,
                now = Instant.parse("2026-09-09T20:00:00Z"),
                minimumRevocationEpoch = 0,
                minimumReplaySequence = null,
                suspiciousTimeOrReplayState = false,
                principal = "liliya",
                admissionCapabilityId = "runtime.start",
                admissionScope = "global",
                grants = listOf(
                    AndroidProductRuntimeStartupAuthorityGrantChoice(
                        capabilityId = "runtime.start",
                        providerId = "liliya.runtime",
                        scope = "global"
                    )
                )
            )
        )

        assertIs<AndroidProductRuntimeStartupAdmissionChoiceResult.Rejected>(result)
    }
}
