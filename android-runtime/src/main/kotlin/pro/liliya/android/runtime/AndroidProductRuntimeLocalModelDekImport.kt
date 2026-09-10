package pro.liliya.android.runtime

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekStore
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisionAndRegisterResult
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningEvidence
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningFailure
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningPort
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningRequest
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

private enum class LocalModelDekParseFailure {
    SOURCE_REJECTED,
    RESOURCE_LIMIT_REJECTED,
    UNSUPPORTED_VERSION,
    MALFORMED,
    PROVIDER_FAILED
}

private data class LocalModelDekArtifact(
    val model: ProtectedModelReference,
    val dek: ModelDekReference,
    val material: ProtectedModelDekMaterial
)

/**
 * One-shot LMDK1 provider for First Working Liliya.
 *
 * Local DEK Import != Final Key Release.
 * Local DEK Import != DEK Discovery.
 * Local DEK Import != Durable Plaintext Storage.
 * Local DEK Import != License/Authority.
 *
 * The selected stream is opened once, bounded to 4 KiB, parsed strictly, compared with the exact
 * verified package request, and then discarded. No URI ownership is retained by this class.
 */
class AndroidProductRuntimeLocalModelDekProvisioningProvider(
    private val openInput: () -> InputStream?
) : ProtectedModelDekProvisioningPort {
    @Volatile
    private var consumed = false

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

        val artifact = when (val parsed = parseSelected(openInput)) {
            is LocalModelDekParseResult.Parsed -> parsed.artifact
            is LocalModelDekParseResult.Rejected -> {
                return when (parsed.reason) {
                    LocalModelDekParseFailure.PROVIDER_FAILED ->
                        ProtectedModelDekProvisioningResult.Failed(
                            ProtectedModelDekProvisioningFailure.PROVIDER_FAILED
                        )
                    LocalModelDekParseFailure.SOURCE_REJECTED,
                    LocalModelDekParseFailure.RESOURCE_LIMIT_REJECTED,
                    LocalModelDekParseFailure.UNSUPPORTED_VERSION,
                    LocalModelDekParseFailure.MALFORMED ->
                        ProtectedModelDekProvisioningResult.Rejected(
                            ProtectedModelDekProvisioningFailure.REJECTED
                        )
                }
            }
        }

        if (artifact.model != request.model || artifact.dek != request.dek) {
            return ProtectedModelDekProvisioningResult.Rejected(
                ProtectedModelDekProvisioningFailure.REFERENCE_MISMATCH
            )
        }

        return ProtectedModelDekProvisioningResult.Provisioned(
            ProtectedModelDekProvisioningEvidence(
                model = artifact.model,
                dek = artifact.dek,
                material = artifact.material
            )
        )
    }

    internal fun isConsumed(): Boolean = consumed
}

/**
 * Immediately transfers a one-time LMDK1 selection into the existing wrapped-only DEK store.
 * The exact coordinator outcome is returned unchanged; a rejected import is never labeled success.
 */
object AndroidProductRuntimeLocalModelDekImport {
    fun importAndRegister(
        openInput: () -> InputStream?,
        store: PersistentProtectedModelDekStore,
        request: ProtectedModelDekProvisioningRequest,
        protectorDescriptor: ProtectedModelKeyProtectorDescriptor
    ): ProtectedModelDekProvisionAndRegisterResult =
        ProtectedModelDekProvisioningCoordinator.provisionAndRegister(
            provider = AndroidProductRuntimeLocalModelDekProvisioningProvider(openInput),
            store = store,
            request = request,
            protectorDescriptor = protectorDescriptor
        )
}

private sealed interface LocalModelDekParseResult {
    data class Parsed(val artifact: LocalModelDekArtifact) : LocalModelDekParseResult
    data class Rejected(val reason: LocalModelDekParseFailure) : LocalModelDekParseResult
}

private fun parseSelected(
    openInput: () -> InputStream?
): LocalModelDekParseResult {
    val input = try {
        openInput()
    } catch (_: Exception) {
        return LocalModelDekParseResult.Rejected(LocalModelDekParseFailure.PROVIDER_FAILED)
    } ?: return LocalModelDekParseResult.Rejected(LocalModelDekParseFailure.SOURCE_REJECTED)

    val bytes = try {
        input.use(::readBoundedArtifact)
    } catch (_: Exception) {
        return LocalModelDekParseResult.Rejected(LocalModelDekParseFailure.PROVIDER_FAILED)
    } ?: return LocalModelDekParseResult.Rejected(LocalModelDekParseFailure.RESOURCE_LIMIT_REJECTED)

    return try {
        parseArtifact(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun readBoundedArtifact(input: InputStream): ByteArray? {
    val working = ByteArray(MAX_ARTIFACT_BYTES + 1)
    var offset = 0
    return try {
        while (offset < working.size) {
            val read = input.read(working, offset, working.size - offset)
            if (read < 0) break
            if (read == 0) continue
            offset += read
        }
        if (offset == 0 || offset > MAX_ARTIFACT_BYTES) return null
        if (input.read() != -1) return null
        working.copyOf(offset)
    } finally {
        working.fill(0)
    }
}

private fun parseArtifact(bytes: ByteArray): LocalModelDekParseResult = try {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        val magicOk = magic.contentEquals(MAGIC)
        magic.fill(0)
        if (!magicOk) return rejected(LocalModelDekParseFailure.MALFORMED)

        if (input.readInt() != FORMAT_VERSION) {
            return rejected(LocalModelDekParseFailure.UNSUPPORTED_VERSION)
        }

        val packageId = readStrictUtf8(input) ?: return rejected(LocalModelDekParseFailure.MALFORMED)
        val modelGeneration = input.readLong()
        val dekId = readStrictUtf8(input) ?: return rejected(LocalModelDekParseFailure.MALFORMED)
        val dekGeneration = input.readLong()
        val materialSize = input.readInt()
        if (materialSize != DEK_BYTES) {
            return rejected(LocalModelDekParseFailure.MALFORMED)
        }
        val materialBytes = ByteArray(DEK_BYTES)
        input.readFully(materialBytes)
        if (input.read() != -1) {
            materialBytes.fill(0)
            return rejected(LocalModelDekParseFailure.MALFORMED)
        }

        try {
            LocalModelDekParseResult.Parsed(
                LocalModelDekArtifact(
                    model = ProtectedModelReference(
                        packageId = ProtectedModelPackageId(packageId),
                        generation = ProtectedModelGeneration(modelGeneration)
                    ),
                    dek = ModelDekReference(
                        id = ModelDekId(dekId),
                        generation = ModelDekGeneration(dekGeneration)
                    ),
                    material = ProtectedModelDekMaterial(materialBytes)
                )
            )
        } finally {
            materialBytes.fill(0)
        }
    }
} catch (_: EOFException) {
    rejected(LocalModelDekParseFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    rejected(LocalModelDekParseFailure.MALFORMED)
} catch (_: Exception) {
    rejected(LocalModelDekParseFailure.PROVIDER_FAILED)
}

private fun readStrictUtf8(input: DataInputStream): String? {
    val size = input.readInt()
    if (size <= 0 || size > MAX_STRING_BYTES) return null
    val bytes = ByteArray(size)
    input.readFully(bytes)
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    } finally {
        bytes.fill(0)
    }
}

private fun rejected(reason: LocalModelDekParseFailure): LocalModelDekParseResult.Rejected =
    LocalModelDekParseResult.Rejected(reason)

private val MAGIC = byteArrayOf(
    'L'.code.toByte(),
    'M'.code.toByte(),
    'D'.code.toByte(),
    'K'.code.toByte(),
    '1'.code.toByte()
)
private const val FORMAT_VERSION = 1
private const val MAX_ARTIFACT_BYTES = 4 * 1024
private const val MAX_STRING_BYTES = 1024
private const val DEK_BYTES = 32
