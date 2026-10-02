package pro.liliya.core.licensetransport

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URL
import java.util.Base64
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

data class DeviceRebindHttpRequest(
    val rebindCode: String,
    val attemptId: String,
    val installationId: String,
    val deviceKeyFingerprint: String
) {
    init {
        require(rebindCode.isNotBlank())
        require(attemptId.isNotBlank())
        require(installationId.isNotBlank())
        require(deviceKeyFingerprint.isNotBlank())
    }

    override fun toString(): String =
        "DeviceRebindHttpRequest(rebindCode=<redacted>,attemptId=<redacted>," +
            "installationId=<redacted>,deviceKeyFingerprint=<redacted>)"
}

sealed interface DeviceRebindHttpResult {
    data class Rebound(
        val subject: String,
        val license: LicenseSignedEnvelope
    ) : DeviceRebindHttpResult

    data class Rejected(val reason: String) : DeviceRebindHttpResult
    data class Failed(val reason: LicenseClientTransportFailure) :
        DeviceRebindHttpResult
}

class DeviceRebindHttpClient(
    private val config: LicenseHttpTransportConfig,
    private val engine: LicenseHttpEngine = UrlConnectionLicenseHttpEngine()
) {
    private val json = ObjectMapper()

    fun rebind(
        request: DeviceRebindHttpRequest,
        cancellation: LicenseTransportCancellation =
            LicenseTransportCancellation()
    ): DeviceRebindHttpResult {
        if (cancellation.isCancelled()) {
            return DeviceRebindHttpResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        val body = try {
            val root = json.createObjectNode()
            root.put("wireVersion", 1)
            root.put("rebindCode", request.rebindCode)
            root.put("attemptId", request.attemptId)
            root.put("installationId", request.installationId)
            root.put("deviceKeyFingerprint", request.deviceKeyFingerprint)
            json.writeValueAsBytes(root)
        } catch (_: Throwable) {
            return DeviceRebindHttpResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val endpoint = try {
            URL(
                config.endpoint.protocol,
                config.endpoint.host,
                config.endpoint.port,
                PATH
            )
        } catch (_: Throwable) {
            return DeviceRebindHttpResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val engineResult = engine.execute(
            LicenseHttpEngineRequest(
                endpoint = endpoint,
                connectTimeoutMillis = config.connectTimeoutMillis,
                readTimeoutMillis = config.readTimeoutMillis,
                body = body,
                authorizationBearer = null,
                tlsTrust = config.tlsTrust
            ),
            cancellation
        )

        if (cancellation.isCancelled()) {
            return DeviceRebindHttpResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        return when (engineResult) {
            is LicenseHttpEngineResult.Failed ->
                DeviceRebindHttpResult.Failed(engineResult.reason)
            is LicenseHttpEngineResult.Response ->
                mapResponse(engineResult.response)
        }
    }

    private fun mapResponse(
        response: LicenseHttpEngineResponse
    ): DeviceRebindHttpResult {
        if (response.status in 500..599) {
            return DeviceRebindHttpResult.Failed(
                LicenseClientTransportFailure.SERVICE_UNAVAILABLE
            )
        }

        val root = try {
            json.readTree(response.body)
        } catch (_: Throwable) {
            return protocolFailure()
        }

        if (root.path("wireVersion").asInt(-1) != 1) {
            return protocolFailure()
        }

        return when (root.path("kind").asText("")) {
            "rebound" -> {
                if (response.status !in 200..299) return protocolFailure()
                val subject = root.path("subject").asText("")
                if (subject.isBlank()) return protocolFailure()
                val envelope = decodeLicense(root) ?: return protocolFailure()
                DeviceRebindHttpResult.Rebound(subject, envelope)
            }

            "rejected" -> {
                val reason = root.path("reason").asText("")
                if (response.status !in 400..499 || reason.isBlank()) {
                    protocolFailure()
                } else {
                    DeviceRebindHttpResult.Rejected(reason)
                }
            }

            else -> protocolFailure()
        }
    }

    private fun decodeLicense(
        root: com.fasterxml.jackson.databind.JsonNode
    ): LicenseSignedEnvelope? = try {
        val schemaVersion = root.path("schemaVersion").longValue()
        val algorithm = root.path("algorithm").asText("")
        val keyReference = root.path("keyReference").asText("")
        val payload = Base64.getDecoder().decode(
            root.path("payloadBase64").asText("")
        )
        val signature = Base64.getDecoder().decode(
            root.path("signatureBase64").asText("")
        )

        if (
            schemaVersion <= 0 ||
            algorithm.isBlank() ||
            keyReference.isBlank() ||
            payload.isEmpty() ||
            signature.isEmpty()
        ) return null

        LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(schemaVersion),
            algorithm = LicenseAlgorithm(algorithm),
            signingKeyId = LicenseKeyId(keyReference),
            payload = LicenseCanonicalPayload.of(payload),
            signature = LicenseSignature.of(signature)
        )
    } catch (_: Throwable) {
        null
    }

    private fun protocolFailure() =
        DeviceRebindHttpResult.Failed(
            LicenseClientTransportFailure.PROTOCOL_FAILURE
        )

    companion object {
        const val PATH = "/v1/activation/rebind"
    }
}
