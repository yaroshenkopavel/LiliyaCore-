package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface AgentFactoryResult {
    data class Rejected(val reason: AgentAdmissionRejection) : AgentFactoryResult

    data class Terminal(
        val instance: AgentInstance,
        val artifact: AgentArtifact?,
        val usage: AgentRuntimeUsage?,
        val workspaceDisposed: Boolean
    ) : AgentFactoryResult
}

class AgentFactory(
    private val registry: AgentBlueprintRegistry,
    private val admissionPolicy: AgentAdmissionPolicy,
    private val runtimeAdapter: AgentRuntimeAdapter,
    private val auditLedger: AgentAuditLedger,
    private val timeSource: () -> Instant = { Instant.now() }
) {
    private val auditLock = Any()
    private val timeLock = Any()
    private val runtimeLocks = ConcurrentHashMap<String, Any>()

    fun runSingle(
        request: AgentSpawnRequest,
        population: AgentPopulationSnapshot,
        generation: AgentInstanceGeneration,
        admittedAt: Instant,
        expiresAt: Instant,
        inputReferences: Collection<String>,
        cancellationRequested: () -> Boolean = { false },
        parentScope: AgentCognitiveScope? = null,
        parentRemainingBudget: AgentWorkBudget? = null,
        workerRuntime: AgentWorkerRuntimeDescriptor? = null
    ): AgentFactoryResult {
        val blueprint = registry.resolve(request.blueprint)
        val decision = admissionPolicy.evaluate(
            request = request,
            blueprint = blueprint,
            population = population,
            parentScope = parentScope,
            parentRemainingBudget = parentRemainingBudget
        )
        if (decision is AgentAdmissionDecision.Rejected) {
            return AgentFactoryResult.Rejected(decision.reason)
        }

        val admission = AgentAdmission.create(
            requestId = request.id,
            generation = generation,
            admittedScope = request.cognitiveScope,
            admittedBudget = request.budget,
            admittedAt = admittedAt
        )
        var instance = AgentInstance.fromAdmission(admission, request)
        audit(AgentAuditEventKind.ADMITTED, request, admission, instance, admittedAt)

        instance = instance.transition(AgentLifecycleState.SPAWNED)
        audit(AgentAuditEventKind.SPAWNED, request, admission, instance, admittedAt)

        if (!expiresAt.isAfter(now())) {
            instance = instance.transition(AgentLifecycleState.EXPIRED)
            audit(AgentAuditEventKind.EXPIRED, request, admission, instance, now())
            return AgentFactoryResult.Terminal(instance, null, null, workspaceDisposed = false)
        }

        if (cancellationRequested()) {
            instance = instance.transition(AgentLifecycleState.CANCELLED)
            audit(AgentAuditEventKind.CANCELLED, request, admission, instance, now())
            return AgentFactoryResult.Terminal(instance, null, null, workspaceDisposed = false)
        }

        var workspace = try {
            AgentWorkspace.create(
                instance.id,
                inputReferences,
                request.budget.maxContextBytes
            )
        } catch (_: IllegalArgumentException) {
            instance = instance.transition(AgentLifecycleState.FAILED)
            audit(AgentAuditEventKind.FAILED, request, admission, instance, now())
            return AgentFactoryResult.Terminal(
                instance = instance,
                artifact = null,
                usage = null,
                workspaceDisposed = false
            )
        }
        try {
            instance = instance.transition(AgentLifecycleState.RUNNING)
            audit(AgentAuditEventKind.RUNNING, request, admission, instance, admittedAt)

            val context = AgentRuntimeContext(
                instanceId = instance.id,
                generation = instance.generation,
                blueprint = request.blueprint,
                scope = request.cognitiveScope,
                budget = request.budget,
                workspace = workspace,
                startedAt = admittedAt,
                expiresAt = expiresAt,
                workerRuntime = workerRuntime
            )

            val outcome = try {
                val runtimeKey = workerRuntime?.canonicalRuntimeIdentity()
                    ?: DEFAULT_RUNTIME_LOCK_KEY
                val runtimeLock = runtimeLocks.computeIfAbsent(runtimeKey) { Any() }
                synchronized(runtimeLock) {
                    if (cancellationRequested() || !now().isBefore(expiresAt)) {
                        AgentRuntimeOutcome.Failed(
                            reason = "runtime execution cancelled before adapter entry",
                            usage = AgentRuntimeUsage(0, 0, 0, 0, 0)
                        )
                    } else {
                        runtimeAdapter.run(context)
                    }
                }
            } catch (_: Exception) {
                instance = instance.transition(AgentLifecycleState.FAILED)
                audit(AgentAuditEventKind.FAILED, request, admission, instance, now())
                return AgentFactoryResult.Terminal(instance, null, null, workspaceDisposed = true)
            }

            if (cancellationRequested()) {
                instance = instance.transition(AgentLifecycleState.CANCELLED)
                audit(
                    AgentAuditEventKind.CANCELLED,
                    request,
                    admission,
                    instance,
                    now(),
                    usage = outcome.usage
                )
                return AgentFactoryResult.Terminal(instance, null, outcome.usage, workspaceDisposed = true)
            }

            if (!now().isBefore(expiresAt)) {
                instance = instance.transition(AgentLifecycleState.EXPIRED)
                audit(
                    AgentAuditEventKind.EXPIRED,
                    request,
                    admission,
                    instance,
                    now(),
                    usage = outcome.usage
                )
                return AgentFactoryResult.Terminal(instance, null, outcome.usage, workspaceDisposed = true)
            }

            if (outcome.usage.exceeds(request.budget)) {
                instance = instance.transition(AgentLifecycleState.BUDGET_EXHAUSTED)
                audit(
                    AgentAuditEventKind.BUDGET_EXHAUSTED,
                    request,
                    admission,
                    instance,
                    now(),
                    usage = outcome.usage
                )
                return AgentFactoryResult.Terminal(instance, null, outcome.usage, workspaceDisposed = true)
            }

            return when (outcome) {
                is AgentRuntimeOutcome.Failed -> {
                    instance = instance.transition(AgentLifecycleState.FAILED)
                    audit(
                        AgentAuditEventKind.FAILED,
                        request,
                        admission,
                        instance,
                        now(),
                        usage = outcome.usage
                    )
                    AgentFactoryResult.Terminal(instance, null, outcome.usage, workspaceDisposed = true)
                }
                is AgentRuntimeOutcome.Completed -> {
                    val artifact = try {
                        AgentArtifact.create(
                            producerId = instance.id,
                            producerGeneration = instance.generation,
                            rootTaskId = request.provenance.rootTaskId,
                            kind = outcome.kind,
                            payloadDigest = outcome.payloadDigest,
                            provenanceReferences = outcome.provenanceReferences,
                            createdAt = now()
                        )
                    } catch (_: IllegalArgumentException) {
                        instance = instance.transition(AgentLifecycleState.FAILED)
                        audit(
                            AgentAuditEventKind.FAILED,
                            request,
                            admission,
                            instance,
                            now(),
                            usage = outcome.usage
                        )
                        return AgentFactoryResult.Terminal(
                            instance,
                            null,
                            outcome.usage,
                            workspaceDisposed = true
                        )
                    }
                    instance = instance.transition(AgentLifecycleState.COMPLETED)
                    audit(
                        AgentAuditEventKind.COMPLETED,
                        request,
                        admission,
                        instance,
                        now(),
                        artifactId = artifact.id,
                        usage = outcome.usage
                    )
                    AgentFactoryResult.Terminal(instance, artifact, outcome.usage, workspaceDisposed = true)
                }
            }
        } finally {
            workspace = workspace.dispose()
            audit(AgentAuditEventKind.WORKSPACE_DISPOSED, request, admission, instance, now())
        }
    }

    private fun audit(
        kind: AgentAuditEventKind,
        request: AgentSpawnRequest,
        admission: AgentAdmission,
        instance: AgentInstance,
        at: Instant,
        artifactId: AgentArtifactId? = null,
        usage: AgentRuntimeUsage? = null
    ) {
        synchronized(auditLock) {
            auditLedger.append(
                AgentAuditEvent(
                    kind = kind,
                    requestId = request.id,
                    admissionId = admission.id,
                    instanceId = instance.id,
                    generation = instance.generation,
                    rootTaskId = request.provenance.rootTaskId,
                    artifactId = artifactId,
                    usage = usage,
                    occurredAt = at
                )
            )
        }
    }

    private fun now(): Instant =
        synchronized(timeLock) {
            timeSource()
        }

    private companion object {
        const val DEFAULT_RUNTIME_LOCK_KEY = "asf-default-runtime"
    }
}
