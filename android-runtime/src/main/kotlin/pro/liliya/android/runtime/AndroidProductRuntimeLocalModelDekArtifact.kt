package pro.liliya.android.runtime

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningEvidence
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningFailure
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningPort
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningRequest
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

enum class AndroidProductRuntimeLocalModelDekArtifactFailure {
    OVERSIZED,
    UNSUPPORTED_VERSION,
    MALFORMED,
    TRAILING_DATA,
    IO_FAILED
}

sealed interface AndroidProductRuntimeLocalModelDekArtifactResult {
    data class Ready(
        val provider: AndroidProductRuntimeLocalModelDekProvisioningProvider
    ) : AndroidProductRuntimeLocalModelDekArtifactResult

    data class Rejected(
        val reason: AndroidProductRuntimeLocalModelDekArtifactFailure
    ) : AndroidProductRuntimeLocalModelDekArtifactResult

    data class Failed(
        val reason: AndroidProductRuntimeLocalModelDekArtifactFailure,
        val throwable: Throwable? = null
    ) : AndroidProductRuntimeLocalModelDekArtifactResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * One-shot provider created from one explicit LMDK1 local provisioning artifact.
 *
 * The provider retains only the exact model/DEK references plus one mutable 32-byte key buffer.
 * That buffer is cleared on success, reference mismatch, explicit close, or any terminal attempt.
 */
class AndroidProductRuntimeLocalModelDekProvisioningProvider internal constructor(
    private val model: ProtectedModelReference,
    private val dek: ModelDekReference,
    material: ByteArray
) : ProtectedModelDekProvisioningPort, AutoCloseable {
    private val materialBytes = material.copyOf()
    private var consumed = false

    init {
        require(materialBytes.size == MODEL_DEK_BYTES)
    }

    @Synchronized
    override fun provision(
        request: ProtectedModelDekProvisioningRequest
    ): ProtectedModelDekProvisioningResult {
        if (consumed) {
            return ProtectedModelDekProvisioningResult.Rejected(
                ProtectedModelDekProvisioningFailure.REJECTED
            )
        }
        consumed = true

        if (request.model != model || request.dek != dek) {
            materialBytes.fill(0)
            return ProtectedModelDekProvisioningResult.Rejected(
                ProtectedModelDekProvisioningFailure.REFERENCE_MISMATCH
            )
        }

        return try {
            ProtectedModelDekProvisioningResult.Provisioned(
                ProtectedModelDekProvisioningEvidence(
                    model = model,
                    dek = dek,
                    material = ProtectedModelDekMaterial(materialBytes)
                )
            )
        } catch (throwable: Throwable) {
            ProtectedModelDekProvisioningResult.Failed(
                ProtectedModelDekProvisioningFailure.PROVIDER_FAILED,
                throwable
            )
        } finally {
            materialBytes.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        materialBytes.fill(0)
        consumed = true
    }

    override fun toString(): String =
        "AndroidProductRuntimeLocalModelDekProvisioningProvider(" +
            "model=$model, dek=$dek, material=<redacted>, consumed=$consumed)"

    private companion object {
        const val MODEL_DEK_BYTES = 32
    }
}

/**
 * Strict bounded parser for the First Working Liliya LMDK1 local provisioning artifact.
 *
 * Local DEK Artifact != Discovery.
 * Local DEK Artifact != Durable Plaintext Storage.
 * Local DEK Artifact != Network Key Release.
 */
object AndroidProductRuntimeLocalModelDekArtifact {
    private val MAGIC = byteArrayOf(
        'L'.code.toByte(),
        'M'.code.toByte(),
        'D'.code.toByte(),
        'K'.code.toByte(),
        '1'.code.toByte()
    )

    private const val VERSION = 1
    private const val DEFAULT_MAX_ARTIFACT_BYTES = 4 * 1024
    private const val MAX_IDENTIFIER_BYTES = 1024
    private const val MODEL_DEK_BYTES = 32

    fun parse(
        input: InputStream,
        maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES
    ): AndroidProductRuntimeLocalModelDekArtifactResult {
        if (
            maxArtifactBytes <= 0 ||
            maxArtifactBytes > DEFAULT_MAX_ARTIFACT_BYTES
        ) {
            return AndroidProductRuntimeLocalModelDekArtifactResult.Rejected(
                AndroidProductRuntimeLocalModelDekArtifactFailure.OVERSIZED
            )
        }

        val bytes = try {
            readBounded(input, maxArtifactBytes)
        } catch (throwable: IOException) {
            return AndroidProductRuntimeLocalModelDekArtifactResult.Failed(
                AndroidProductRuntimeLocalModelDekArtifactFailure.IO_FAILED,
                throwable
            )
        } ?: return AndroidProductRuntimeLocalModelDekArtifactResult.Rejected(
            AndroidProductRuntimeLocalModelDekArtifactFailure.OVERSIZED
        )

        return try {
            parseBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseBytes(
        bytes: ByteArray
    ): AndroidProductRuntimeLocalModelDekArtifactResult =
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            val magicMatches = magic.contentEquals(MAGIC)
            magic.fill(0)
            if (!magicMatches) {
                return AndroidProductRuntimeLocalModelDekArtifactResult.Rejected(
                    AndroidProductRuntimeLocalModelDekArtifactFailure.MALFORMED
                )
            }

            if (input.readInt() != VERSION) {
                return AndroidProductRuntimeLocalModelDekArtifactResult.Rejected(
                    AndroidProductRuntimeLocalModelDekArtifactFailure.UNSUPPORTED_VERSION
                )
            }

            val packageId = readString(input)
                ?: return rejectedMalformed()
            val packageGeneration = input.readLong()
            val dekId = readString(input)
                ?: return rejectedMalformed()
            val dekGeneration = input.readLong()
            if (packageGeneration <= 0L || dekGeneration <= 0L) {
                return rejectedMalformed()
            }

            val material = ByteArray(MODEL_DEK_BYTES)
            input.readFully(material)
            if (input.read() != -1) {
                material.fill(0)
                return AndroidProductRuntimeLocalModelDekArtifactResult.Rejected(
                    AndroidProductRuntimeLocalModelDekArtifactFailure.TRAILING_DATA
                )
            }

            try {
                val model = ProtectedModelReference(
                    packageId = ProtectedModelPackageId(packageId),
                    generation = ProtectedModelGeneration(packageGeneration)
                )
                val dek = ModelDekReference(
                    id = ModelDekId(dekId),
                    generation = ModelDekGeneration(dekGeneration)
                )
                AndroidProductRuntimeLocalModelDekArtifactResult.Ready(
                    AndroidProductRuntimeLocalModelDekProvisioningProvider(
                        model = model,
                        dek = dek,
                        material = material
                    )
                )
            } catch (_: IllegalArgumentException) {
                rejectedMalformed()
            } finally {
                material.fill(0)
            }
        } catch (_: EOFException) {
            rejectedMalformed()
        } catch (_: IllegalArgumentException) {
            rejectedMalformed()
        }

    private fun readString(input: DataInputStream): String? {
        val size = input.readInt()
        if (size <= 0 || size > MAX_IDENTIFIER_BYTES) return null
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .takeIf { it.isNotBlank() }
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun readBounded(
        input: InputStream,
        maxBytes: Int
    ): ByteArray? {
        val buffer = ByteArray(maxBytes + 1)
        var total = 0
        try {
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) {
                    return buffer.copyOf(total)
                }
                if (count == 0) continue
                total += count
                if (total > maxBytes) return null
            }
            return null
        } finally {
            buffer.fill(0)
        }
    }

    private fun rejectedMalformed() =
        AndroidProductRuntimeLocalModelDekArtifactResult.Rejected(
            AndroidProductRuntimeLocalModelDekArtifactFailure.MALFORMED
        )
}
