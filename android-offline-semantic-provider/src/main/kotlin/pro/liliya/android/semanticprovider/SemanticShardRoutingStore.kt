package pro.liliya.android.semanticprovider

internal data class SemanticShardRoutingNodeRef(
    val sha256: String,
    val level: Int,
    val envelope: SemanticShardRoutingEnvelope
)

internal sealed interface SemanticShardRoutingRootLoadResult {
    data object Missing : SemanticShardRoutingRootLoadResult
    data class Loaded(val root: SemanticShardRoutingRoot) : SemanticShardRoutingRootLoadResult
    data object Corrupt : SemanticShardRoutingRootLoadResult
    data class Incompatible(val reason: String) : SemanticShardRoutingRootLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardRoutingRootLoadResult
}

internal sealed interface SemanticShardRoutingNodeLoadResult {
    data object Missing : SemanticShardRoutingNodeLoadResult
    data class Loaded(val node: SemanticShardRoutingNode) : SemanticShardRoutingNodeLoadResult
    data object Corrupt : SemanticShardRoutingNodeLoadResult
    data class Incompatible(val reason: String) : SemanticShardRoutingNodeLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardRoutingNodeLoadResult
}

internal class SemanticShardRoutingStore(
    private val storage: AndroidOfflineSemanticShardStorage
) {
    fun loadRoot(): SemanticShardRoutingRootLoadResult =
        when (val read = storage.read(AndroidOfflineSemanticShardStorageKey.ROUTING_V1_ROOT)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticShardRoutingRootLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticShardRoutingRootLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded ->
                when (val decoded = SemanticShardRoutingCodec.decodeRoot(read.blob)) {
                    SemanticShardRoutingRootDecodeResult.Corrupt ->
                        SemanticShardRoutingRootLoadResult.Corrupt
                    is SemanticShardRoutingRootDecodeResult.Incompatible ->
                        SemanticShardRoutingRootLoadResult.Incompatible(decoded.reason)
                    is SemanticShardRoutingRootDecodeResult.Decoded ->
                        SemanticShardRoutingRootLoadResult.Loaded(decoded.root)
                }
        }

    fun writeRoot(root: SemanticShardRoutingRoot): Boolean {
        val blob = try {
            SemanticShardRoutingCodec.encodeRoot(root)
        } catch (_: Exception) {
            return false
        }
        return storage.write(
            AndroidOfflineSemanticShardStorageKey.ROUTING_V1_ROOT,
            blob
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written
    }

    fun readNode(sha256: String): SemanticShardRoutingNodeLoadResult {
        val key = try {
            AndroidOfflineSemanticShardStorageKey.forRoutingNode(sha256)
        } catch (_: IllegalArgumentException) {
            return SemanticShardRoutingNodeLoadResult.Corrupt
        }
        return when (val read = storage.read(key)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticShardRoutingNodeLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticShardRoutingNodeLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> {
                if (SemanticShardRoutingCodec.nodeDigest(read.blob) != sha256) {
                    SemanticShardRoutingNodeLoadResult.Corrupt
                } else {
                    when (val decoded = SemanticShardRoutingCodec.decodeNode(read.blob)) {
                        SemanticShardRoutingNodeDecodeResult.Corrupt ->
                            SemanticShardRoutingNodeLoadResult.Corrupt
                        is SemanticShardRoutingNodeDecodeResult.Incompatible ->
                            SemanticShardRoutingNodeLoadResult.Incompatible(decoded.reason)
                        is SemanticShardRoutingNodeDecodeResult.Decoded ->
                            SemanticShardRoutingNodeLoadResult.Loaded(decoded.node)
                    }
                }
            }
        }
    }

    fun writeNode(node: SemanticShardRoutingNode): SemanticShardRoutingNodeRef? {
        val blob = try {
            SemanticShardRoutingCodec.encodeNode(node)
        } catch (_: Exception) {
            return null
        }
        val digest = SemanticShardRoutingCodec.nodeDigest(blob)
        return when (
            storage.write(
                AndroidOfflineSemanticShardStorageKey.forRoutingNode(digest),
                blob
            )
        ) {
            AndroidOfflineSemanticShardStorageWriteResult.Written ->
                SemanticShardRoutingNodeRef(digest, node.level, node.envelope)
            is AndroidOfflineSemanticShardStorageWriteResult.Failed -> null
        }
    }
}
