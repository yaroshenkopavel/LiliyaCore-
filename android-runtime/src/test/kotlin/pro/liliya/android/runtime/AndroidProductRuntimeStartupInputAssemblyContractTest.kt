package pro.liliya.android.runtime

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.license.LicenseVerificationRejection
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.LoggerProvider

class AndroidProductRuntimeStartupInputAssemblyContractTest {
    @Test
    fun trust_rejection_stops_before_authority_registration() {
        val result = AndroidProductRuntimeStartupInputAssembly.resolve(
            trustResult = AndroidProductRuntimeStartupTrustAssemblyResult.VerificationRejected(
                LicenseVerificationRejection.INVALID_SIGNATURE
            ),
            authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = emptyList(),
                directGrants = emptyList()
            ),
            buildPort = AndroidProductRuntimeStartupInputBuildPort { _, _ ->
                error("must not build")
            }
        )

        val rejected = assertIs<AndroidProductRuntimeStartupInputAssemblyResult.TrustVerificationRejected>(
            result
        )
        assertEquals(LicenseVerificationRejection.INVALID_SIGNATURE, rejected.reason)
    }

    @Test
    fun authority_rejection_stops_before_source_input_build() {
        val trust = trustOwnership()
        val grant = DirectAuthorityGrant(
            principal = AuthorityPrincipal("liliya"),
            capability = CapabilityId("runtime.missing"),
            scope = AuthorityScope.GLOBAL
        )
        var buildCalls = 0

        val result = AndroidProductRuntimeStartupInputAssembly.resolve(
            trustResult = AndroidProductRuntimeStartupTrustAssemblyResult.Ready(trust),
            authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = emptyList(),
                directGrants = listOf(grant)
            ),
            buildPort = AndroidProductRuntimeStartupInputBuildPort { _, _ ->
                buildCalls += 1
                error("must not build")
            }
        )

        val rejected = assertIs<AndroidProductRuntimeStartupInputAssemblyResult.AuthorityRejected>(result)
        assertEquals(
            AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED,
            rejected.reason
        )
        assertEquals(0, buildCalls)
        assertTrue(trust.capabilityAuthority.capabilitySnapshot().isEmpty())
        assertTrue(trust.capabilityAuthority.directGrantSnapshot().isEmpty())
    }

    @Test
    fun source_input_build_exception_rolls_back_registered_authority() {
        val trust = trustOwnership()
        val capability = CapabilityDescriptor(
            id = CapabilityId("runtime.chat"),
            providerId = CapabilityProviderId("liliya.runtime")
        )
        val grant = DirectAuthorityGrant(
            principal = AuthorityPrincipal("liliya"),
            capability = capability.id,
            scope = AuthorityScope.GLOBAL
        )

        val result = AndroidProductRuntimeStartupInputAssembly.resolve(
            trustResult = AndroidProductRuntimeStartupTrustAssemblyResult.Ready(trust),
            authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = listOf(capability),
                directGrants = listOf(grant)
            ),
            buildPort = AndroidProductRuntimeStartupInputBuildPort { _, _ ->
                error("private source-input failure")
            }
        )

        assertIs<AndroidProductRuntimeStartupInputAssemblyResult.Failed>(result)
        assertTrue(trust.capabilityAuthority.capabilitySnapshot().isEmpty())
        assertTrue(trust.capabilityAuthority.directGrantSnapshot().isEmpty())
    }

    private fun trustOwnership(): AndroidProductRuntimeStartupTrustOwnership {
        val algorithm = pro.liliya.core.license.LicenseAlgorithm("ECDSA-P256-SHA256")
        val schemaVersion = pro.liliya.core.license.LicenseVersion(1)
        val keyId = pro.liliya.core.license.LicenseKeyId("startup-input-trust-key")
        val entitlement = pro.liliya.core.license.LicenseEntitlement(
            id = pro.liliya.core.license.LicenseId("startup-input-license"),
            subject = pro.liliya.core.license.LicenseSubject("startup-input-subject"),
            productId = pro.liliya.core.license.LicenseProductId("liliya-core"),
            features = setOf(pro.liliya.core.license.LicenseFeature("runtime")),
            version = schemaVersion,
            signingKeyId = keyId,
            issuedAt = Instant.parse("2026-09-09T00:00:00Z"),
            notBefore = Instant.parse("2026-09-09T00:00:00Z"),
            expiresAt = Instant.parse("2027-09-09T00:00:00Z"),
            offlineLeaseUntil = Instant.parse("2026-10-09T00:00:00Z"),
            revocationEpoch = pro.liliya.core.license.LicenseRevocationEpoch(0),
            replaySequence = pro.liliya.core.license.LicenseReplaySequence(1)
        )
        val payload = pro.liliya.core.license.LicenseEntitlementCanonicalCodec.encode(entitlement)
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload.copyBytes())
            sign()
        }
        val envelope = pro.liliya.core.license.LicenseSignedEnvelope(
            schemaVersion = schemaVersion,
            algorithm = algorithm,
            signingKeyId = keyId,
            payload = payload,
            signature = pro.liliya.core.license.LicenseSignature.of(signatureBytes)
        )
        val trustedKey = pro.liliya.core.license.LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = algorithm,
            material = keyPair.public.encoded
        )
        val result = AndroidProductRuntimeStartupTrustAssembly.create(
            AndroidProductRuntimeStartupTrustInput(
                diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
                loggerProvider = LoggerProvider(LoggerFactory::create),
                supportedLicenseSchemaVersion = schemaVersion,
                trustedKeys = pro.liliya.core.license.LicenseTrustedKeyResolver { requested ->
                    trustedKey.takeIf { requested == keyId }
                },
                licenseEnvelope = envelope
            )
        )
        return assertIs<AndroidProductRuntimeStartupTrustAssemblyResult.Ready>(result).ownership
    }
}
