package pro.liliya.core.license

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPolicy
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseAuthorityCompositionContractTest {
    private val now = Instant.parse("2026-08-30T18:20:00Z")
    private val algorithm = LicenseAlgorithm("TEST-SHA256")
    private val key = LicenseTrustedVerificationKey.of(
        keyId = LicenseKeyId("license-authority-key"),
        algorithm = algorithm,
        material = "license-authority-test-key".toByteArray(StandardCharsets.UTF_8)
    )

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "license-authority-${sequence.incrementAndGet()}" }
        )
    }

    private fun entitlement(
        expiresAt: Instant = now.plusSeconds(600),
        feature: LicenseFeature = LicenseFeature("model.local")
    ) = LicenseEntitlement(
        id = LicenseId("license-authority-1"),
        subject = LicenseSubject("private-license-subject"),
        productId = LicenseProductId("liliya-pro"),
        features = setOf(feature),
        version = LicenseVersion(1),
        signingKeyId = key.keyId,
        issuedAt = now.minusSeconds(60),
        notBefore = now.minusSeconds(30),
        expiresAt = expiresAt,
        offlineLeaseUntil = null,
        revocationEpoch = LicenseRevocationEpoch(2),
        replaySequence = LicenseReplaySequence(9)
    )

    private fun verified(entitlement: LicenseEntitlement): LicenseVerificationResult.Verified {
        val payload = LicenseEntitlementCanonicalCodec.encode(entitlement)
        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = algorithm,
            signingKeyId = key.keyId,
            payload = payload,
            signature = LicenseDigestTestVerifier.signForTest(key, payload)
        )
        return assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(algorithm),
                trustedKeys = LicenseTrustedKeyResolver { requested -> if (requested == key.keyId) key else null },
                signatureVerifier = LicenseDigestTestVerifier
            ).verify(envelope)
        )
    }

    private fun licenseRequest(feature: LicenseFeature = LicenseFeature("model.local")) =
        LicensePolicyRequest(
            productId = LicenseProductId("liliya-pro"),
            feature = feature
        )

    private fun policyContext(at: Instant = now) = LicensePolicyContext(
        now = at,
        minimumRevocationEpoch = LicenseRevocationEpoch(2),
        minimumReplaySequence = LicenseReplaySequence(9)
    )

    private fun authorityRequest() = LicenseAuthorityRequest(
        principal = AuthorityPrincipal("assistant-core"),
        capability = CapabilityId("model.execute"),
        scope = AuthorityScope("local-model")
    )

    @Test
    fun entitled_license_creates_fresh_exact_authority_request_and_requires_authority_grant() {
        val foundation = foundation()
        var observed: AuthorityRequest? = null
        val authority = AuthorityManager(
            policy = AuthorityPolicy { request ->
                observed = request
                AuthorityDecision.Granted
            },
            observability = foundation.observability
        )
        val composition = LicenseAuthorityComposition(foundation, authority)

        val result = assertIs<LicenseAuthorityDecision.Authorized>(
            composition.authorize(
                verified = verified(entitlement()),
                licenseRequest = licenseRequest(),
                policyContext = policyContext(),
                authorityRequest = authorityRequest()
            )
        )

        assertEquals(LicenseId("license-authority-1"), result.licenseReceipt.licenseId)
        assertEquals(AuthorityPrincipal("assistant-core"), observed?.principal)
        assertEquals(CapabilityId("model.execute"), observed?.capability)
        assertEquals(AuthorityScope("local-model"), observed?.scope)
        assertEquals("licensed protected operation", observed?.reason)
    }

    @Test
    fun license_denial_performs_zero_authority_calls() {
        val foundation = foundation()
        var authorityCalls = 0
        val authority = AuthorityManager(
            policy = AuthorityPolicy {
                authorityCalls++
                AuthorityDecision.Granted
            },
            observability = foundation.observability
        )
        val composition = LicenseAuthorityComposition(foundation, authority)

        val result = composition.authorize(
            verified = verified(entitlement()),
            licenseRequest = licenseRequest(feature = LicenseFeature("cloud.premium")),
            policyContext = policyContext(),
            authorityRequest = authorityRequest()
        )

        assertEquals(
            LicenseDenialReason.FEATURE_NOT_ENTITLED,
            assertIs<LicenseAuthorityDecision.LicenseDenied>(result).reason
        )
        assertEquals(0, authorityCalls)
    }

    @Test
    fun authority_denial_does_not_become_authorized_license_execution() {
        val foundation = foundation()
        val authority = AuthorityManager(
            policy = AuthorityPolicy { AuthorityDecision.Denied("structural authority denial") },
            observability = foundation.observability
        )
        val composition = LicenseAuthorityComposition(foundation, authority)

        assertIs<LicenseAuthorityDecision.AuthorityDenied>(
            composition.authorize(
                verified = verified(entitlement()),
                licenseRequest = licenseRequest(),
                policyContext = policyContext(),
                authorityRequest = authorityRequest()
            )
        )
    }

    @Test
    fun every_call_re_evaluates_license_policy_so_old_entitlement_receipt_cannot_bypass_expiry() {
        val foundation = foundation()
        var authorityCalls = 0
        val authority = AuthorityManager(
            policy = AuthorityPolicy {
                authorityCalls++
                AuthorityDecision.Granted
            },
            observability = foundation.observability
        )
        val composition = LicenseAuthorityComposition(foundation, authority)
        val expiry = now.plusSeconds(10)
        val evidence = verified(entitlement(expiresAt = expiry))

        assertIs<LicenseAuthorityDecision.Authorized>(
            composition.authorize(
                verified = evidence,
                licenseRequest = licenseRequest(),
                policyContext = policyContext(at = expiry.minusNanos(1)),
                authorityRequest = authorityRequest()
            )
        )
        assertEquals(1, authorityCalls)

        val expired = composition.authorize(
            verified = evidence,
            licenseRequest = licenseRequest(),
            policyContext = policyContext(at = expiry),
            authorityRequest = authorityRequest()
        )
        assertEquals(
            LicenseDenialReason.EXPIRED,
            assertIs<LicenseAuthorityDecision.LicenseDenied>(expired).reason
        )
        assertEquals(1, authorityCalls)
    }
}
