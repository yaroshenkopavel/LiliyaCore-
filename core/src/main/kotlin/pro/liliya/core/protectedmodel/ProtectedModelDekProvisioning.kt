package pro.liliya.core.protectedmodel

data class ProtectedModelDekProvisioningRequest(
    val model: ProtectedModelReference,
    val dek: ModelDekReference
)

class ProtectedModelDekProvisioningEvidence(
    val model: ProtectedModelReference,
    val dek: ModelDekReference,
    val material: ProtectedModelDekMaterial
) {
    override fun toString(): String =
        "ProtectedModelDekProvisioningEvidence(model=$model,dek=$dek,material=<redacted>)"
}

enum class ProtectedModelDekProvisioningFailure {
    UNAVAILABLE,
    REJECTED,
    REFERENCE_MISMATCH,
    PROVIDER_FAILED
}

sealed interface ProtectedModelDekProvisioningResult {
    data class Provisioned(
        val evidence: ProtectedModelDekProvisioningEvidence
    ) : ProtectedModelDekProvisioningResult

    data class Rejected(
        val reason: ProtectedModelDekProvisioningFailure
    ) : ProtectedModelDekProvisioningResult

    data class Failed(
        val reason: ProtectedModelDekProvisioningFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelDekProvisioningResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Trusted higher-layer seam for releasing one exact protected-model DEK.
 *
 * Provisioning != License Verification.
 * Provisioning != Package Selection.
 * Provisioning != Key Generation.
 * Provisioning != Network Protocol.
 *
 * A concrete provider may later adapt an authenticated key-release service, but Core deliberately
 * does not invent that transport here.
 */
fun interface ProtectedModelDekProvisioningPort {
    fun provision(
        request: ProtectedModelDekProvisioningRequest
    ): ProtectedModelDekProvisioningResult
}

sealed interface ProtectedModelDekProvisionAndRegisterResult {
    data class Registered(
        val binding: PersistentProtectedModelDekBinding
    ) : ProtectedModelDekProvisionAndRegisterResult

    data class ProvisioningRejected(
        val reason: ProtectedModelDekProvisioningFailure
    ) : ProtectedModelDekProvisionAndRegisterResult

    data class StoreRejected(
        val reason: PersistentProtectedModelDekFailure
    ) : ProtectedModelDekProvisionAndRegisterResult

    data class Failed(
        val reason: ProtectedModelDekProvisioningFailure? = null,
        val storeReason: PersistentProtectedModelDekFailure? = null,
        val throwable: Throwable? = null
    ) : ProtectedModelDekProvisionAndRegisterResult {
        override fun toString(): String =
            "Failed(reason=$reason, storeReason=$storeReason, " +
                "throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

internal fun interface ProtectedModelDekRegistrationPort {
    fun register(
        material: ProtectedModelDekMaterial
    ): PersistentProtectedModelDekRegistrationResult
}

/**
 * One-shot coordinator: exact provisioning evidence is checked before the material is immediately
 * wrapped and committed by [PersistentProtectedModelDekStore].
 */
object ProtectedModelDekProvisioningCoordinator {
    fun provisionAndRegister(
        provider: ProtectedModelDekProvisioningPort,
        store: PersistentProtectedModelDekStore,
        request: ProtectedModelDekProvisioningRequest,
        protectorDescriptor: ProtectedModelKeyProtectorDescriptor
    ): ProtectedModelDekProvisionAndRegisterResult =
        coordinate(
            request = request,
            provision = {
                try {
                    provider.provision(request)
                } catch (throwable: Throwable) {
                    ProtectedModelDekProvisioningResult.Failed(
                        ProtectedModelDekProvisioningFailure.PROVIDER_FAILED,
                        throwable
                    )
                }
            },
            registration = ProtectedModelDekRegistrationPort { material ->
                store.registerExact(
                    model = request.model,
                    reference = request.dek,
                    protectorDescriptor = protectorDescriptor,
                    material = material
                )
            }
        )

    internal fun coordinate(
        request: ProtectedModelDekProvisioningRequest,
        provision: () -> ProtectedModelDekProvisioningResult,
        registration: ProtectedModelDekRegistrationPort
    ): ProtectedModelDekProvisionAndRegisterResult {
        val evidence = when (val result = provision()) {
            is ProtectedModelDekProvisioningResult.Provisioned -> result.evidence

            is ProtectedModelDekProvisioningResult.Rejected ->
                return ProtectedModelDekProvisionAndRegisterResult.ProvisioningRejected(
                    result.reason
                )

            is ProtectedModelDekProvisioningResult.Failed ->
                return ProtectedModelDekProvisionAndRegisterResult.Failed(
                    reason = result.reason,
                    throwable = result.throwable
                )
        }

        if (evidence.model != request.model || evidence.dek != request.dek) {
            return ProtectedModelDekProvisionAndRegisterResult.ProvisioningRejected(
                ProtectedModelDekProvisioningFailure.REFERENCE_MISMATCH
            )
        }

        val registrationResult = try {
            registration.register(evidence.material)
        } catch (throwable: Throwable) {
            return ProtectedModelDekProvisionAndRegisterResult.Failed(
                throwable = throwable
            )
        }

        return when (val registered = registrationResult) {
            is PersistentProtectedModelDekRegistrationResult.Registered ->
                ProtectedModelDekProvisionAndRegisterResult.Registered(registered.binding)

            is PersistentProtectedModelDekRegistrationResult.Rejected ->
                ProtectedModelDekProvisionAndRegisterResult.StoreRejected(registered.reason)

            is PersistentProtectedModelDekRegistrationResult.Failed ->
                ProtectedModelDekProvisionAndRegisterResult.Failed(
                    storeReason = registered.reason,
                    throwable = registered.throwable
                )
        }
    }
}
