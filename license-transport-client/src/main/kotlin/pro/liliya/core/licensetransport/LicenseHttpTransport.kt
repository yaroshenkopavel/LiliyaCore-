package pro.liliya.core.licensetransport

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class LicenseHttpTransportConfig(
    val endpoint: URL,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val developmentAllowInsecureHttp: Boolean = false
) {
    init {
        require(connectTimeoutMillis > 0) {
            "license transport connect timeout must be finite and positive"
        }
        require(readTimeoutMillis > 0) {
            "license transport read timeout must be finite and positive"
        }
        require(
            endpoint.protocol == "https" ||
                (developmentAllowInsecureHttp && endpoint.protocol == "http")
        ) {
            "license transport endpoint must use HTTPS unless development HTTP is explicitly enabled"
        }
    }

    val attemptLimit: Int = 1

    override fun toString(): String =
        "LicenseHttpTransportConfig(endpoint=" +
            endpoint.protocol + "://" + endpoint.host + "/<redacted-path>" +
            ",connectTimeoutMillis=" + connectTimeoutMillis +
            ",readTimeoutMillis=" + readTimeoutMillis +
            ",attemptLimit=" + attemptLimit +
            ",developmentAllowInsecureHttp=" + developmentAllowInsecureHttp + ")"
}

data class LicenseHttpEngineRequest(
    val endpoint: URL,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val body: ByteArray,
    val authorizationBearer: ByteArray? = null
) {
    override fun toString(): String =
        "LicenseHttpEngineRequest(endpoint=" +
            endpoint.protocol + "://" + endpoint.host + "/<redacted-path>" +
            ",connectTimeoutMillis=" + connectTimeoutMillis +
            ",readTimeoutMillis=" + readTimeoutMillis +
            ",body=<redacted>,authorizationBearer=<redacted>)"
}

data class LicenseHttpEngineResponse(
    val status: Int,
    val body: ByteArray
) {
    override fun toString(): String =
        "LicenseHttpEngineResponse(status=" + status + ",body=<redacted>)"
}

sealed interface LicenseHttpEngineResult {
    data class Response(val response: LicenseHttpEngineResponse) : LicenseHttpEngineResult
    data class Failed(val reason: LicenseClientTransportFailure) : LicenseHttpEngineResult
}

fun interface LicenseHttpEngine {
    fun execute(
        request: LicenseHttpEngineRequest,
        cancellation: LicenseTransportCancellation
    ): LicenseHttpEngineResult
}

class UrlConnectionLicenseHttpEngine : LicenseHttpEngine {
    override fun execute(
        request: LicenseHttpEngineRequest,
        cancellation: LicenseTransportCancellation
    ): LicenseHttpEngineResult {
        if (cancellation.isCancelled()) {
            return LicenseHttpEngineResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        var connection: HttpURLConnection? = null
        var cancellationRegistration: AutoCloseable? = null

        return try {
            val activeConnection = request.endpoint.openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = request.connectTimeoutMillis
            activeConnection.readTimeout = request.readTimeoutMillis
            activeConnection.instanceFollowRedirects = false
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "application/json")
            activeConnection.setRequestProperty("Accept", "application/json")
            request.authorizationBearer?.let { bearer ->
                activeConnection.setRequestProperty(
                    "Authorization",
                    "Bearer ${bearer.toString(Charsets.UTF_8)}"
                )
            }

            cancellationRegistration = cancellation.register {
                activeConnection.disconnect()
            }

            if (cancellation.isCancelled()) {
                return LicenseHttpEngineResult.Failed(
                    LicenseClientTransportFailure.CANCELLED
                )
            }

            activeConnection.outputStream.use { output ->
                output.write(request.body)
                output.flush()
            }

            if (cancellation.isCancelled()) {
                return LicenseHttpEngineResult.Failed(
                    LicenseClientTransportFailure.CANCELLED
                )
            }

            val status = activeConnection.responseCode
            val stream = if (status in 200..399) {
                activeConnection.inputStream
            } else {
                activeConnection.errorStream
            }
            val body = stream?.use { it.readBytes() } ?: byteArrayOf()

            if (cancellation.isCancelled()) {
                LicenseHttpEngineResult.Failed(
                    LicenseClientTransportFailure.CANCELLED
                )
            } else {
                LicenseHttpEngineResult.Response(
                    LicenseHttpEngineResponse(status, body)
                )
            }
        } catch (_: SocketTimeoutException) {
            failureUnlessCancelled(
                cancellation,
                LicenseClientTransportFailure.TIMEOUT
            )
        } catch (_: SSLException) {
            failureUnlessCancelled(
                cancellation,
                LicenseClientTransportFailure.TLS_FAILURE
            )
        } catch (_: UnknownHostException) {
            failureUnlessCancelled(
                cancellation,
                LicenseClientTransportFailure.CONNECT_FAILURE
            )
        } catch (_: ConnectException) {
            failureUnlessCancelled(
                cancellation,
                LicenseClientTransportFailure.CONNECT_FAILURE
            )
        } catch (_: NoRouteToHostException) {
            failureUnlessCancelled(
                cancellation,
                LicenseClientTransportFailure.CONNECT_FAILURE
            )
        } catch (_: IOException) {
            failureUnlessCancelled(
                cancellation,
                LicenseClientTransportFailure.CONNECT_FAILURE
            )
        } catch (_: RuntimeException) {
            failureUnlessCancelled(
                cancellation,
                LicenseClientTransportFailure.PROTOCOL_FAILURE
            )
        } finally {
            runCatching {
                cancellationRegistration?.close()
            }
            connection?.disconnect()
        }
    }

    private fun failureUnlessCancelled(
        cancellation: LicenseTransportCancellation,
        reason: LicenseClientTransportFailure
    ): LicenseHttpEngineResult.Failed =
        LicenseHttpEngineResult.Failed(
            if (cancellation.isCancelled()) {
                LicenseClientTransportFailure.CANCELLED
            } else {
                reason
            }
        )
}

class LicenseHttpTransportClient(
    private val config: LicenseHttpTransportConfig,
    private val engine: LicenseHttpEngine = UrlConnectionLicenseHttpEngine()
) {
    fun execute(
        request: LicenseServiceTransportRequest,
        cancellation: LicenseTransportCancellation = LicenseTransportCancellation()
    ): LicenseClientTransportResult =
        executeInternal(
            request = request,
            authentication = null,
            cancellation = cancellation
        )

    fun execute(
        request: LicenseServiceTransportRequest,
        authentication: LicenseHttpBearerCredential,
        cancellation: LicenseTransportCancellation = LicenseTransportCancellation()
    ): LicenseClientTransportResult =
        executeInternal(
            request = request,
            authentication = authentication,
            cancellation = cancellation
        )

    private fun executeInternal(
        request: LicenseServiceTransportRequest,
        authentication: LicenseHttpBearerCredential?,
        cancellation: LicenseTransportCancellation
    ): LicenseClientTransportResult {
        if (cancellation.isCancelled()) {
            return LicenseClientTransportResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        val body = try {
            LicenseTransportWireCodec.encodeRequest(request)
        } catch (_: RuntimeException) {
            return LicenseClientTransportResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val bearer = try {
            authentication?.copyBytes()
        } catch (_: RuntimeException) {
            return LicenseClientTransportResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        val engineResult = try {
            engine.execute(
                LicenseHttpEngineRequest(
                    endpoint = config.endpoint,
                    connectTimeoutMillis = config.connectTimeoutMillis,
                    readTimeoutMillis = config.readTimeoutMillis,
                    body = body,
                    authorizationBearer = bearer
                ),
                cancellation
            )
        } finally {
            bearer?.fill(0)
        }

        if (cancellation.isCancelled()) {
            return LicenseClientTransportResult.Failed(
                LicenseClientTransportFailure.CANCELLED
            )
        }

        return when (engineResult) {
            is LicenseHttpEngineResult.Failed ->
                LicenseClientTransportResult.Failed(engineResult.reason)

            is LicenseHttpEngineResult.Response ->
                mapResponse(engineResult.response)
        }
    }

    private fun mapResponse(
        response: LicenseHttpEngineResponse
    ): LicenseClientTransportResult =
        when (response.status) {
            in 200..299 ->
                when (
                    val decoded = LicenseTransportWireCodec.decodeResponse(
                        response.body
                    )
                ) {
                    is LicenseTransportWireDecodeResult.Decoded ->
                        if (decoded.value is LicenseClientTransportResult.Signed) {
                            decoded.value
                        } else {
                            LicenseClientTransportResult.Failed(
                                LicenseClientTransportFailure.PROTOCOL_FAILURE
                            )
                        }
                    LicenseTransportWireDecodeResult.ProtocolFailure ->
                        LicenseClientTransportResult.Failed(
                            LicenseClientTransportFailure.PROTOCOL_FAILURE
                        )
                }

            in 400..499 ->
                when (
                    val decoded = LicenseTransportWireCodec.decodeResponse(
                        response.body
                    )
                ) {
                    is LicenseTransportWireDecodeResult.Decoded ->
                        if (decoded.value is LicenseClientTransportResult.ServiceRejected) {
                            decoded.value
                        } else {
                            LicenseClientTransportResult.Failed(
                                LicenseClientTransportFailure.PROTOCOL_FAILURE
                            )
                        }
                    LicenseTransportWireDecodeResult.ProtocolFailure ->
                        LicenseClientTransportResult.Failed(
                            LicenseClientTransportFailure.PROTOCOL_FAILURE
                        )
                }

            in 500..599 ->
                LicenseClientTransportResult.Failed(
                    LicenseClientTransportFailure.SERVICE_UNAVAILABLE
                )

            else ->
                LicenseClientTransportResult.Failed(
                    LicenseClientTransportFailure.PROTOCOL_FAILURE
                )
        }
}
