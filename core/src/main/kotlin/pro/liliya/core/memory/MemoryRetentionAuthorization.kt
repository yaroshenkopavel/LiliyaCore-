package pro.liliya.core.memory

import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId

object MemoryRetentionAuthorityContract {
    val capability: CapabilityId = CapabilityId("memory.retention.prune")

    fun scopeFor(retentionClass: MemoryRetentionClass): AuthorityScope = AuthorityScope(
        when (retentionClass) {
            MemoryRetentionClass.WORKING -> "memory.retention.working"
            MemoryRetentionClass.EPISODIC -> "memory.retention.episodic"
            MemoryRetentionClass.SEMANTIC -> "memory.retention.semantic"
        }
    )
}

data class MemoryRetentionPruneRequest(
    val snapshot: MemoryRecordSnapshot,
    val retentionClass: MemoryRetentionClass,
    val disposition: MemoryRetentionDisposition
) {
    init {
        require(disposition != MemoryRetentionDisposition.RETAINED) {
            "retained memory must not produce a prune request"
        }
    }

    override fun toString(): String =
        "MemoryRetentionPruneRequest(recordId=${snapshot.record.id}, generation=${snapshot.generation}, retentionClass=$retentionClass, disposition=$disposition)"
}

data class MemoryRetentionAuthorizationReceipt(
    val request: MemoryRetentionPruneRequest,
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val scope: AuthorityScope
)

sealed interface MemoryRetentionAuthorizationResult {
    data class Authorized(val receipt: MemoryRetentionAuthorizationReceipt) : MemoryRetentionAuthorizationResult
    data class Denied(val reason: String) : MemoryRetentionAuthorizationResult
}

class MemoryRetentionAuthorizer(
    private val authority: CapabilityAuthorityComposition
) {
    fun authorize(
        request: MemoryRetentionPruneRequest,
        principal: AuthorityPrincipal
    ): MemoryRetentionAuthorizationResult {
        val capability = MemoryRetentionAuthorityContract.capability
        val scope = MemoryRetentionAuthorityContract.scopeFor(request.retentionClass)
        val decision = authority.authorize(
            AuthorityRequest(
                principal = principal,
                capability = capability,
                scope = scope,
                reason = "bounded retention prune ${request.snapshot.record.id.value}"
            )
        )
        return when (decision) {
            AuthorityDecision.Granted -> MemoryRetentionAuthorizationResult.Authorized(
                MemoryRetentionAuthorizationReceipt(
                    request = request,
                    principal = principal,
                    capability = capability,
                    scope = scope
                )
            )
            is AuthorityDecision.Denied -> MemoryRetentionAuthorizationResult.Denied(decision.reason)
        }
    }
}
