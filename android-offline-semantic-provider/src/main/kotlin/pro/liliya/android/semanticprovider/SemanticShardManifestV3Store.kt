package pro.liliya.android.semanticprovider

internal sealed interface SemanticShardManifestRootV3LoadResult {
    data object Missing : SemanticShardManifestRootV3LoadResult
    data class Loaded(val root: SemanticShardManifestRootV3) : SemanticShardManifestRootV3LoadResult
    data object Corrupt : SemanticShardManifestRootV3LoadResult
    data class Incompatible(val reason: String) : SemanticShardManifestRootV3LoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardManifestRootV3LoadResult
}

internal sealed interface SemanticShardManifestSegmentLoadResult {
    data class Loaded(val segment: SemanticShardManifestSegment) :
        SemanticShardManifestSegmentLoadResult
    data object Missing : SemanticShardManifestSegmentLoadResult
    data object Corrupt : SemanticShardManifestSegmentLoadResult
    data class Incompatible(val reason: String) : SemanticShardManifestSegmentLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardManifestSegmentLoadResult
}

internal class SemanticShardManifestV3Store(
    internal val storage: AndroidOfflineSemanticShardStorage
) {
    fun loadRoot(): SemanticShardManifestRootV3LoadResult = when (
        val read = storage.read(AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_ROOT)
    ) {
        AndroidOfflineSemanticShardStorageReadResult.Missing ->
            SemanticShardManifestRootV3LoadResult.Missing
        is AndroidOfflineSemanticShardStorageReadResult.Failed ->
            SemanticShardManifestRootV3LoadResult.Failed(read.reason, read.throwable)
        is AndroidOfflineSemanticShardStorageReadResult.Loaded -> when (
            val decoded = SemanticShardManifestV3Codec.decodeRoot(read.blob)
        ) {
            SemanticShardManifestRootV3DecodeResult.Corrupt ->
                SemanticShardManifestRootV3LoadResult.Corrupt
            is SemanticShardManifestRootV3DecodeResult.Incompatible ->
                SemanticShardManifestRootV3LoadResult.Incompatible(decoded.reason)
            is SemanticShardManifestRootV3DecodeResult.Decoded -> {
                val expectedBinding = SemanticShardManifestV3Codec.manifestBindingSha256(
                    decoded.root.publicationId,
                    decoded.root.model,
                    decoded.root.authoritative
                )
                if (expectedBinding != decoded.root.manifestBindingSha256) {
                    SemanticShardManifestRootV3LoadResult.Corrupt
                } else {
                    SemanticShardManifestRootV3LoadResult.Loaded(decoded.root)
                }
            }
        }
    }

    fun writeSegment(segment: SemanticShardManifestSegment): Boolean {
        val blob = try {
            SemanticShardManifestV3Codec.encodeSegment(segment)
        } catch (_: Exception) {
            return false
        }
        return storage.write(
            AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                segment.publicationId,
                segment.ordinal
            ),
            blob
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written
    }

    fun readSegment(
        root: SemanticShardManifestRootV3,
        ordinal: Long
    ): SemanticShardManifestSegmentLoadResult {
        if (ordinal !in 0 until root.segmentCount) {
            return SemanticShardManifestSegmentLoadResult.Corrupt
        }
        val key = AndroidOfflineSemanticShardStorageKey.forManifestSegment(
            root.publicationId,
            ordinal
        )
        return when (val read = storage.read(key)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticShardManifestSegmentLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticShardManifestSegmentLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> when (
                val decoded = SemanticShardManifestV3Codec.decodeSegment(read.blob)
            ) {
                SemanticShardManifestSegmentDecodeResult.Corrupt ->
                    SemanticShardManifestSegmentLoadResult.Corrupt
                is SemanticShardManifestSegmentDecodeResult.Incompatible ->
                    SemanticShardManifestSegmentLoadResult.Incompatible(decoded.reason)
                is SemanticShardManifestSegmentDecodeResult.Decoded -> {
                    val segment = decoded.segment
                    if (
                        segment.publicationId != root.publicationId ||
                        segment.ordinal != ordinal
                    ) {
                        SemanticShardManifestSegmentLoadResult.Corrupt
                    } else {
                        SemanticShardManifestSegmentLoadResult.Loaded(segment)
                    }
                }
            }
        }
    }

    fun writeRoot(root: SemanticShardManifestRootV3): Boolean {
        val expectedBinding = SemanticShardManifestV3Codec.manifestBindingSha256(
            root.publicationId,
            root.model,
            root.authoritative
        )
        if (expectedBinding != root.manifestBindingSha256) return false
        val blob = try {
            SemanticShardManifestV3Codec.encodeRoot(root)
        } catch (_: Exception) {
            return false
        }
        return storage.write(
            AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_ROOT,
            blob
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written
    }
}