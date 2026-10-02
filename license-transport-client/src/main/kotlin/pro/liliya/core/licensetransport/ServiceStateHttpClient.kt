package pro.liliya.core.licensetransport

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URL
import java.util.Base64
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceAuthenticationProof
import pro.liliya.core.license.LicenseServiceEvidenceProfile
import pro.liliya.core.license.LicenseServiceEvidencePurpose
import pro.liliya.core.license.LicenseServiceOpaquePayload
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseServiceSecurityScope
import pro.liliya.core.license.LicenseServiceStateEnvelope
import pro.liliya.core.license.LicenseSubject

data class ServiceStateHttpRequest(
    val protocolVersion: LicenseServiceProtocolVersion,
    val scope: LicenseServiceSecurityScope,
    val requestId: LicenseServiceRequestId
)

enum class ServiceStateRemoteFailure {
    INVALID_REQUEST,
    AUTHENTICATION_REQUIRED,
    STATE_UNAVAILABLE,
    SIGNING_KEY_UNAVAILABLE,
    INTERNAL_FAILURE
}

sealed interface ServiceStateHttpResult {
    data class Evidence(val envelope: LicenseServiceStateEnvelope) : ServiceStateHttpResult
    data class ServiceRejected(val reason: ServiceStateRemoteFailure) : ServiceStateHttpResult
    data class Failed(val reason: LicenseClientTransportFailure) : ServiceStateHttpResult
}

class ServiceStateHttpClient(
    private val config: LicenseHttpTransportConfig,
    private val engine: LicenseHttpEngine = UrlConnectionLicenseHttpEngine()
) {
    fun execute(
        request: ServiceStateHttpRequest,
        authentication: LicenseHttpBearerCredential,
        cancellation: LicenseTransportCancellation = LicenseTransportCancellation()
    ): ServiceStateHttpResult {
        if (cancellation.isCancelled()) {
            return ServiceStateHttpResult.Failed(LicenseClientTransportFailure.CANCELLED)
        }

        val body = try {
            encodeRequest(request)
        } catch (_: RuntimeException) {
            return ServiceStateHttpResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val bearer = try {
            authentication.copyBytes()
        } catch (_: RuntimeException) {
            return ServiceStateHttpResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val result = try {
            engine.execute(
                LicenseHttpEngineRequest(
                    endpoint = endpoint(config.endpoint),
                    connectTimeoutMillis = config.connectTimeoutMillis,
                    readTimeoutMillis = config.readTimeoutMillis,
                    body = body,
                    authorizationBearer = bearer,
                    tlsTrust = config.tlsTrust
                ),
                cancellation
            )
        } finally {
            bearer.fill(0)
        }

        return when (result) {
            is LicenseHttpEngineResult.Failed ->
                ServiceStateHttpResult.Failed(result.reason)
            is LicenseHttpEngineResult.Response ->
                decodeResponse(result.response)
        }
    }

    private fun decodeResponse(response: LicenseHttpEngineResponse): ServiceStateHttpResult {
        if (response.status !in 200..599) {
            return ServiceStateHttpResult.Failed(
                LicenseClientTransportFailure.PROTOCOL_FAILURE
            )
        }

        return try {
            val root = JSON.readTree(response.body)
            if (
                !root.isObject ||
                root.path("wireVersion").asInt(-1) != WIRE_VERSION
            ) {
                return ServiceStateHttpResult.Failed(
                    LicenseClientTransportFailure.PROTOCOL_FAILURE
                )
            }

            when (root.path("kind").asText("")) {
                "service-state" -> {
                    if (response.status !in 200..299) {
                        return ServiceStateHttpResult.Failed(
                            LicenseClientTransportFailure.PROTOCOL_FAILURE
                        )
                    }
                    val purpose = try {
                        LicenseServiceEvidencePurpose.valueOf(
                            requiredText(root, "purpose")
                        )
                    } catch (_: IllegalArgumentException) {
                        return ServiceStateHttpResult.Failed(
                            LicenseClientTransportFailure.PROTOCOL_FAILURE
                        )
                    }
                    ServiceStateHttpResult.Evidence(
                        LicenseServiceStateEnvelope(
                            protocolVersion = LicenseServiceProtocolVersion(
                                requiredLong(root, "protocolVersion")
                            ),
                            purpose = purpose,
                            profile = LicenseServiceEvidenceProfile(
                                requiredText(root, "profile")
                            ),
                            signingKeyId = LicenseKeyId(
                                requiredText(root, "signingKeyId")
                            ),
                            payload = LicenseServiceOpaquePayload.of(
                                Base64.getDecoder().decode(
                                    requiredText(root, "payloadBase64")
                                )
                            ),
                            proof = LicenseServiceAuthenticationProof.of(
                                Base64.getDecoder().decode(
                                    requiredText(root, "proofBase64")
                                )
                            )
                        )
                    )
                }

                "rejected" -> {
                    if (response.status in 200..299) {
                        return ServiceStateHttpResult.Failed(
                            LicenseClientTransportFailure.PROTOCOL_FAILURE
                        )
                    }
                    val reason = try {
                        ServiceStateRemoteFailure.valueOf(
                            requiredText(root, "reason")
                        )
                    } catch (_: IllegalArgumentException) {
                        return ServiceStateHttpResult.Failed(
                            LicenseClientTransportFailure.PROTOCOL_FAILURE
                        )
                    }
                    ServiceStateHttpResult.ServiceRejected(reason)
                }

                else -> ServiceStateHttpResult.Failed(
                    LicenseClientTransportFailure.PROTOCOL_FAILURE
                )
            }
        } catch (_: Exception) {
            ServiceStateHttpResult.Failed(
                LicenseClientTransportFailure.PROTOCOL_FAILURE
            )
        }
    }

    private fun encodeRequest(request: ServiceStateHttpRequest): ByteArray {
        val root = JSON.createObjectNode()
        root.put("wireVersion", WIRE_VERSION)
        root.put("kind", "service-state-request")
        root.put("protocolVersion", request.protocolVersion.value)
        root.put("productId", request.scope.productId.value)
        root.put("subjectReference", request.scope.subject.value)
        root.put("requestId", request.requestId.value)
        return JSON.writeValueAsBytes(root)
    }

    private fun endpoint(base: URL): URL =
        URL(base.protocol, base.host, base.port, PATH)

    private fun requiredText(root: com.fasterxml.jackson.databind.JsonNode, name: String): String {
        val node = root.get(name) ?: error("missing $name")
        require(node.isTextual)
        return node.asText().takeIf(String::isNotBlank) ?: error("blank $name")
    }

    private fun requiredLong(root: com.fasterxml.jackson.databind.JsonNode, name: String): Long {
        val node = root.get(name) ?: error("missing $name")
        require(node.isIntegralNumber)
        return node.longValue()
    }

    companion object {
        const val PATH = "/v1/license/service-state"
        const val WIRE_VERSION = 1
        private val JSON = ObjectMapper()
    }
}
