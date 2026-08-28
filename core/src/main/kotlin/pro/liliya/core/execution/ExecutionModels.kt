package pro.liliya.core.execution

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId

@JvmInline
value class ExecutionActionId(val value: String) {
    init {
        require(value.isNotBlank()) { "execution action id must not be blank" }
    }

    override fun toString(): String = value
}

data class ExecutionRequest(
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val scope: AuthorityScope,
    val actionId: ExecutionActionId,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "execution reason must not be blank" }
    }
}

sealed interface ExecutionResult {
    data object Succeeded : ExecutionResult
    data class Rejected(val reason: String) : ExecutionResult {
        init {
            require(reason.isNotBlank()) { "execution rejection reason must not be blank" }
        }
    }

    data class Failed(val reason: String, val throwable: Throwable? = null) : ExecutionResult {
        init {
            require(reason.isNotBlank()) { "execution failure reason must not be blank" }
        }
    }
}
