package pro.liliya.android.semanticprovider

@JvmInline
value class AndroidOfflineSemanticShardStorageKey internal constructor(
    val value: String
) {
    init {
        require(KEY.matches(value)) { "invalid semantic shard storage key" }
    }

    override fun toString(): String = "AndroidOfflineSemanticShardStorageKey(<redacted>)"

    internal companion object {
        private val KEY = Regex("[a-z0-9-]{1,80}")

        val MANIFEST = AndroidOfflineSemanticShardStorageKey("manifest-v2")

        fun forShard(shardId: SemanticShardId): AndroidOfflineSemanticShardStorageKey =
            AndroidOfflineSemanticShardStorageKey(
                when (shardId.domain) {
                    SemanticIndexDomain.MEMORY -> "memory-"
                    SemanticIndexDomain.KNOWLEDGE -> "knowledge-"
                } + shardId.ordinal.toString()
            )
    }
}

sealed interface AndroidOfflineSemanticShardStorageReadResult {
    data object Missing : AndroidOfflineSemanticShardStorageReadResult
    data class Loaded(
        val blob: AndroidOfflineSemanticCheckpointBlob
    ) : AndroidOfflineSemanticShardStorageReadResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidOfflineSemanticShardStorageReadResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

sealed interface AndroidOfflineSemanticShardStorageWriteResult {
    data object Written : AndroidOfflineSemanticShardStorageWriteResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidOfflineSemanticShardStorageWriteResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

/**
 * Host-owned encrypted storage for v2 semantic manifest and bounded generation shards.
 *
 * Keys carry only provider-defined shard identity. Blob contents remain opaque to the host.
 */
interface AndroidOfflineSemanticShardStorage {
    fun read(
        key: AndroidOfflineSemanticShardStorageKey
    ): AndroidOfflineSemanticShardStorageReadResult

    fun write(
        key: AndroidOfflineSemanticShardStorageKey,
        blob: AndroidOfflineSemanticCheckpointBlob
    ): AndroidOfflineSemanticShardStorageWriteResult
}

internal sealed interface SemanticShardManifestLoadResult {
    data object Missing : SemanticShardManifestLoadResult
    data class Loaded(val manifest: SemanticShardManifest) : SemanticShardManifestLoadResult
    data object Corrupt : SemanticShardManifestLoadResult
    data class Incompatible(val reason: String) : SemanticShardManifestLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardManifestLoadResult
}

internal sealed interface SemanticShardRankResult {
    data class Ranked(val candidates: List<SemanticRankedCandidate>) : SemanticShardRankResult
    data object Corrupt : SemanticShardRankResult
    data class Incompatible(val reason: String) : SemanticShardRankResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardRankResult
}

internal class SemanticShardStore(
    private val storage: AndroidOfflineSemanticShardStorage,
    private val profileGeneration: SemanticProfileGeneration
) {
    fun loadManifest(): SemanticShardManifestLoadResult =
        when (val read = storage.read(AndroidOfflineSemanticShardStorageKey.MANIFEST)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticShardManifestLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticShardManifestLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded ->
                when (val decoded = SemanticShardCheckpointCodec.decodeManifest(read.blob)) {
                    is SemanticShardManifestDecodeResult.Decoded ->
                        SemanticShardManifestLoadResult.Loaded(decoded.manifest)
                    SemanticShardManifestDecodeResult.Corrupt ->
                        SemanticShardManifestLoadResult.Corrupt
                    is SemanticShardManifestDecodeResult.Incompatible ->
                        SemanticShardManifestLoadResult.Incompatible(decoded.reason)
                }
        }

    fun rank(
        manifest: SemanticShardManifest,
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): SemanticShardRankResult {
        require(maxCandidates > 0)
        val shardTopK = ArrayList<List<SemanticRankedCandidate>>()

        for (descriptor in manifest.shards) {
            if (descriptor.shardId.domain != domain) continue
            val key = AndroidOfflineSemanticShardStorageKey.forShard(descriptor.shardId)
            val blob = when (val read = storage.read(key)) {
                AndroidOfflineSemanticShardStorageReadResult.Missing ->
                    return SemanticShardRankResult.Corrupt
                is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                    return SemanticShardRankResult.Failed(read.reason, read.throwable)
                is AndroidOfflineSemanticShardStorageReadResult.Loaded -> read.blob
            }
            if (SemanticShardCheckpointCodec.shardDigest(blob) != descriptor.blobSha256) {
                return SemanticShardRankResult.Corrupt
            }
            val checkpoint = when (val decoded = SemanticShardCheckpointCodec.decodeShard(blob)) {
                SemanticShardDecodeResult.Corrupt ->
                    return SemanticShardRankResult.Corrupt
                is SemanticShardDecodeResult.Incompatible ->
                    return SemanticShardRankResult.Incompatible(decoded.reason)
                is SemanticShardDecodeResult.Decoded -> decoded.checkpoint
            }
            if (
                checkpoint.shardId != descriptor.shardId ||
                checkpoint.seeds.size != descriptor.entryCount
            ) {
                checkpoint.seeds.forEach { it.vector.clear() }
                return SemanticShardRankResult.Corrupt
            }

            val index = SemanticFlatIndex(
                profileGeneration = profileGeneration,
                limits = shardLimits()
            )
            var accepted = true
            try {
                for (seed in checkpoint.seeds) {
                    if (index.addExact(seed.source, seed.vector) != SemanticIndexAddResult.Indexed) {
                        accepted = false
                        break
                    }
                }
                if (!accepted) return SemanticShardRankResult.Corrupt
                shardTopK += index.rank(domain, query, maxCandidates)
            } finally {
                index.clear()
                if (!accepted) {
                    checkpoint.seeds.forEach { it.vector.clear() }
                }
            }
        }

        return SemanticShardRankResult.Ranked(
            SemanticShardRankMerger.merge(shardTopK, maxCandidates)
        )
    }

    fun writeShard(
        checkpoint: SemanticShardCheckpoint
    ): SemanticShardDescriptor? {
        val blob = try {
            SemanticShardCheckpointCodec.encodeShard(checkpoint)
        } catch (_: Exception) {
            return null
        }
        val descriptor = SemanticShardDescriptor(
            shardId = checkpoint.shardId,
            entryCount = checkpoint.seeds.size,
            blobSha256 = SemanticShardCheckpointCodec.shardDigest(blob)
        )
        return when (
            storage.write(
                AndroidOfflineSemanticShardStorageKey.forShard(checkpoint.shardId),
                blob
            )
        ) {
            AndroidOfflineSemanticShardStorageWriteResult.Written -> descriptor
            is AndroidOfflineSemanticShardStorageWriteResult.Failed -> null
        }
    }

    fun writeManifest(
        manifest: SemanticShardManifest
    ): Boolean {
        val blob = try {
            SemanticShardCheckpointCodec.encodeManifest(manifest)
        } catch (_: Exception) {
            return false
        }
        return storage.write(
            AndroidOfflineSemanticShardStorageKey.MANIFEST,
            blob
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written
    }

    private fun shardLimits(): SemanticFlatIndexLimits =
        SemanticFlatIndexLimits(
            maxMemoryEntries = SemanticShardLayout.ENTRIES_PER_SHARD.toInt(),
            maxKnowledgeEntries = SemanticShardLayout.ENTRIES_PER_SHARD.toInt(),
            maxTotalEntries = SemanticShardLayout.ENTRIES_PER_SHARD.toInt()
        )
}
