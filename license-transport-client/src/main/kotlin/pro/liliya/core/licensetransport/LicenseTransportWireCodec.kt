package pro.liliya.core.licensetransport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

internal sealed interface LicenseTransportWireDecodeResult<out T> {
    data class Decoded<T>(val value: T) : LicenseTransportWireDecodeResult<T>
    data object ProtocolFailure : LicenseTransportWireDecodeResult<Nothing>
}

internal object LicenseTransportWireCodec {
    private val json = ObjectMapper()
    const val WIRE_VERSION = 1

    fun encodeRequest(request: LicenseServiceTransportRequest): ByteArray {
        val root = json.createObjectNode()
        root.put("wireVersion", WIRE_VERSION)
        root.put("kind", "request")
        root.put("protocolVersion", request.protocolVersion.value)
        root.put("operation", request.operation.name)
        root.put("productId", request.productId.value)
        root.put("subjectReference", request.subjectReference.value)
        root.put("requestId", request.requestId.value)
        request.enrollmentId?.let {
            root.put("enrollmentReference", it.value)
        }
        return json.writeValueAsBytes(root)
    }

    fun decodeResponse(bytes: ByteArray): LicenseTransportWireDecodeResult<LicenseClientTransportResult> =
        try {
            val root = json.readTree(bytes)
            if (
                !root.isObject ||
                requiredInt(root, "wireVersion") != WIRE_VERSION
            ) {
                LicenseTransportWireDecodeResult.ProtocolFailure
            } else {
                when (requiredText(root, "kind")) {
                    "success" -> decodeSignedSuccess(root)
                    "rejected" -> decodeServiceRejected(root)
                    else -> LicenseTransportWireDecodeResult.ProtocolFailure
                }
            }
        } catch (_: Exception) {
            LicenseTransportWireDecodeResult.ProtocolFailure
        }

    private fun decodeSignedSuccess(
        root: JsonNode
    ): LicenseTransportWireDecodeResult<LicenseClientTransportResult> {
        val payload = Base64.getDecoder().decode(
            requiredText(root, "payloadBase64")
        )
        val signature = Base64.getDecoder().decode(
            requiredText(root, "signatureBase64")
        )

        return LicenseTransportWireDecodeResult.Decoded(
            LicenseClientTransportResult.Signed(
                LicenseSignedEnvelope(
                    schemaVersion = LicenseVersion(
                        requiredLong(root, "schemaVersion")
                    ),
                    algorithm = LicenseAlgorithm(
                        requiredText(root, "algorithm")
                    ),
                    signingKeyId = LicenseKeyId(
                        requiredText(root, "keyReference")
                    ),
                    payload = LicenseCanonicalPayload.of(payload),
                    signature = LicenseSignature.of(signature)
                )
            )
        )
    }

    private fun decodeServiceRejected(
        root: JsonNode
    ): LicenseTransportWireDecodeResult<LicenseClientTransportResult> {
        val reason = try {
            LicenseRemoteServiceFailure.valueOf(
                requiredText(root, "reason")
            )
        } catch (_: IllegalArgumentException) {
            return LicenseTransportWireDecodeResult.ProtocolFailure
        }

        return LicenseTransportWireDecodeResult.Decoded(
            LicenseClientTransportResult.ServiceRejected(reason)
        )
    }

    private fun requiredText(root: JsonNode, name: String): String {
        val node = root.get(name) ?: error("missing " + name)
        require(node.isTextual)
        val value = node.asText()
        require(value.isNotBlank())
        return value
    }

    private fun requiredInt(root: JsonNode, name: String): Int {
        val node = root.get(name) ?: error("missing " + name)
        require(node.canConvertToInt())
        return node.intValue()
    }

    private fun requiredLong(root: JsonNode, name: String): Long {
        val node = root.get(name) ?: error("missing " + name)
        require(node.isIntegralNumber)
        return node.longValue()
    }
}
