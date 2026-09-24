package pro.liliya.core.licensetransport

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

data class LicenseActivationTransportConfig(
    val endpoint: java.net.URL,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val developmentAllowInsecureHttp: Boolean = false
) {
    init {
        require(connectTimeoutMillis > 0) { "activation connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "activation read timeout must be positive" }
        require(
            endpoint.protocol == "https" ||
                (developmentAllowInsecureHttp && endpoint.protocol == "http")
        ) {
            "activation endpoint must use HTTPS unless development HTTP is explicitly enabled"
        }
    }

    override fun toString(): String =
        "LicenseActivationTransportConfig(endpoint=" +
            endpoint.protocol + "://" + endpoint.host + "/<redacted-path>," +
            "connectTimeoutMillis=$connectTimeoutMillis," +
            "readTimeoutMillis=$readTimeoutMillis," +
            "developmentAllowInsecureHttp=$developmentAllowInsecureHttp)"
}

class LicenseActivationTransportClient(
    private val config: LicenseActivationTransportConfig,
    private val engine: LicenseHttpEngine = UrlConnectionLicenseHttpEngine()
) {
    fun execute(
        activationCode: String,
        activationRequestId: String,
        installId: String,
        installSecret: ByteArray,
        cancellation: LicenseTransportCancellation = LicenseTransportCancellation()
    ): LicenseClientTransportResult {
        if (cancellation.isCancelled()) {
            return LicenseClientTransportResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        val secretText = try {
            validateAndDecodeSecret(installSecret)
        } catch (_: IllegalArgumentException) {
            return LicenseClientTransportResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val body = try {
            encodeRequest(
                activationCode = activationCode,
                activationRequestId = activationRequestId,
                installId = installId,
                installSecret = secretText
            )
        } catch (_: RuntimeException) {
            return LicenseClientTransportResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        return try {
            when (
                val result = engine.execute(
                    LicenseHttpEngineRequest(
                        endpoint = config.endpoint,
                        connectTimeoutMillis = config.connectTimeoutMillis,
                        readTimeoutMillis = config.readTimeoutMillis,
                        body = body
                    ),
                    cancellation
                )
            ) {
                is LicenseHttpEngineResult.Failed ->
                    LicenseClientTransportResult.Failed(result.reason)

                is LicenseHttpEngineResult.Response ->
                    mapResponse(result.response)
            }
        } finally {
            body.fill(0)
        }
    }

    private fun encodeRequest(
        activationCode: String,
        activationRequestId: String,
        installId: String,
        installSecret: String
    ): ByteArray {
        require(activationCode.isNotBlank() && activationCode.length <= 128)
        require(activationRequestId.isNotBlank() && activationRequestId.length <= 128)
        require(installId.length in 8..128)
        require(installId.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        require(installSecret.length in 32..256)

        val root = JSON.createObjectNode()
        root.put("wireVersion", 1)
        root.put("kind", "activate")
        root.put("activationCode", activationCode)
        root.put("activationRequestId", activationRequestId)
        root.put("installId", installId)
        root.put("installSecret", installSecret)
        return JSON.writeValueAsBytes(root)
    }

    private fun validateAndDecodeSecret(secret: ByteArray): String {
        require(secret.size in 32..256)
        val text = secret.toString(StandardCharsets.UTF_8)
        require(text.encodeToByteArray().contentEquals(secret))
        require(text.length in 32..256)
        require('\r' !in text && '\n' !in text)
        return text
    }

    private fun mapResponse(
        response: LicenseHttpEngineResponse
    ): LicenseClientTransportResult =
        when (response.status) {
            in 200..299 ->
                decodeExpected(response.body, expectSigned = true)
            in 400..499 ->
                decodeExpected(response.body, expectSigned = false)
            in 500..599 ->
                LicenseClientTransportResult.Failed(
                    LicenseClientTransportFailure.SERVICE_UNAVAILABLE
                )
            else ->
                LicenseClientTransportResult.Failed(
                    LicenseClientTransportFailure.PROTOCOL_FAILURE
                )
        }

    private fun decodeExpected(
        body: ByteArray,
        expectSigned: Boolean
    ): LicenseClientTransportResult =
        when (val decoded = LicenseTransportWireCodec.decodeResponse(body)) {
            is LicenseTransportWireDecodeResult.Decoded -> {
                val value = decoded.value
                if (
                    (expectSigned && value is LicenseClientTransportResult.Signed) ||
                    (!expectSigned && value is LicenseClientTransportResult.ServiceRejected)
                ) {
                    value
                } else {
                    LicenseClientTransportResult.Failed(
                        LicenseClientTransportFailure.PROTOCOL_FAILURE
                    )
                }
            }
            LicenseTransportWireDecodeResult.ProtocolFailure ->
                LicenseClientTransportResult.Failed(
                    LicenseClientTransportFailure.PROTOCOL_FAILURE
                )
        }

    override fun toString(): String =
        "LicenseActivationTransportClient(config=$config,engine=<redacted>)"

    private companion object {
        val JSON = ObjectMapper()
    }
}
