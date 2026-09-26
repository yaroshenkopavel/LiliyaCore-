package pro.liliya.android.semanticprovider

internal class SemanticShardRoutingBuilder(
    private val shardStore: SemanticShardStore,
    private val manifestStore: SemanticShardManifestV3Store,
    private val routingStore: SemanticShardRoutingStore
) {
    fun rebuild(manifest: SemanticShardManifestRootV3): SemanticShardRoutingRoot? {
        val memory = DomainTreeBuilder(routingStore)
        val knowledge = DomainTreeBuilder(routingStore)

        var descriptorCount = 0L
        var previous: SemanticShardId? = null

        for (segmentOrdinal in 0 until manifest.segmentCount) {
            val segment = when (val loaded = manifestStore.readSegment(manifest, segmentOrdinal)) {
                is SemanticShardManifestSegmentLoadResult.Loaded -> loaded.segment
                SemanticShardManifestSegmentLoadResult.Missing,
                SemanticShardManifestSegmentLoadResult.Corrupt,
                is SemanticShardManifestSegmentLoadResult.Incompatible,
                is SemanticShardManifestSegmentLoadResult.Failed -> return null
            }

            for (descriptor in segment.shards) {
                val before = previous
                if (before != null && compareShardIds(before, descriptor.shardId) >= 0) return null
                previous = descriptor.shardId
                descriptorCount = try {
                    Math.addExact(descriptorCount, 1L)
                } catch (_: ArithmeticException) {
                    return null
                }

                val checkpoint = when (val loaded = shardStore.readShard(descriptor)) {
                    is SemanticShardLoadResult.Loaded -> loaded.checkpoint
                    SemanticShardLoadResult.Corrupt,
                    is SemanticShardLoadResult.Incompatible,
                    is SemanticShardLoadResult.Failed -> return null
                }
                try {
                    val envelope = SemanticShardRoutingEnvelope.fromSeeds(checkpoint.seeds)
                    val builder = when (descriptor.shardId.domain) {
                        SemanticIndexDomain.MEMORY -> memory
                        SemanticIndexDomain.KNOWLEDGE -> knowledge
                    }
                    if (!builder.append(descriptor, envelope)) return null
                } finally {
                    checkpoint.seeds.forEach { it.vector.clear() }
                }
            }
        }

        if (descriptorCount != manifest.shardDescriptorCount) return null

        val memoryRoot = memory.finish() ?: return null
        val knowledgeRoot = knowledge.finish() ?: return null
        val root = try {
            SemanticShardRoutingRoot(
                manifestPublicationId = manifest.publicationId,
                manifestBindingSha256 = manifest.manifestBindingSha256,
                memory = memoryRoot,
                knowledge = knowledgeRoot
            )
        } catch (_: Exception) {
            return null
        }
        if (!root.matches(manifest)) return null

        // Root is the routing commit point. Nodes are immutable/content-addressed and are written
        // first. A crash before this write leaves the previous routing root authoritative.
        return if (routingStore.writeRoot(root)) root else null
    }

    private class DomainTreeBuilder(
        private val store: SemanticShardRoutingStore
    ) {
        private val leafEntries =
            ArrayList<SemanticShardRoutingLeafEntry>(SemanticShardRoutingNode.FANOUT)
        private val levelBuffers = ArrayList<ArrayList<SemanticShardRoutingNodeRef>>()
        private var shardCount = 0L
        private var failed = false

        fun append(
            descriptor: SemanticShardDescriptor,
            envelope: SemanticShardRoutingEnvelope
        ): Boolean {
            if (failed) return false
            leafEntries += SemanticShardRoutingLeafEntry(descriptor, envelope)
            shardCount = try {
                Math.addExact(shardCount, 1L)
            } catch (_: ArithmeticException) {
                failed = true
                return false
            }
            if (leafEntries.size == SemanticShardRoutingNode.FANOUT) {
                if (!flushLeaf()) {
                    failed = true
                    return false
                }
            }
            return true
        }

        fun finish(): SemanticShardRoutingDomainRoot? {
            if (failed) return null
            if (leafEntries.isNotEmpty() && !flushLeaf()) return null
            if (shardCount == 0L) return SemanticShardRoutingDomainRoot.EMPTY

            while (true) {
                val nonEmpty = levelBuffers.indices.filter { levelBuffers[it].isNotEmpty() }
                val totalRefs = nonEmpty.sumOf { levelBuffers[it].size }
                if (totalRefs == 1) {
                    val level = nonEmpty.single()
                    val ref = levelBuffers[level].single()
                    return SemanticShardRoutingDomainRoot(
                        rootNodeSha256 = ref.sha256,
                        depth = ref.level + 1,
                        shardCount = shardCount
                    )
                }

                val level = nonEmpty.firstOrNull() ?: return null
                val refs = levelBuffers[level].toList()
                levelBuffers[level].clear()
                if (!writeParent(level, refs)) return null
            }
        }

        private fun flushLeaf(): Boolean {
            if (leafEntries.isEmpty()) return true
            val node = try {
                SemanticShardRoutingNode.Leaf(entries = leafEntries.toList())
            } catch (_: Exception) {
                return false
            }
            val ref = store.writeNode(node) ?: return false
            leafEntries.clear()
            return addNodeRef(ref)
        }

        private fun addNodeRef(ref: SemanticShardRoutingNodeRef): Boolean {
            ensureLevel(ref.level)
            val buffer = levelBuffers[ref.level]
            buffer += ref
            if (buffer.size < SemanticShardRoutingNode.FANOUT) return true

            val refs = buffer.toList()
            buffer.clear()
            return writeParent(ref.level, refs)
        }

        private fun writeParent(
            childLevel: Int,
            refs: List<SemanticShardRoutingNodeRef>
        ): Boolean {
            if (refs.isEmpty() || refs.size > SemanticShardRoutingNode.FANOUT) return false
            if (refs.any { it.level != childLevel }) return false

            val node = try {
                SemanticShardRoutingNode.Internal(
                    level = childLevel + 1,
                    children = refs.map {
                        SemanticShardRoutingChildEntry(
                            nodeSha256 = it.sha256,
                            envelope = it.envelope
                        )
                    }
                )
            } catch (_: Exception) {
                return false
            }
            val parent = store.writeNode(node) ?: return false
            return addNodeRef(parent)
        }

        private fun ensureLevel(level: Int) {
            while (levelBuffers.size <= level) {
                levelBuffers.add(ArrayList(SemanticShardRoutingNode.FANOUT))
            }
        }
    }

    private fun compareShardIds(left: SemanticShardId, right: SemanticShardId): Int {
        val domain = left.domain.ordinal.compareTo(right.domain.ordinal)
        return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
    }
}
