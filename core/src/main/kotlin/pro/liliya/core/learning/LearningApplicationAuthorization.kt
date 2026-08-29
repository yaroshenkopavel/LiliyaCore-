package pro.liliya.core.learning

import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.logging.LogContext

object LearningApplicationAuthorityContract {
    val capability: CapabilityId = CapabilityId("learning.application.apply")

    fun scopeFor(target: LearningApplicationTarget): AuthorityScope = when (target) {
        LearningApplicationTarget.MEMORY -> AuthorityScope("learning.application.memory")
        LearningApplicationTarget.KNOWLEDGE -> AuthorityScope("learning.application.knowledge")
    }
}

data class LearningApplicationAuthorizationReceipt(
    val preflight: LearningApplicationPreflightReceipt,
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val scope: AuthorityScope
)

sealed interface LearningApplicationAuthorizationResult {
    data class Authorized(
        val receipt: LearningApplicationAuthorizationReceipt
    ) : LearningApplicationAuthorizationResult

    data class PreflightRejected(
        val reason: LearningApplicationPreflightRejection
    ) : LearningApplicationAuthorizationResult

    data class Denied(
        val reason: String
    ) : LearningApplicationAuthorizationResult
}

class LearningApplicationAuthorizer(
    private val preflight: LearningApplicationPreflightValidator,
    private val authority: CapabilityAuthorityComposition
) {
    fun authorize(
        reference: LearningApplicationIntentReference,
        principal: AuthorityPrincipal
    ): LearningApplicationAuthorizationResult = authorizeInternal(reference, principal, null)

    internal fun authorize(
        reference: LearningApplicationIntentReference,
        principal: AuthorityPrincipal,
        context: LogContext
    ): LearningApplicationAuthorizationResult = authorizeInternal(reference, principal, context)

    private fun authorizeInternal(
        reference: LearningApplicationIntentReference,
        principal: AuthorityPrincipal,
        context: LogContext?
    ): LearningApplicationAuthorizationResult {
        val preflightResult = preflight.validate(reference)
        if (preflightResult is LearningApplicationPreflightResult.Rejected) {
            return LearningApplicationAuthorizationResult.PreflightRejected(preflightResult.reason)
        }

        val receipt = (preflightResult as LearningApplicationPreflightResult.ReadyForAuthorization).receipt
        val capability = LearningApplicationAuthorityContract.capability
        val scope = LearningApplicationAuthorityContract.scopeFor(receipt.target)
        val request = AuthorityRequest(
            principal = principal,
            capability = capability,
            scope = scope,
            reason = "controlled learning application ${receipt.application.applicationId.value}"
        )
        val authorityDecision = if (context == null) {
            authority.authorize(request)
        } else {
            authority.authorize(request, context)
        }

        return when (authorityDecision) {
            AuthorityDecision.Granted -> LearningApplicationAuthorizationResult.Authorized(
                LearningApplicationAuthorizationReceipt(
                    preflight = receipt,
                    principal = principal,
                    capability = capability,
                    scope = scope
                )
            )

            is AuthorityDecision.Denied ->
                LearningApplicationAuthorizationResult.Denied(authorityDecision.reason)
        }
    }
}
