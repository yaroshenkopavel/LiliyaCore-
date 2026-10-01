package pro.liliya.core.asf

import java.nio.charset.StandardCharsets
import java.time.Instant

@JvmInline
value class AgentCognitiveOrganizationId(val value: String) {
    init {
        require(value.matches(Regex("asf-organization-[0-9a-f]{64}"))) {
            "cognitive organization id must use canonical sha256 identity"
        }
    }
}

@JvmInline
value class AgentCognitiveGraphNodeId(val value: String) {
    init {
        require(value.matches(Regex("asf-graph-node-[0-9a-f]{64}"))) {
            "cognitive graph node id must use canonical sha256 identity"
        }
    }
}

enum class AgentCognitiveOrganizationState {
    ACTIVE,
    RECOVERED_PAUSED,
    COLLAPSED
}

enum class AgentCognitiveGraphNodeKind {
    WORKER,
    ARTIFACT,
    VERIFICATION_GATE
}

enum class AgentCognitiveGraphEdgeKind {
    DEPENDS_ON,
    PRODUCES,
    VERIFIES
}

data class AgentCognitiveGraphBounds(
    val maxNodes: Int = 32,
    val maxEdges: Int = 64,
    val maxDepth: Int = 8,
    val maxClusters: Int = 8,
    val maxMutations: Int = 64
) {
    init {
        require(maxNodes in 1..128)
        require(maxEdges in 0..256)
        require(maxDepth in 1..32)
        require(maxClusters in 1..32)
        require(maxMutations in 1..256)
    }
}

data class AgentCognitiveGraphNode(
    val id: AgentCognitiveGraphNodeId,
    val kind: AgentCognitiveGraphNodeKind,
    val reference: String,
    val cluster: String,
    val workerClass: AgentWorkerClass? = null,
    val runtime: AgentWorkerRuntimeDescriptor? = null,
    val terminal: Boolean = false
) {
    init {
        require(reference.isNotBlank())
        require(reference.toByteArray(StandardCharsets.UTF_8).size <= 256)
        require(cluster.isNotBlank())
        require(cluster.toByteArray(StandardCharsets.UTF_8).size <= 128)
        require((workerClass == null) == (runtime == null))
        if (kind == AgentCognitiveGraphNodeKind.WORKER) {
            require(workerClass != null && runtime != null)
        } else {
            require(workerClass == null && runtime == null)
        }
    }
}

data class AgentCognitiveGraphEdge(
    val from: AgentCognitiveGraphNodeId,
    val to: AgentCognitiveGraphNodeId,
    val kind: AgentCognitiveGraphEdgeKind
) {
    init {
        require(from != to) {
            "cognitive graph edge cannot self-reference"
        }
    }
}

data class AgentCognitiveOrganizationSnapshot(
    val organizationId: AgentCognitiveOrganizationId,
    val rootTaskId: AgentRootTaskId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val bounds: AgentCognitiveGraphBounds,
    val mutationCount: Int,
    val nodes: List<AgentCognitiveGraphNode>,
    val edges: List<AgentCognitiveGraphEdge>,
    val provenanceReferences: List<String>
)

data class AgentCognitiveOrganizationMetrics(
    val nodeCount: Int,
    val edgeCount: Int,
    val activeWorkerCount: Int,
    val terminalWorkerCount: Int,
    val clusterCount: Int,
    val maxDepth: Int,
    val mutationCount: Int
)

class AgentCognitiveOrganization private constructor(
    val id: AgentCognitiveOrganizationId,
    val rootTaskId: AgentRootTaskId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val bounds: AgentCognitiveGraphBounds,
    val mutationCount: Int,
    val state: AgentCognitiveOrganizationState,
    val nodes: List<AgentCognitiveGraphNode>,
    val edges: List<AgentCognitiveGraphEdge>,
    val provenanceReferences: List<String>
) {
    init {
        require(expiresAt.isAfter(createdAt)) {
            "cognitive organization lifetime must be positive"
        }
        require(nodes.size <= bounds.maxNodes)
        require(edges.size <= bounds.maxEdges)
        require(mutationCount in 0..bounds.maxMutations)
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(edges.distinct().size == edges.size)
        require(provenanceReferences.isNotEmpty())
        require(provenanceReferences.size <= 64)
        require(provenanceReferences == provenanceReferences.distinct().sorted())
        provenanceReferences.forEach {
            require(it.isNotBlank())
            require(it.toByteArray(StandardCharsets.UTF_8).size <= 256)
        }
        val nodeIds = nodes.map { it.id }.toSet()
        require(edges.all { it.from in nodeIds && it.to in nodeIds }) {
            "cognitive graph edges must reference existing nodes"
        }
        require(nodes.map { it.cluster }.distinct().size <= bounds.maxClusters) {
            "cognitive organization exceeds cluster bound"
        }
        require(computeMaxDepth(nodes, edges) <= bounds.maxDepth) {
            "cognitive organization exceeds depth bound"
        }
        require(isAcyclic(nodes, edges)) {
            "cognitive organization graph must remain acyclic"
        }
        if (state == AgentCognitiveOrganizationState.COLLAPSED) {
            require(nodes.isEmpty() && edges.isEmpty()) {
                "collapsed organization must dispose graph structure"
            }
        }
    }

    fun addArtifact(
        producerStepId: AgentCoordinatorStepId,
        artifact: AgentArtifact
    ): AgentCognitiveOrganization {
        requireActive()
        require(artifact.rootTaskId == rootTaskId) {
            "artifact must belong to the same root task"
        }
        val producer = workerNode(producerStepId)
        val node = AgentCognitiveGraphNode(
            id = graphNodeId(
                id,
                AgentCognitiveGraphNodeKind.ARTIFACT,
                artifact.id.value
            ),
            kind = AgentCognitiveGraphNodeKind.ARTIFACT,
            reference = artifact.id.value,
            cluster = producer.cluster,
            terminal = true
        )
        require(nodes.none { it.id == node.id }) {
            "artifact already represented in cognitive organization"
        }
        return copyValidated(
            mutationCount = nextMutationCount(),
            nodes = canonicalNodes(nodes + node),
            edges = canonicalEdges(
                edges + AgentCognitiveGraphEdge(
                    from = producer.id,
                    to = node.id,
                    kind = AgentCognitiveGraphEdgeKind.PRODUCES
                )
            )
        )
    }

    fun addVerificationGate(
        gateReference: String,
        dependsOn: Collection<AgentCognitiveGraphNodeId>,
        cluster: String = "verification"
    ): AgentCognitiveOrganization {
        requireActive()
        require(gateReference.isNotBlank())
        val deps = dependsOn.distinct().sortedBy { it.value }
        require(deps.isNotEmpty()) {
            "verification gate requires at least one dependency"
        }
        require(deps.all { dep -> nodes.any { it.id == dep } }) {
            "verification dependency must already exist"
        }
        val gate = AgentCognitiveGraphNode(
            id = graphNodeId(
                id,
                AgentCognitiveGraphNodeKind.VERIFICATION_GATE,
                gateReference
            ),
            kind = AgentCognitiveGraphNodeKind.VERIFICATION_GATE,
            reference = gateReference,
            cluster = cluster
        )
        require(nodes.none { it.id == gate.id }) {
            "verification gate already represented"
        }
        val newEdges = deps.map { dep ->
            AgentCognitiveGraphEdge(
                from = dep,
                to = gate.id,
                kind = AgentCognitiveGraphEdgeKind.VERIFIES
            )
        }
        return copyValidated(
            mutationCount = nextMutationCount(),
            nodes = canonicalNodes(nodes + gate),
            edges = canonicalEdges(edges + newEdges)
        )
    }

    fun markWorkerTerminal(
        stepId: AgentCoordinatorStepId
    ): AgentCognitiveOrganization {
        requireActive()
        val worker = workerNode(stepId)
        if (worker.terminal) return this
        return copyValidated(
            mutationCount = nextMutationCount(),
            nodes = canonicalNodes(
                nodes.map {
                    if (it.id == worker.id) it.copy(terminal = true) else it
                }
            )
        )
    }

    fun contractTerminalBranch(
        stepId: AgentCoordinatorStepId
    ): AgentCognitiveOrganization {
        requireActive()
        val worker = workerNode(stepId)
        require(worker.terminal) {
            "only terminal worker branches may be contracted"
        }
        val liveWorkerDependents = edges
            .filter {
                it.from == worker.id &&
                    it.kind == AgentCognitiveGraphEdgeKind.DEPENDS_ON
            }
            .map { it.to }
            .filter { dependentId ->
                nodes.any {
                    it.id == dependentId &&
                        it.kind == AgentCognitiveGraphNodeKind.WORKER
                }
            }
        require(liveWorkerDependents.isEmpty()) {
            "worker branch cannot contract while dependent workers remain"
        }

        val producedArtifacts = edges
            .filter {
                it.from == worker.id &&
                    it.kind == AgentCognitiveGraphEdgeKind.PRODUCES
            }
            .map { it.to }
            .toSet()
        val externallyRequiredArtifacts = producedArtifacts.filter { artifactId ->
            edges.any { edge ->
                edge.from == artifactId &&
                    edge.to != worker.id
            }
        }.toSet()
        val removable = buildSet {
            add(worker.id)
            addAll(producedArtifacts - externallyRequiredArtifacts)
        }
        return copyValidated(
            mutationCount = nextMutationCount(),
            nodes = canonicalNodes(nodes.filterNot { it.id in removable }),
            edges = canonicalEdges(
                edges.filterNot { it.from in removable || it.to in removable }
            )
        )
    }

    fun collapse(
        terminalAt: Instant,
        auditReferences: Collection<String>
    ): AgentCognitiveOrganization {
        require(state != AgentCognitiveOrganizationState.COLLAPSED) {
            "cognitive organization already collapsed"
        }
        require(!terminalAt.isBefore(createdAt))
        val refs = (provenanceReferences + auditReferences)
            .distinct()
            .sorted()
        return AgentCognitiveOrganization(
            id = id,
            rootTaskId = rootTaskId,
            createdAt = createdAt,
            expiresAt = expiresAt,
            bounds = bounds,
            mutationCount = mutationCount,
            state = AgentCognitiveOrganizationState.COLLAPSED,
            nodes = emptyList(),
            edges = emptyList(),
            provenanceReferences = refs
        )
    }

    fun snapshot(): AgentCognitiveOrganizationSnapshot {
        require(state == AgentCognitiveOrganizationState.ACTIVE) {
            "only active organization may be snapshotted"
        }
        return AgentCognitiveOrganizationSnapshot(
            organizationId = id,
            rootTaskId = rootTaskId,
            createdAt = createdAt,
            expiresAt = expiresAt,
            bounds = bounds,
            mutationCount = mutationCount,
            nodes = nodes,
            edges = edges,
            provenanceReferences = provenanceReferences
        )
    }

    fun metrics(): AgentCognitiveOrganizationMetrics =
        AgentCognitiveOrganizationMetrics(
            nodeCount = nodes.size,
            edgeCount = edges.size,
            activeWorkerCount = nodes.count {
                it.kind == AgentCognitiveGraphNodeKind.WORKER && !it.terminal
            },
            terminalWorkerCount = nodes.count {
                it.kind == AgentCognitiveGraphNodeKind.WORKER && it.terminal
            },
            clusterCount = nodes.map { it.cluster }.distinct().size,
            maxDepth = computeMaxDepth(nodes, edges),
            mutationCount = mutationCount
        )

    private fun workerNode(stepId: AgentCoordinatorStepId): AgentCognitiveGraphNode =
        nodes.firstOrNull {
            it.kind == AgentCognitiveGraphNodeKind.WORKER &&
                it.reference == stepId.value
        } ?: error("worker step is not represented in cognitive organization")

    private fun nextMutationCount(): Int {
        require(mutationCount < bounds.maxMutations) {
            "cognitive organization mutation budget exhausted"
        }
        return mutationCount + 1
    }

    private fun requireActive() {
        require(state == AgentCognitiveOrganizationState.ACTIVE) {
            "cognitive organization mutation requires active state"
        }
    }

    private fun copyValidated(
        mutationCount: Int = this.mutationCount,
        nodes: List<AgentCognitiveGraphNode> = this.nodes,
        edges: List<AgentCognitiveGraphEdge> = this.edges
    ) = AgentCognitiveOrganization(
        id = id,
        rootTaskId = rootTaskId,
        createdAt = createdAt,
        expiresAt = expiresAt,
        bounds = bounds,
        mutationCount = mutationCount,
        state = state,
        nodes = nodes,
        edges = edges,
        provenanceReferences = provenanceReferences
    )

    fun experimentAgainst(
        plan: AgentTeamCompositionPlan
    ): AgentCognitiveOrganizationExperimentResult {
        require(plan.coordinatorPlan.rootTaskId == rootTaskId) {
            "controlled experiment must use the same root task"
        }
        val baselineWorkers = plan.coordinatorPlan.steps.size
        val activeWorkers = nodes.count {
            it.kind == AgentCognitiveGraphNodeKind.WORKER
        }
        return AgentCognitiveOrganizationExperimentResult(
            baselineStaticWorkerCount = baselineWorkers,
            dynamicWorkerNodeCount = activeWorkers,
            workerSurfaceReduction = baselineWorkers - activeWorkers,
            dynamicNodeCount = nodes.size,
            dynamicEdgeCount = edges.size,
            graphMutationCount = mutationCount,
            preservedProvenanceReferenceCount = provenanceReferences.size
        )
    }

    companion object {
        fun fromComposition(
            plan: AgentTeamCompositionPlan,
            createdAt: Instant,
            expiresAt: Instant,
            bounds: AgentCognitiveGraphBounds = AgentCognitiveGraphBounds()
        ): AgentCognitiveOrganization {
            val organizationId = organizationId(
                plan.coordinatorPlan.rootTaskId,
                plan.decisionId
            )
            val stepToNode = plan.coordinatorPlan.steps.associate { step ->
                step.id to AgentCognitiveGraphNode(
                    id = graphNodeId(
                        organizationId,
                        AgentCognitiveGraphNodeKind.WORKER,
                        step.id.value
                    ),
                    kind = AgentCognitiveGraphNodeKind.WORKER,
                    reference = step.id.value,
                    cluster = step.parentStepId?.value ?: "root",
                    workerClass = requireNotNull(step.workerClass),
                    runtime = requireNotNull(step.runtime)
                )
            }
            val edges = plan.coordinatorPlan.steps.mapNotNull { step ->
                step.parentStepId?.let { parent ->
                    AgentCognitiveGraphEdge(
                        from = stepToNode.getValue(parent).id,
                        to = stepToNode.getValue(step.id).id,
                        kind = AgentCognitiveGraphEdgeKind.DEPENDS_ON
                    )
                }
            }
            return AgentCognitiveOrganization(
                id = organizationId,
                rootTaskId = plan.coordinatorPlan.rootTaskId,
                createdAt = createdAt,
                expiresAt = expiresAt,
                bounds = bounds,
                mutationCount = 0,
                state = AgentCognitiveOrganizationState.ACTIVE,
                nodes = canonicalNodes(stepToNode.values),
                edges = canonicalEdges(edges),
                provenanceReferences = (
                    plan.inputReferences +
                        ("composition-decision:" + plan.decisionId.value)
                    ).distinct().sorted()
            )
        }

        fun recoverPaused(
            snapshot: AgentCognitiveOrganizationSnapshot,
            recoveredAt: Instant,
            recoveryReference: String
        ): AgentCognitiveOrganization {
            require(recoveryReference.isNotBlank())
            require(!recoveredAt.isBefore(snapshot.createdAt))
            return AgentCognitiveOrganization(
                id = snapshot.organizationId,
                rootTaskId = snapshot.rootTaskId,
                createdAt = snapshot.createdAt,
                expiresAt = snapshot.expiresAt,
                bounds = snapshot.bounds,
                mutationCount = snapshot.mutationCount,
                state = AgentCognitiveOrganizationState.RECOVERED_PAUSED,
                nodes = snapshot.nodes,
                edges = snapshot.edges,
                provenanceReferences = (
                    snapshot.provenanceReferences +
                        ("recovery:" + recoveryReference)
                    ).distinct().sorted()
            )
        }

        private fun organizationId(
            rootTaskId: AgentRootTaskId,
            decisionId: AgentTeamCompositionDecisionId
        ) = AgentCognitiveOrganizationId(
            "asf-organization-" + AsfIdentity.sha256(
                "cognitive-organization-v1",
                rootTaskId.value,
                decisionId.value
            )
        )

        private fun graphNodeId(
            organizationId: AgentCognitiveOrganizationId,
            kind: AgentCognitiveGraphNodeKind,
            reference: String
        ) = AgentCognitiveGraphNodeId(
            "asf-graph-node-" + AsfIdentity.sha256(
                "cognitive-graph-node-v1",
                organizationId.value,
                kind.name,
                reference
            )
        )

        private fun canonicalNodes(
            nodes: Collection<AgentCognitiveGraphNode>
        ) = nodes.sortedBy { it.id.value }

        private fun canonicalEdges(
            edges: Collection<AgentCognitiveGraphEdge>
        ) = edges.distinct().sortedWith(
            compareBy<AgentCognitiveGraphEdge>(
                { it.from.value },
                { it.to.value },
                { it.kind.ordinal }
            )
        )

        private fun isAcyclic(
            nodes: List<AgentCognitiveGraphNode>,
            edges: List<AgentCognitiveGraphEdge>
        ): Boolean {
            val outgoing = edges.groupBy { it.from }
            val visiting = mutableSetOf<AgentCognitiveGraphNodeId>()
            val visited = mutableSetOf<AgentCognitiveGraphNodeId>()

            fun visit(node: AgentCognitiveGraphNodeId): Boolean {
                if (node in visiting) return false
                if (node in visited) return true
                visiting += node
                for (edge in outgoing[node].orEmpty()) {
                    if (!visit(edge.to)) return false
                }
                visiting -= node
                visited += node
                return true
            }

            return nodes.all { visit(it.id) }
        }

        private fun computeMaxDepth(
            nodes: List<AgentCognitiveGraphNode>,
            edges: List<AgentCognitiveGraphEdge>
        ): Int {
            if (nodes.isEmpty()) return 0
            val incoming = edges.groupBy { it.to }
            val memo = mutableMapOf<AgentCognitiveGraphNodeId, Int>()
            fun depth(node: AgentCognitiveGraphNodeId): Int =
                memo.getOrPut(node) {
                    val parents = incoming[node].orEmpty().map { it.from }
                    if (parents.isEmpty()) 1 else 1 + parents.maxOf(::depth)
                }
            return nodes.maxOf { depth(it.id) }
        }
    }
}


data class AgentCognitiveOrganizationExperimentResult(
    val baselineStaticWorkerCount: Int,
    val dynamicWorkerNodeCount: Int,
    val workerSurfaceReduction: Int,
    val dynamicNodeCount: Int,
    val dynamicEdgeCount: Int,
    val graphMutationCount: Int,
    val preservedProvenanceReferenceCount: Int
) {
    init {
        require(baselineStaticWorkerCount > 0)
        require(dynamicWorkerNodeCount >= 0)
        require(workerSurfaceReduction ==
            baselineStaticWorkerCount - dynamicWorkerNodeCount)
        require(dynamicNodeCount >= dynamicWorkerNodeCount)
        require(dynamicEdgeCount >= 0)
        require(graphMutationCount >= 0)
        require(preservedProvenanceReferenceCount > 0)
    }
}
