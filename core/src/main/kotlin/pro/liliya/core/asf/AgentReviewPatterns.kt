package pro.liliya.core.asf

enum class AgentReviewPatternKind {
    RESEARCH_VERIFY,
    PLAN_CRITIQUE,
    PROPOSE_ADVERSARIAL_REVIEW
}

data class AgentReviewPatternPlan(
    val kind: AgentReviewPatternKind,
    val coordinatorPlan: AgentCoordinatorPlan,
    val primaryStepId: AgentCoordinatorStepId,
    val reviewerStepId: AgentCoordinatorStepId,
    val reviewKind: AgentReviewKind
) {
    init {
        require(primaryStepId != reviewerStepId) { "review pattern step ids must be distinct" }
        val primary = requireNotNull(coordinatorPlan.step(primaryStepId)) {
            "review pattern primary step must exist"
        }
        val reviewer = requireNotNull(coordinatorPlan.step(reviewerStepId)) {
            "review pattern reviewer step must exist"
        }
        require(primary.parentStepId == null) { "review pattern primary step must be root" }
        require(reviewer.parentStepId == primaryStepId) {
            "review pattern reviewer must be a direct child of primary"
        }
        require(reviewer.includeParentArtifact) {
            "review pattern reviewer must receive primary artifact provenance"
        }
    }
}

object AgentReviewPatterns {
    fun researcherVerifier(
        rootTaskId: AgentRootTaskId,
        researcherBlueprint: AgentBlueprintReference,
        verifierBlueprint: AgentBlueprintReference,
        researcherScope: AgentCognitiveScope,
        verifierScope: AgentCognitiveScope,
        researcherBudget: AgentWorkBudget,
        verifierBudget: AgentWorkBudget,
        researchInputs: Collection<String>,
        verifierContextInputs: Collection<String>
    ): AgentReviewPatternPlan = twoStage(
        kind = AgentReviewPatternKind.RESEARCH_VERIFY,
        reviewKind = AgentReviewKind.VERIFICATION,
        rootTaskId = rootTaskId,
        primaryId = AgentCoordinatorStepId("research"),
        reviewerId = AgentCoordinatorStepId("verify"),
        primaryBlueprint = researcherBlueprint,
        reviewerBlueprint = verifierBlueprint,
        primaryScope = researcherScope,
        reviewerScope = verifierScope,
        primaryBudget = researcherBudget,
        reviewerBudget = verifierBudget,
        primaryInputs = researchInputs,
        reviewerInputs = verifierContextInputs
    )

    fun plannerCritic(
        rootTaskId: AgentRootTaskId,
        plannerBlueprint: AgentBlueprintReference,
        criticBlueprint: AgentBlueprintReference,
        plannerScope: AgentCognitiveScope,
        criticScope: AgentCognitiveScope,
        plannerBudget: AgentWorkBudget,
        criticBudget: AgentWorkBudget,
        planningInputs: Collection<String>,
        criticContextInputs: Collection<String>
    ): AgentReviewPatternPlan = twoStage(
        kind = AgentReviewPatternKind.PLAN_CRITIQUE,
        reviewKind = AgentReviewKind.CRITIQUE,
        rootTaskId = rootTaskId,
        primaryId = AgentCoordinatorStepId("plan"),
        reviewerId = AgentCoordinatorStepId("critic"),
        primaryBlueprint = plannerBlueprint,
        reviewerBlueprint = criticBlueprint,
        primaryScope = plannerScope,
        reviewerScope = criticScope,
        primaryBudget = plannerBudget,
        reviewerBudget = criticBudget,
        primaryInputs = planningInputs,
        reviewerInputs = criticContextInputs
    )

    fun proposerAdversarialReviewer(
        rootTaskId: AgentRootTaskId,
        proposerBlueprint: AgentBlueprintReference,
        reviewerBlueprint: AgentBlueprintReference,
        proposerScope: AgentCognitiveScope,
        reviewerScope: AgentCognitiveScope,
        proposerBudget: AgentWorkBudget,
        reviewerBudget: AgentWorkBudget,
        proposalInputs: Collection<String>,
        reviewerContextInputs: Collection<String>
    ): AgentReviewPatternPlan = twoStage(
        kind = AgentReviewPatternKind.PROPOSE_ADVERSARIAL_REVIEW,
        reviewKind = AgentReviewKind.ADVERSARIAL_REVIEW,
        rootTaskId = rootTaskId,
        primaryId = AgentCoordinatorStepId("propose"),
        reviewerId = AgentCoordinatorStepId("adversarial-review"),
        primaryBlueprint = proposerBlueprint,
        reviewerBlueprint = reviewerBlueprint,
        primaryScope = proposerScope,
        reviewerScope = reviewerScope,
        primaryBudget = proposerBudget,
        reviewerBudget = reviewerBudget,
        primaryInputs = proposalInputs,
        reviewerInputs = reviewerContextInputs
    )

    private fun twoStage(
        kind: AgentReviewPatternKind,
        reviewKind: AgentReviewKind,
        rootTaskId: AgentRootTaskId,
        primaryId: AgentCoordinatorStepId,
        reviewerId: AgentCoordinatorStepId,
        primaryBlueprint: AgentBlueprintReference,
        reviewerBlueprint: AgentBlueprintReference,
        primaryScope: AgentCognitiveScope,
        reviewerScope: AgentCognitiveScope,
        primaryBudget: AgentWorkBudget,
        reviewerBudget: AgentWorkBudget,
        primaryInputs: Collection<String>,
        reviewerInputs: Collection<String>
    ): AgentReviewPatternPlan {
        require(reviewerScope.isWithin(primaryScope)) {
            "reviewer scope must be within primary scope"
        }
        require(reviewerBudget.isWithin(primaryBudget)) {
            "reviewer budget must be within primary budget"
        }
        require(primaryBudget.maxDescendants >= 1) {
            "primary review pattern budget must allow one reviewer child"
        }

        val primary = AgentCoordinatorStep.create(
            id = primaryId,
            parentStepId = null,
            blueprint = primaryBlueprint,
            cognitiveScope = primaryScope,
            budget = primaryBudget,
            inputReferences = primaryInputs
        )
        val reviewer = AgentCoordinatorStep.create(
            id = reviewerId,
            parentStepId = primaryId,
            blueprint = reviewerBlueprint,
            cognitiveScope = reviewerScope,
            budget = reviewerBudget,
            inputReferences = reviewerInputs,
            includeParentArtifact = true
        )

        return AgentReviewPatternPlan(
            kind = kind,
            coordinatorPlan = AgentCoordinatorPlan(rootTaskId, listOf(primary, reviewer)),
            primaryStepId = primaryId,
            reviewerStepId = reviewerId,
            reviewKind = reviewKind
        )
    }
}
