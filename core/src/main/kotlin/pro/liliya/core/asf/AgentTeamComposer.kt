package pro.liliya.core.asf

object AgentTeamComposer {
    fun compose(request: AgentTeamCompositionRequest): AgentTeamCompositionDecision {
        if (request.taskShape.deterministicResolutionAvailable) {
            return AgentTeamCompositionDecision.DeterministicFallback(
                decisionId = deterministicFallbackId(request),
                rootTaskId = request.rootTaskId,
                policyVersion = request.policyVersion,
                inputReferences = request.inputReferences
            )
        }

        val candidatesByClass = request.candidates.associateBy { it.workerClass }
        val availableClasses = candidatesByClass.keys

        val selectedClasses = linkedSetOf<AgentWorkerClass>()
        val requirementRoutes = mutableListOf<Pair<AgentWorkerRequirement, AgentWorkerClass>>()
        request.taskShape.workerRequirements.forEach { requirement ->
            when (val routed = AgentWorkerRouter.route(requirement, availableClasses)) {
                AgentWorkerRoutingDecision.DeterministicCheck ->
                    error("deterministic requirement must not enter worker composition")
                AgentWorkerRoutingDecision.Declined ->
                    return AgentTeamCompositionDecision.Rejected(
                        AgentTeamCompositionRejection.NO_SUITABLE_WORKER
                    )
                is AgentWorkerRoutingDecision.Worker -> {
                    if (routed.workerClass !in request.capacity.allowedWorkerClasses) {
                        return AgentTeamCompositionDecision.Rejected(
                            AgentTeamCompositionRejection.CAPACITY_RESTRICTED
                        )
                    }
                    selectedClasses += routed.workerClass
                    requirementRoutes += requirement to routed.workerClass
                }
            }
        }

        val rootClass = selectedClasses.maxByOrNull { it.order }
            ?: return AgentTeamCompositionDecision.Rejected(
                AgentTeamCompositionRejection.NO_SUITABLE_WORKER
            )
        val orderedClasses = buildList {
            add(rootClass)
            selectedClasses
                .filterNot { it == rootClass }
                .sortedByDescending { it.order }
                .forEach(::add)
        }

        if (orderedClasses.size > request.capacity.maxWorkers) {
            return AgentTeamCompositionDecision.Rejected(
                AgentTeamCompositionRejection.CAPACITY_RESTRICTED
            )
        }

        val root = candidatesByClass.getValue(rootClass)
        val childCount = orderedClasses.size - 1
        if (childCount > AgentFactoryBounds.PROTOTYPE.maxDirectChildren) {
            return AgentTeamCompositionDecision.Rejected(
                AgentTeamCompositionRejection.DIRECT_CHILD_LIMIT
            )
        }
        if (root.budget.maxDescendants < childCount) {
            return AgentTeamCompositionDecision.Rejected(
                AgentTeamCompositionRejection.ROOT_DESCENDANT_BUDGET_EXCEEDED
            )
        }

        orderedClasses.drop(1).forEach { workerClass ->
            val child = candidatesByClass.getValue(workerClass)
            if (!child.cognitiveScope.isWithin(root.cognitiveScope)) {
                return AgentTeamCompositionDecision.Rejected(
                    AgentTeamCompositionRejection.ROOT_SCOPE_TOO_NARROW
                )
            }
            if (!child.budget.isWithin(root.budget)) {
                return AgentTeamCompositionDecision.Rejected(
                    AgentTeamCompositionRejection.ROOT_DESCENDANT_BUDGET_EXCEEDED
                )
            }
        }

        if (!fitsAggregateBudget(
                orderedClasses.map(candidatesByClass::getValue),
                request.aggregateBudget
            )
        ) {
            return AgentTeamCompositionDecision.Rejected(
                AgentTeamCompositionRejection.AGGREGATE_BUDGET_EXCEEDED
            )
        }

        val rootStepId = stepId(rootClass)
        val templateKind = if (orderedClasses.size == 1) {
            AgentTeamTemplateKind.SINGLE_WORKER
        } else {
            AgentTeamTemplateKind.ROOT_REVIEW_FAN_OUT
        }
        val steps = orderedClasses.mapIndexed { index, workerClass ->
            val candidate = candidatesByClass.getValue(workerClass)
            AgentCoordinatorStep.create(
                id = stepId(workerClass),
                parentStepId = if (index == 0) null else rootStepId,
                blueprint = candidate.blueprint,
                cognitiveScope = candidate.cognitiveScope,
                budget = candidate.budget,
                inputReferences = request.inputReferences,
                includeParentArtifact = index != 0,
                workerClass = candidate.workerClass,
                runtime = candidate.runtime
            )
        }
        val coordinatorPlan = AgentCoordinatorPlan(
            rootTaskId = request.rootTaskId,
            steps = steps
        )
        val assignments = requirementRoutes.map { (requirement, workerClass) ->
            AgentTeamRequirementAssignment(
                requirement = requirement,
                workerClass = workerClass,
                stepId = stepId(workerClass)
            )
        }
        val decisionId = composedDecisionId(
            request,
            orderedClasses,
            candidatesByClass,
            requirementRoutes
        )
        return AgentTeamCompositionDecision.Composed(
            AgentTeamCompositionPlan(
                decisionId = decisionId,
                policyVersion = request.policyVersion,
                inputReferences = request.inputReferences,
                selectedRequirements = request.taskShape.workerRequirements,
                selectedWorkerClasses = orderedClasses,
                assignments = assignments,
                templateKind = templateKind,
                coordinatorPlan = coordinatorPlan
            )
        )
    }

    private fun fitsAggregateBudget(
        candidates: List<AgentTeamWorkerCandidate>,
        aggregate: AgentAggregateBudget
    ): Boolean {
        if (candidates.size > aggregate.maxAgents) return false
        var wall = 0L
        var inference = 0L
        var context = 0
        var retrieval = 0
        var artifacts = 0
        return try {
            candidates.forEach { candidate ->
                wall = Math.addExact(wall, candidate.budget.maxWallClockMillis)
                inference = Math.addExact(inference, candidate.budget.maxInferenceUnits)
                context = Math.addExact(context, candidate.budget.maxContextBytes)
                retrieval = Math.addExact(retrieval, candidate.budget.maxRetrievalItems)
                artifacts = Math.addExact(artifacts, candidate.budget.maxArtifacts)
            }
            wall <= aggregate.maxWallClockMillis &&
                inference <= aggregate.maxInferenceUnits &&
                context <= aggregate.maxContextBytes &&
                retrieval <= aggregate.maxRetrievalItems &&
                artifacts <= aggregate.maxArtifacts
        } catch (_: ArithmeticException) {
            false
        }
    }

    private fun stepId(workerClass: AgentWorkerClass) =
        AgentCoordinatorStepId("team-" + workerClass.name.lowercase())

    private fun deterministicFallbackId(
        request: AgentTeamCompositionRequest
    ) = AgentTeamCompositionDecisionId(
        "asf-team-composition-" + AsfIdentity.sha256(
            "team-composition-v1",
            request.rootTaskId.value,
            request.policyVersion.value,
            "deterministic",
            "aggregate:" +
                request.aggregateBudget.maxWallClockMillis + ":" +
                request.aggregateBudget.maxInferenceUnits + ":" +
                request.aggregateBudget.maxContextBytes + ":" +
                request.aggregateBudget.maxRetrievalItems + ":" +
                request.aggregateBudget.maxArtifacts + ":" +
                request.aggregateBudget.maxAgents,
            "capacity:" +
                request.capacity.maxWorkers + ":" +
                request.capacity.allowedWorkerClasses.sortedBy { it.order }
                    .joinToString(",") { it.name },
            *request.inputReferences.toTypedArray()
        )
    )

    private fun composedDecisionId(
        request: AgentTeamCompositionRequest,
        orderedClasses: List<AgentWorkerClass>,
        candidatesByClass: Map<AgentWorkerClass, AgentTeamWorkerCandidate>,
        requirementRoutes: List<Pair<AgentWorkerRequirement, AgentWorkerClass>>
    ): AgentTeamCompositionDecisionId {
        val values = mutableListOf(
            "team-composition-v1",
            request.rootTaskId.value,
            request.policyVersion.value
        )
        values += request.inputReferences
        values += "aggregate:" +
            request.aggregateBudget.maxWallClockMillis + ":" +
            request.aggregateBudget.maxInferenceUnits + ":" +
            request.aggregateBudget.maxContextBytes + ":" +
            request.aggregateBudget.maxRetrievalItems + ":" +
            request.aggregateBudget.maxArtifacts + ":" +
            request.aggregateBudget.maxAgents
        values += "capacity:" +
            request.capacity.maxWorkers + ":" +
            request.capacity.allowedWorkerClasses.sortedBy { it.order }
                .joinToString(",") { it.name }
        request.candidates.forEach { candidate ->
            values += "candidate:" + candidate.workerClass.name +
                ":" + candidate.blueprint.id.value +
                ":" + candidate.blueprint.version.value +
                ":" + candidate.runtime.runtimeId +
                ":" + candidate.runtime.kind.name +
                ":" + (candidate.runtime.modelId ?: "")
        }
        requirementRoutes.forEach { (requirement, workerClass) ->
            values += "assignment:" + requirement.name + "->" + workerClass.name
        }
        values += "template:" + if (orderedClasses.size == 1) {
            AgentTeamTemplateKind.SINGLE_WORKER.name
        } else {
            AgentTeamTemplateKind.ROOT_REVIEW_FAN_OUT.name
        }
        orderedClasses.forEach { workerClass ->
            val candidate = candidatesByClass.getValue(workerClass)
            values += "worker:" + workerClass.name
            values += "blueprint:" + candidate.blueprint.id.value + ":" + candidate.blueprint.version.value
            values += "runtime:" + candidate.runtime.runtimeId + ":" + candidate.runtime.kind.name + ":" + (candidate.runtime.modelId ?: "")
            values += "budget:" +
                candidate.budget.maxWallClockMillis + ":" +
                candidate.budget.maxInferenceUnits + ":" +
                candidate.budget.maxContextBytes + ":" +
                candidate.budget.maxRetrievalItems + ":" +
                candidate.budget.maxArtifacts + ":" +
                candidate.budget.maxDescendants
            candidate.cognitiveScope.domains.forEach { values += "scope:" + it }
        }
        return AgentTeamCompositionDecisionId(
            "asf-team-composition-" + AsfIdentity.sha256(*values.toTypedArray())
        )
    }
}
