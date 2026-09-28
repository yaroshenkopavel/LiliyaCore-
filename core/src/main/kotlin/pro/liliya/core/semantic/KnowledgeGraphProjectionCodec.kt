package pro.liliya.core.semantic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface KnowledgeGraphProjectionManifestDecodeResult {
    data class Decoded(val manifest: KnowledgeGraphProjectionManifest) :
        KnowledgeGraphProjectionManifestDecodeResult
    data object Corrupt : KnowledgeGraphProjectionManifestDecodeResult
    data class Incompatible(val reason: String) :
        KnowledgeGraphProjectionManifestDecodeResult
}

internal sealed interface KnowledgeGraphProjectionFragmentDecodeResult {
    data class Decoded(val fragment: KnowledgeGraphStoredFragment) :
        KnowledgeGraphProjectionFragmentDecodeResult
    data object Corrupt : KnowledgeGraphProjectionFragmentDecodeResult
    data class Incompatible(val reason: String) :
        KnowledgeGraphProjectionFragmentDecodeResult
}

internal object KnowledgeGraphProjectionCodec {
    private const val MANIFEST_MAGIC = 0x4B47504D // KGPM
    private const val FRAGMENT_MAGIC = 0x4B475046 // KGPF
    private const val MAX_STRING_BYTES = 4096

    val schemaId = PersistentSchemaId("knowledge-graph-projection-index")
    private val schemaVersion = PersistentSchemaVersion(1)
    val manifestEntityId = PersistentEntityId("knowledge-graph-projection-manifest-v1")

    fun fragmentEntityId(
        buildEpoch: String,
        reference: SemanticClaimVersionReference
    ): PersistentEntityId =
        PersistentEntityId(
            "knowledge-graph-fragment-" + epochDigest(buildEpoch) + "-" +
                referenceDigest(reference)
        )

    fun encodeManifest(manifest: KnowledgeGraphProjectionManifest): PersistentRecord =
        record(manifestEntityId, manifest.buildEpoch) { out ->
            out.writeInt(MANIFEST_MAGIC)
            out.writeInt(manifest.version)
            writeString(out, manifest.buildEpoch)
            out.writeLong(manifest.source.revision)
            out.writeLong(manifest.source.highWatermark)
            out.writeLong(manifest.source.entryCount)
            out.writeInt(manifest.state.ordinal)
            out.writeInt(manifest.policyVersion)
            out.writeInt(manifest.claimPageSize)
            out.writeLong(manifest.projectedClaimCount)
            out.writeLong(manifest.projectedNodeCount)
            out.writeLong(manifest.projectedEdgeCount)
        }

    fun decodeManifest(record: PersistentRecord): KnowledgeGraphProjectionManifestDecodeResult {
        if (record.schemaId != schemaId || record.schemaVersion != schemaVersion) {
            return KnowledgeGraphProjectionManifestDecodeResult.Incompatible(
                "knowledge graph projection manifest schema mismatch"
            )
        }
        return try {
            DataInputStream(ByteArrayInputStream(record.payload.copyBytes())).use { input ->
                if (input.readInt() != MANIFEST_MAGIC) {
                    return KnowledgeGraphProjectionManifestDecodeResult.Corrupt
                }
                val version = input.readInt()
                if (version != KnowledgeGraphProjectionManifest.CURRENT_VERSION) {
                    return KnowledgeGraphProjectionManifestDecodeResult.Incompatible(
                        "knowledge graph projection manifest version mismatch"
                    )
                }
                val manifest = KnowledgeGraphProjectionManifest(
                    version = version,
                    buildEpoch = readString(input),
                    source = SemanticClaimSourceCheckpoint(
                        revision = input.readLong(),
                        highWatermark = input.readLong(),
                        entryCount = input.readLong()
                    ),
                    state = enumValue(
                        input.readInt(),
                        KnowledgeGraphProjectionState.entries
                    ) ?: return KnowledgeGraphProjectionManifestDecodeResult.Corrupt,
                    policyVersion = input.readInt(),
                    claimPageSize = input.readInt(),
                    projectedClaimCount = input.readLong(),
                    projectedNodeCount = input.readLong(),
                    projectedEdgeCount = input.readLong()
                )
                if (input.read() != -1) {
                    KnowledgeGraphProjectionManifestDecodeResult.Corrupt
                } else {
                    KnowledgeGraphProjectionManifestDecodeResult.Decoded(manifest)
                }
            }
        } catch (_: EOFException) {
            KnowledgeGraphProjectionManifestDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            KnowledgeGraphProjectionManifestDecodeResult.Corrupt
        }
    }

    fun encodeFragment(fragment: KnowledgeGraphStoredFragment): PersistentRecord =
        record(
            fragmentEntityId(fragment.buildEpoch, fragment.sourceClaim),
            fragment.buildEpoch
        ) { out ->
            out.writeInt(FRAGMENT_MAGIC)
            out.writeInt(fragment.version)
            writeString(out, fragment.buildEpoch)
            writeReference(out, fragment.sourceClaim)
            out.writeBoolean(fragment.advisoryOnly)
            out.writeInt(fragment.nodes.size)
            fragment.nodes.forEach { node ->
                writeString(out, node.id.value)
                out.writeInt(node.kind.ordinal)
            }
            out.writeInt(fragment.edges.size)
            fragment.edges.forEach { edge ->
                writeString(out, edge.id.value)
                out.writeInt(edge.kind.ordinal)
                writeString(out, edge.source.value)
                writeString(out, edge.target.value)
                writeReference(out, edge.claimReference)
            }
        }

    fun decodeFragment(record: PersistentRecord): KnowledgeGraphProjectionFragmentDecodeResult {
        if (record.schemaId != schemaId || record.schemaVersion != schemaVersion) {
            return KnowledgeGraphProjectionFragmentDecodeResult.Incompatible(
                "knowledge graph projection fragment schema mismatch"
            )
        }
        return try {
            DataInputStream(ByteArrayInputStream(record.payload.copyBytes())).use { input ->
                if (input.readInt() != FRAGMENT_MAGIC) {
                    return KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
                }
                val version = input.readInt()
                if (version != KnowledgeGraphStoredFragment.CURRENT_VERSION) {
                    return KnowledgeGraphProjectionFragmentDecodeResult.Incompatible(
                        "knowledge graph projection fragment version mismatch"
                    )
                }
                val epoch = readString(input)
                val source = readReference(input)
                val advisory = input.readBoolean()

                val nodeCount = input.readInt()
                if (nodeCount !in 2..KnowledgeGraphSingleClaimProjector.MAX_NODES_PER_CLAIM) {
                    return KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
                }
                val nodes = List(nodeCount) {
                    val id = KnowledgeGraphNodeId(readString(input))
                    val kind = enumValue(
                        input.readInt(),
                        KnowledgeGraphNodeKind.entries
                    ) ?: return KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
                    KnowledgeGraphStoredNode(id, kind)
                }

                val edgeCount = input.readInt()
                if (edgeCount != KnowledgeGraphSingleClaimProjector.MAX_EDGES_PER_CLAIM) {
                    return KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
                }
                val edges = List(edgeCount) {
                    val id = KnowledgeGraphEdgeId(readString(input))
                    val kind = enumValue(
                        input.readInt(),
                        KnowledgeGraphEdgeKind.entries
                    ) ?: return KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
                    KnowledgeGraphStoredEdge(
                        id = id,
                        kind = kind,
                        source = KnowledgeGraphNodeId(readString(input)),
                        target = KnowledgeGraphNodeId(readString(input)),
                        claimReference = readReference(input)
                    )
                }

                val fragment = KnowledgeGraphStoredFragment(
                    version = version,
                    buildEpoch = epoch,
                    sourceClaim = source,
                    nodes = nodes,
                    edges = edges,
                    advisoryOnly = advisory
                )
                if (input.read() != -1) {
                    KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
                } else {
                    KnowledgeGraphProjectionFragmentDecodeResult.Decoded(fragment)
                }
            }
        } catch (_: EOFException) {
            KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            KnowledgeGraphProjectionFragmentDecodeResult.Corrupt
        }
    }

    private fun writeReference(
        out: DataOutputStream,
        reference: SemanticClaimVersionReference
    ) {
        writeString(out, reference.claimId.value)
        out.writeLong(reference.version.value)
    }

    private fun readReference(input: DataInputStream): SemanticClaimVersionReference =
        SemanticClaimVersionReference(
            SemanticClaimId(readString(input)),
            SemanticClaimVersion(input.readLong())
        )

    private fun record(
        id: PersistentEntityId,
        epoch: String,
        writer: (DataOutputStream) -> Unit
    ): PersistentRecord {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(writer)
        return PersistentRecord(
            id = id,
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(output.toByteArray()),
            createdAt = stableInstant(epoch)
        )
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun stableInstant(epoch: String): Instant {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(epoch.toByteArray(StandardCharsets.UTF_8))
        val seconds = ByteBuffer.wrap(digest.copyOfRange(0, 8)).long and Long.MAX_VALUE
        return Instant.ofEpochSecond(seconds % 4_102_444_800L)
    }

    private fun epochDigest(epoch: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, epoch)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun referenceDigest(reference: SemanticClaimVersionReference): String {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, reference.claimId.value)
        put(digest, reference.version.value.toString())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun <T> enumValue(index: Int, values: List<T>): T? =
        values.getOrNull(index)
}
