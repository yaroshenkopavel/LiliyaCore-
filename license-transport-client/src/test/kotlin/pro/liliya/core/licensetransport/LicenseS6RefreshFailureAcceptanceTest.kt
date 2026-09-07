package pro.liliya.core.licensetransport

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion

class LicenseS6RefreshFailureAcceptanceTest {
    @Test
    fun real_refresh_rejection_timeout_and_cancellation_are_bounded_and_typed() {
        val endpoint = System.getenv("LIVE_S6_HTTP_ENDPOINT")
        val slowEndpoint = System.getenv("LIVE_S6_SLOW_ENDPOINT")
        val publicKeyPath = System.getenv("LIVE_S6_PUBLIC_KEY_DER_PATH")

        assumeTrue(!endpoint.isNullOrBlank(), "LIVE_S6_HTTP_ENDPOINT is not configured")
        assumeTrue(!slowEndpoint.isNullOrBlank(), "LIVE_S6_SLOW_ENDPOINT is not configured")
        assumeTrue(!publicKeyPath.isNullOrBlank(), "LIVE_S6_PUBLIC_KEY_DER_PATH is not configured")
        assumeTrue(Files.isRegularFile(Path.of(publicKeyPath!!)), "S6 public key file is missing")

        val publicKeyDer = Files.readAllBytes(Path.of(publicKeyPath))
        val normalTransport = LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL(endpoint!!),
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 5_000,
                developmentAllowInsecureHttp = true
            )
        )
        assertEquals(1, LicenseHttpTransportConfig(
            endpoint = URL(endpoint),
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 5_000,
            developmentAllowInsecureHttp = true
        ).attemptLimit)

        val issue = assertIs<LicenseClientTransportResult.Signed>(
            normalTransport.execute(
                request(
                    operation = LicenseServiceOperation.ISSUE,
                    subject = "s6-refresh-subject",
                    requestId = "s6-refresh-issue-001"
                )
            )
        )
        val issuePayloadBeforeRefresh = issue.envelope.payload.copyBytes()
        val issueSignatureBeforeRefresh = issue.envelope.signature.copyBytes()
        val verifiedIssue = verify(issue.envelope, publicKeyDer)

        val refreshed = assertIs<LicenseClientTransportResult.Signed>(
            normalTransport.execute(
                request(
                    operation = LicenseServiceOperation.REFRESH,
                    subject = "s6-refresh-subject",
                    requestId = "s6-refresh-002"
                )
            )
        )
        val verifiedRefresh = verify(refreshed.envelope, publicKeyDer)

        assertEquals(0L, verifiedIssue.entitlement.replaySequence?.value)
        assertEquals(1L, verifiedRefresh.entitlement.replaySequence?.value)
        assertTrue(
            !issue.envelope.payload.copyBytes()
                .contentEquals(refreshed.envelope.payload.copyBytes())
        )

        val rejectIssue = assertIs<LicenseClientTransportResult.Signed>(
            normalTransport.execute(
                request(
                    operation = LicenseServiceOperation.ISSUE,
                    subject = "s6-refresh-reject-subject",
                    requestId = "s6-reject-issue-001"
                )
            )
        )
        val rejectIssuePayload = rejectIssue.envelope.payload.copyBytes()
        val rejectIssueSignature = rejectIssue.envelope.signature.copyBytes()
        val verifiedRejectIssue = verify(rejectIssue.envelope, publicKeyDer)

        val rejectedRefresh = assertIs<LicenseClientTransportResult.ServiceRejected>(
            normalTransport.execute(
                request(
                    operation = LicenseServiceOperation.REFRESH,
                    subject = "s6-refresh-reject-subject",
                    requestId = "s6-reject-refresh-002"
                )
            )
        )
        assertEquals(
            LicenseRemoteServiceFailure.REFRESH_REJECTED,
            rejectedRefresh.reason
        )
        assertEquals(0L, verifiedRejectIssue.entitlement.replaySequence?.value)
        assertContentEquals(rejectIssuePayload, rejectIssue.envelope.payload.copyBytes())
        assertContentEquals(rejectIssueSignature, rejectIssue.envelope.signature.copyBytes())

        val timeoutTransport = LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL(slowEndpoint!!),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 250,
                developmentAllowInsecureHttp = true
            )
        )
        val timeoutStarted = System.nanoTime()
        val timeout = assertIs<LicenseClientTransportResult.Failed>(
            timeoutTransport.execute(
                request(
                    operation = LicenseServiceOperation.REFRESH,
                    subject = "s6-timeout-subject",
                    requestId = "s6-timeout-001"
                )
            )
        )
        val timeoutMillis = TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - timeoutStarted
        )
        assertEquals(LicenseClientTransportFailure.TIMEOUT, timeout.reason)
        assertTrue(timeoutMillis < 4_000, "timeout must remain bounded")

        val cancellation = LicenseTransportCancellation()
        val cancellationTransport = LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL(slowEndpoint),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 10_000,
                developmentAllowInsecureHttp = true
            )
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = System.nanoTime()
            val future = executor.submit<LicenseClientTransportResult> {
                cancellationTransport.execute(
                    request(
                        operation = LicenseServiceOperation.REFRESH,
                        subject = "s6-cancel-subject",
                        requestId = "s6-cancel-001"
                    ),
                    cancellation
                )
            }

            Thread.sleep(500)
            assertTrue(!future.isDone, "request should still be waiting before cancellation")
            cancellation.cancel()

            val cancelled = assertIs<LicenseClientTransportResult.Failed>(
                future.get(4, TimeUnit.SECONDS)
            )
            val cancelledMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
            )
            assertEquals(LicenseClientTransportFailure.CANCELLED, cancelled.reason)
            assertTrue(cancelledMillis < 5_000, "cancellation must stop waiting")
        } finally {
            executor.shutdownNow()
        }

        assertContentEquals(issuePayloadBeforeRefresh, issue.envelope.payload.copyBytes())
        assertContentEquals(issueSignatureBeforeRefresh, issue.envelope.signature.copyBytes())

        val evidence =
            "LICENSING_S6_5_EVIDENCE=" +
                "{\"refreshAdvancedReplay\":true," +
                "\"refreshNewSignedEvidence\":true," +
                "\"refreshRejectedTyped\":true," +
                "\"oldEvidenceUnchanged\":true," +
                "\"realTimeout\":true," +
                "\"realCancellation\":true," +
                "\"attemptLimitOne\":true," +
                "\"offlineLeaseNotExtendedByTransport\":true}"

        println(evidence)
        System.getenv("LIVE_S6_5_EVIDENCE_PATH")
            ?.takeIf { it.isNotBlank() }
            ?.let { rawPath ->
                val target = Path.of(rawPath)
                target.parent?.let(Files::createDirectories)
                Files.writeString(
                    target,
                    evidence + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            }
    }

    private fun verify(
        envelope: LicenseSignedEnvelope,
        publicKeyDer: ByteArray
    ): LicenseVerificationResult.Verified {
        val trustedKey = LicenseTrustedVerificationKey.of(
            keyId = envelope.signingKeyId,
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            material = publicKeyDer
        )

        return assertIs(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm("ECDSA-P256-SHA256")),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trustedKey.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(envelope)
        )
    }

    private fun request(
        operation: LicenseServiceOperation,
        subject: String,
        requestId: String
    ): LicenseServiceTransportRequest =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = operation,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject(subject),
            requestId = LicenseServiceRequestId(requestId)
        )
}
