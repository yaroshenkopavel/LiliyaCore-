package pro.liliya.core.licensetransport

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject

class LicenseS6PrivacyNegativeAcceptanceTest {
    @Test
    fun real_hostile_http_paths_fail_closed_without_secret_echo() {
        val base = System.getenv("LIVE_S6_NEGATIVE_BASE")
        val connectFailureEndpoint = System.getenv("LIVE_S6_CONNECT_FAILURE_ENDPOINT")

        assumeTrue(!base.isNullOrBlank(), "LIVE_S6_NEGATIVE_BASE is not configured")
        assumeTrue(
            !connectFailureEndpoint.isNullOrBlank(),
            "LIVE_S6_CONNECT_FAILURE_ENDPOINT is not configured"
        )

        val malformedMarker = "PRIVATE-MALFORMED-SUCCESS-MARKER"
        val malformed = client(
            "$base/v1/license/malformed-success"
        ).execute(request())

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(malformed).reason
        )
        assertFalse(malformedMarker in malformed.toString())

        val redirect = client(
            "$base/v1/license/redirect"
        ).execute(request())

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(redirect).reason
        )

        val serverSecretMarker = "PRIVATE-SERVER-SECRET-MARKER"
        val serverError = client(
            "$base/v1/license/server-secret"
        ).execute(request())

        assertEquals(
            LicenseClientTransportFailure.SERVICE_UNAVAILABLE,
            assertIs<LicenseClientTransportResult.Failed>(serverError).reason
        )
        assertFalse(serverSecretMarker in serverError.toString())

        val connectFailure = client(
            connectFailureEndpoint!!
        ).execute(request())

        assertEquals(
            LicenseClientTransportFailure.CONNECT_FAILURE,
            assertIs<LicenseClientTransportResult.Failed>(connectFailure).reason
        )

        val requestSecretMarker = "PRIVATE-REQUEST-ECHO-MARKER"
        val lowLevel = UrlConnectionLicenseHttpEngine().execute(
            LicenseHttpEngineRequest(
                endpoint = URL("$base/v1/license"),
                connectTimeoutMillis = 1000,
                readTimeoutMillis = 2000,
                body = (
                    """{"wireVersion":1,"kind":"request","protocolVersion":1,"operation":"ISSUE","productId":"liliya-pro","subjectReference":""" +
                        "\"$requestSecretMarker\""
                    ).encodeToByteArray()
            ),
            LicenseTransportCancellation()
        )

        val response = assertIs<LicenseHttpEngineResult.Response>(lowLevel).response
        assertEquals(400, response.status)
        assertFalse(
            response.body.decodeToString().contains(requestSecretMarker),
            "backend must never reflect private malformed request data"
        )
        assertFalse(requestSecretMarker in response.toString())

        val evidence =
            "LICENSING_S6_6_EVIDENCE=" +
                "{\"malformed2xxFailClosed\":true," +
                "\"redirectRejected\":true," +
                "\"serverSecretBodyHidden\":true," +
                "\"connectFailureTyped\":true," +
                "\"malformedRequestNotEchoed\":true," +
                "\"transportRenderingRedacted\":true}"

        println(evidence)

        System.getenv("LIVE_S6_6_EVIDENCE_PATH")
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

    private fun client(endpoint: String) =
        LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL(endpoint),
                connectTimeoutMillis = 1000,
                readTimeoutMillis = 2000,
                developmentAllowInsecureHttp = true
            )
        )

    private fun request() =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("s6-negative-subject"),
            requestId = LicenseServiceRequestId("s6-negative-request")
        )
}
