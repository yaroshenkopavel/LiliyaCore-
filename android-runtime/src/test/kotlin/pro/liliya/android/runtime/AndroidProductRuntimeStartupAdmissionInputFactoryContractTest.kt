package pro.liliya.android.runtime

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseSubject

class AndroidProductRuntimeStartupAdmissionInputFactoryContractTest {
    @Test
    fun exact_product_security_values_are_preserved() {
        val now = Instant.parse("2026-09-10T10:00:00Z")
        val result = AndroidProductRuntimeStartupAdmissionInputFactory.create(
            AndroidProductRuntimeStartupAdmissionInput(
                productId = "liliya-core",
                feature = "runtime",
                subject = "device-owner",
                now = now,
                minimumRevocationEpoch = 4L,
                minimumReplaySequence = 9L,
                suspiciousTimeOrReplayState = true,
                principal = "liliya",
                capability = "runtime.start",
                authorityScope = "global"
            )
        )

        val ready = assertIs<AndroidProductRuntimeStartupAdmissionInputResult.Ready>(result)
        assertEquals(LicenseProductId("liliya-core"), ready.contracts.licenseRequest.productId)
        assertEquals(LicenseFeature("runtime"), ready.contracts.licenseRequest.feature)
        assertEquals(LicenseSubject("device-owner"), ready.contracts.licenseRequest.subject)
        assertEquals(now, ready.contracts.policyContext.now)
        assertEquals(
            LicenseRevocationEpoch(4),
            ready.contracts.policyContext.minimumRevocationEpoch
        )
        assertEquals(
            LicenseReplaySequence(9),
            ready.contracts.policyContext.minimumReplaySequence
        )
        assertEquals(true, ready.contracts.policyContext.suspiciousTimeOrReplayState)
        assertEquals(
            AuthorityPrincipal("liliya"),
            ready.contracts.authorityRequest.principal
        )
        assertEquals(
            CapabilityId("runtime.start"),
            ready.contracts.authorityRequest.capability
        )
        assertEquals(
            AuthorityScope("global"),
            ready.contracts.authorityRequest.scope
        )
    }

    @Test
    fun nullable_subject_and_replay_sequence_are_preserved_as_absent() {
        val result = AndroidProductRuntimeStartupAdmissionInputFactory.create(
            AndroidProductRuntimeStartupAdmissionInput(
                productId = "liliya-core",
                feature = "runtime",
                subject = null,
                now = Instant.parse("2026-09-10T10:00:00Z"),
                minimumRevocationEpoch = 0L,
                minimumReplaySequence = null,
                suspiciousTimeOrReplayState = false,
                principal = "liliya",
                capability = "runtime.start",
                authorityScope = "global"
            )
        )

        val ready = assertIs<AndroidProductRuntimeStartupAdmissionInputResult.Ready>(result)
        assertEquals(null, ready.contracts.licenseRequest.subject)
        assertEquals(null, ready.contracts.policyContext.minimumReplaySequence)
    }

    @Test
    fun invalid_explicit_input_is_rejected_without_default_substitution() {
        val result = AndroidProductRuntimeStartupAdmissionInputFactory.create(
            AndroidProductRuntimeStartupAdmissionInput(
                productId = "liliya-core",
                feature = "",
                subject = null,
                now = Instant.parse("2026-09-10T10:00:00Z"),
                minimumRevocationEpoch = 0L,
                minimumReplaySequence = null,
                suspiciousTimeOrReplayState = false,
                principal = "liliya",
                capability = "runtime.start",
                authorityScope = "global"
            )
        )

        assertIs<AndroidProductRuntimeStartupAdmissionInputResult.Rejected>(result)
    }
}
