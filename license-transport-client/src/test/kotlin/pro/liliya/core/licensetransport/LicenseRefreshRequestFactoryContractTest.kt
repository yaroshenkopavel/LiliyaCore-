package pro.liliya.core.licensetransport

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDigestTestVerifier
import pro.liliya.core.license.LicenseEntitlement
import pro.liliya.core.license.LicenseEntitlementCanonicalCodec
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseId
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion

class LicenseRefreshRequestFactoryContractTest {
    @Test
    fun refresh_scope_comes_from_verified_signed_license() {
        val key = LicenseTrustedVerificationKey.of(
            LicenseKeyId("test-key"), LicenseAlgorithm("TEST-SHA256"), byteArrayOf(1, 2, 3)
        )
        val payload = LicenseEntitlementCanonicalCodec.encode(
            LicenseEntitlement(
                id = LicenseId("license-1"),
                subject = LicenseSubject("signed-subject"),
                productId = LicenseProductId("signed-product"),
                features = setOf(LicenseFeature("chat")),
                version = LicenseVersion(1),
                signingKeyId = key.keyId,
                issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
                notBefore = Instant.parse("2026-01-01T00:00:00Z"),
                expiresAt = null,
                offlineLeaseUntil = null,
                revocationEpoch = LicenseRevocationEpoch(0),
                replaySequence = LicenseReplaySequence(3)
            )
        )
        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(key.algorithm),
                trustedKeys = LicenseTrustedKeyResolver { key },
                signatureVerifier = LicenseDigestTestVerifier
            ).verify(LicenseSignedEnvelope(
                LicenseVersion(1), key.algorithm, key.keyId, payload,
                LicenseDigestTestVerifier.signForTest(key, payload)
            ))
        )
        val request = LicenseRefreshRequestFactory.fromVerifiedLicense(
            verified, LicenseServiceRequestId("refresh-1")
        )
        assertEquals(LicenseServiceOperation.REFRESH, request.operation)
        assertEquals(LicenseSubject("signed-subject"), request.subjectReference)
        assertEquals(LicenseProductId("signed-product"), request.productId)
        assertEquals(LicenseServiceRequestId("refresh-1"), request.requestId)
    }
}
