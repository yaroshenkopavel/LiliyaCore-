package pro.liliya.core.licensetransport

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URL

data class ActivationRedemptionHttpRequest(
    val activationCode: String,
    val attemptId: String
) {
    init {
        require(activationCode.isNotBlank())
        require(attemptId.isNotBlank())
    }

    override fun toString(): String =
        "ActivationRedemptionHttpRequest(activationCode=<redacted>,attemptId=<redacted>)"
}

sealed interface ActivationRedemptionHttpResult {
    data class Activated(val subject: String) : ActivationRedemptionHttpResult
    data class Rejected(val reason: String) : ActivationRedemptionHttpResult
    data class Failed(val reason: LicenseClientTransportFailure) : ActivationRedemptionHttpResult
}

class ActivationRedemptionHttpClient(
    private val config: LicenseHttpTransportConfig,
    private val engine: LicenseHttpEngine = UrlConnectionLicenseHttpEngine()
) {
    private val json = ObjectMapper()

    fun redeem(
        request: ActivationRedemptionHttpRequest,
        authentication: LicenseHttpBearerCredential,
        cancellation: LicenseTransportCancellation = LicenseTransportCancellation()
    ): ActivationRedemptionHttpResult {
        if (cancellation.isCancelled()) {
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        val body = try {
            val root = json.createObjectNode()
            root.put("wireVersion", 1)
            root.put("activationCode", request.activationCode)
            root.put("attemptId", request.attemptId)
            json.writeValueAsBytes(root)
        } catch (_: Throwable) {
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val bearer = try {
            authentication.copyBytes()
        } catch (_: Throwable) {
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val endpoint = try {
            URL(config.endpoint.protocol, config.endpoint.host, config.endpoint.port, PATH)
        } catch (_: Throwable) {
            bearer.fill(0)
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val engineResult = try {
            engine.execute(
                LicenseHttpEngineRequest(
                    endpoint = endpoint,
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

        if (cancellation.isCancelled()) {
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        return when (engineResult) {
            is LicenseHttpEngineResult.Failed ->
                ActivationRedemptionHttpResult.Failed(engineResult.reason)

            is LicenseHttpEngineResult.Response ->
                mapResponse(engineResult.response)
        }
    }

    private fun mapResponse(
        response: LicenseHttpEngineResponse
    ): ActivationRedemptionHttpResult {
        if (response.status in 500..599) {
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.SERVICE_UNAVAILABLE
            )
        }

        val root = try {
            json.readTree(response.body)
        } catch (_: Throwable) {
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.PROTOCOL_FAILURE
            )
        }

        if (root.path("wireVersion").asInt(-1) != 1) {
            return ActivationRedemptionHttpResult.Failed(
                LicenseClientTransportFailure.PROTOCOL_FAILURE
            )
        }

        return when (root.path("kind").asText("")) {
            "activated" -> {
                val subject = root.path("subject").asText("")
                if (response.status !in 200..299 || subject.isBlank()) {
                    ActivationRedemptionHttpResult.Failed(
                        LicenseClientTransportFailure.PROTOCOL_FAILURE
                    )
                } else {
                    ActivationRedemptionHttpResult.Activated(subject)
                }
            }
            "rejected" -> {
                val reason = root.path("reason").asText("")
                if (response.status !in 400..499 || reason.isBlank()) {
                    ActivationRedemptionHttpResult.Failed(
                        LicenseClientTransportFailure.PROTOCOL_FAILURE
                    )
                } else {
                    ActivationRedemptionHttpResult.Rejected(reason)
                }
            }
            else ->
                ActivationRedemptionHttpResult.Failed(
                    LicenseClientTransportFailure.PROTOCOL_FAILURE
                )
        }
    }

    companion object {
        const val PATH = "/v1/activation/redeem"
    }
}
