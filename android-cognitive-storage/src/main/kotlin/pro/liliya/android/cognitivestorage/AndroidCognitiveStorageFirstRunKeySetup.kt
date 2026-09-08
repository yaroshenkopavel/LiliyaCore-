package pro.liliya.android.cognitivestorage

import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveKeyProtectorCreationRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor
import pro.liliya.core.encryption.CognitiveKeyProtectorReference
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel
import pro.liliya.core.encryption.CognitiveKeyPurpose
import pro.liliya.core.encryption.PersistentCognitiveDekRegistrationResult
import pro.liliya.core.encryption.WrappedCognitiveDekEnvelope

sealed interface AndroidCognitiveStorageFirstRunKeySetupRequest {
    data class RestoreExact(
        val reference: CognitiveDekReference
    ) : AndroidCognitiveStorageFirstRunKeySetupRequest

    data class CreateOnce(
        val dekId: CognitiveDekId,
        val protectorRequest: CognitiveKeyProtectorCreationRequest
    ) : AndroidCognitiveStorageFirstRunKeySetupRequest
}

enum class AndroidCognitiveStorageFirstRunKeySetupFailure {
    EXISTING_DEK_PRESENT,
    DEK_REFERENCE_MISSING,
    DEK_REFERENCE_INVALID,
    PROTECTOR_REJECTED,
    PROTECTOR_FAILED,
    PROTECTOR_RESULT_INVALID,
    DEK_REGISTRATION_REJECTED,
    DEK_REGISTRATION_FAILED,
    DEK_REGISTRATION_RESULT_INVALID,
    INTERNAL_FAILURE
}

enum class AndroidCognitiveStorageFirstRunKeySetupCleanup {
    RETIRED,
    REJECTED,
    FAILED
}

sealed interface AndroidCognitiveStorageFirstRunKeySetupResult {
    data class Ready(
        val activeDek: CognitiveDekReference
    ) : AndroidCognitiveStorageFirstRunKeySetupResult

    data class Rejected(
        val reason: AndroidCognitiveStorageFirstRunKeySetupFailure,
        val category: CognitiveEncryptionFailureCategory? = null,
        val cleanup: AndroidCognitiveStorageFirstRunKeySetupCleanup? = null
    ) : AndroidCognitiveStorageFirstRunKeySetupResult
}

internal interface AndroidCognitiveStorageFirstRunKeySetupPort {
    fun snapshotReferences(): List<CognitiveDekReference>
    fun inspectDek(reference: CognitiveDekReference): WrappedCognitiveDekEnvelope?
    fun createProtector(
        request: CognitiveKeyProtectorCreationRequest
    ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor>
    fun inspectProtector(
        reference: CognitiveKeyProtectorReference
    ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor>
    fun registerDek(
        id: CognitiveDekId,
        descriptor: CognitiveKeyProtectorDescriptor
    ): PersistentCognitiveDekRegistrationResult
    fun retireProtector(
        descriptor: CognitiveKeyProtectorDescriptor
    ): CognitiveEncryptionResult<Unit>
}

/**
 * Explicit first-run/restore policy over the already-authoritative Android cognitive-storage
 * key protector and durable wrapped-DEK registry.
 *
 * Key Setup != Key Selection Heuristic.
 * Key Setup != Rotation.
 * Key Setup != Recovery.
 * Requested Security Level != Silent Downgrade.
 */
class AndroidCognitiveStorageFirstRunKeySetup internal constructor(
    private val port: AndroidCognitiveStorageFirstRunKeySetupPort
) {
    constructor(storage: AndroidCognitiveStorageAssembly) : this(
        object : AndroidCognitiveStorageFirstRunKeySetupPort {
            override fun snapshotReferences(): List<CognitiveDekReference> =
                storage.dekStore.snapshotReferences()

            override fun inspectDek(
                reference: CognitiveDekReference
            ): WrappedCognitiveDekEnvelope? = storage.dekStore.inspect(reference)

            override fun createProtector(
                request: CognitiveKeyProtectorCreationRequest
            ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> =
                storage.keyProtector.create(request)

            override fun inspectProtector(
                reference: CognitiveKeyProtectorReference
            ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> =
                storage.keyProtector.inspect(reference)

            override fun registerDek(
                id: CognitiveDekId,
                descriptor: CognitiveKeyProtectorDescriptor
            ): PersistentCognitiveDekRegistrationResult =
                storage.dekStore.register(id, descriptor)

            override fun retireProtector(
                descriptor: CognitiveKeyProtectorDescriptor
            ): CognitiveEncryptionResult<Unit> = storage.keyProtector.retire(descriptor)
        }
    )

    @Synchronized
    fun prepare(
        request: AndroidCognitiveStorageFirstRunKeySetupRequest
    ): AndroidCognitiveStorageFirstRunKeySetupResult = when (request) {
        is AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact -> restore(request)
        is AndroidCognitiveStorageFirstRunKeySetupRequest.CreateOnce -> createOnce(request)
    }

    private fun restore(
        request: AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact
    ): AndroidCognitiveStorageFirstRunKeySetupResult {
        val references = try {
            port.snapshotReferences()
        } catch (_: Exception) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.INTERNAL_FAILURE)
        }
        if (request.reference !in references) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REFERENCE_MISSING)
        }

        val envelope = try {
            port.inspectDek(request.reference)
        } catch (_: Exception) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.INTERNAL_FAILURE)
        } ?: return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REFERENCE_INVALID)

        if (envelope.dek != request.reference || envelope.purpose != CognitiveKeyPurpose.COGNITIVE_STORAGE) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REFERENCE_INVALID)
        }

        return when (val inspected = try {
            port.inspectProtector(envelope.protector)
        } catch (_: Exception) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.INTERNAL_FAILURE)
        }) {
            is CognitiveEncryptionResult.Success -> {
                if (
                    inspected.value.reference != envelope.protector ||
                    inspected.value.purpose != CognitiveKeyPurpose.COGNITIVE_STORAGE
                ) {
                    rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.PROTECTOR_RESULT_INVALID)
                } else {
                    AndroidCognitiveStorageFirstRunKeySetupResult.Ready(request.reference)
                }
            }
            is CognitiveEncryptionResult.Rejected -> rejected(
                AndroidCognitiveStorageFirstRunKeySetupFailure.PROTECTOR_REJECTED,
                inspected.category
            )
            is CognitiveEncryptionResult.Failed -> rejected(
                AndroidCognitiveStorageFirstRunKeySetupFailure.PROTECTOR_FAILED,
                inspected.category
            )
        }
    }

    private fun createOnce(
        request: AndroidCognitiveStorageFirstRunKeySetupRequest.CreateOnce
    ): AndroidCognitiveStorageFirstRunKeySetupResult {
        val references = try {
            port.snapshotReferences()
        } catch (_: Exception) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.INTERNAL_FAILURE)
        }
        if (references.isNotEmpty()) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.EXISTING_DEK_PRESENT)
        }

        val descriptor = when (val created = try {
            port.createProtector(request.protectorRequest)
        } catch (_: Exception) {
            return rejected(AndroidCognitiveStorageFirstRunKeySetupFailure.INTERNAL_FAILURE)
        }) {
            is CognitiveEncryptionResult.Success -> created.value
            is CognitiveEncryptionResult.Rejected -> return rejected(
                AndroidCognitiveStorageFirstRunKeySetupFailure.PROTECTOR_REJECTED,
                created.category
            )
            is CognitiveEncryptionResult.Failed -> return rejected(
                AndroidCognitiveStorageFirstRunKeySetupFailure.PROTECTOR_FAILED,
                created.category
            )
        }

        if (!descriptorMatchesRequest(descriptor, request.protectorRequest)) {
            return rejectedWithCleanup(
                reason = AndroidCognitiveStorageFirstRunKeySetupFailure.PROTECTOR_RESULT_INVALID,
                descriptor = descriptor
            )
        }

        val referencesAfterProtectorCreate = try {
            port.snapshotReferences()
        } catch (_: Exception) {
            return rejectedWithCleanup(
                reason = AndroidCognitiveStorageFirstRunKeySetupFailure.INTERNAL_FAILURE,
                descriptor = descriptor
            )
        }
        if (referencesAfterProtectorCreate.isNotEmpty()) {
            return rejectedWithCleanup(
                reason = AndroidCognitiveStorageFirstRunKeySetupFailure.EXISTING_DEK_PRESENT,
                descriptor = descriptor
            )
        }

        return when (val registered = try {
            port.registerDek(request.dekId, descriptor)
        } catch (_: Exception) {
            return rejectedWithCleanup(
                reason = AndroidCognitiveStorageFirstRunKeySetupFailure.INTERNAL_FAILURE,
                descriptor = descriptor
            )
        }) {
            is PersistentCognitiveDekRegistrationResult.Registered -> {
                if (registered.ownership.reference.id != request.dekId) {
                    // Registration already reports durable ownership. Retiring the protector here
                    // could make that durable DEK unreadable, so fail closed without destructive
                    // compensation.
                    rejected(
                        AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REGISTRATION_RESULT_INVALID
                    )
                } else {
                    AndroidCognitiveStorageFirstRunKeySetupResult.Ready(
                        registered.ownership.reference
                    )
                }
            }
            is PersistentCognitiveDekRegistrationResult.Rejected -> rejectedWithCleanup(
                reason = AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REGISTRATION_REJECTED,
                category = registered.category,
                descriptor = descriptor
            )
            is PersistentCognitiveDekRegistrationResult.Failed -> rejectedWithCleanup(
                reason = AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REGISTRATION_FAILED,
                category = registered.category,
                descriptor = descriptor
            )
        }
    }

    private fun descriptorMatchesRequest(
        descriptor: CognitiveKeyProtectorDescriptor,
        request: CognitiveKeyProtectorCreationRequest
    ): Boolean {
        if (
            descriptor.reference.id != request.id ||
            descriptor.reference.generation != request.generation ||
            descriptor.purpose != CognitiveKeyPurpose.COGNITIVE_STORAGE ||
            descriptor.reference.platformReference == null
        ) {
            return false
        }
        return when (request.requestedSecurityLevel) {
            CognitiveKeyProtectorSecurityLevel.STRONGBOX ->
                descriptor.securityLevel == CognitiveKeyProtectorSecurityLevel.STRONGBOX
            CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT ->
                descriptor.securityLevel == CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT ||
                    descriptor.securityLevel == CognitiveKeyProtectorSecurityLevel.STRONGBOX
            CognitiveKeyProtectorSecurityLevel.SOFTWARE ->
                descriptor.securityLevel != CognitiveKeyProtectorSecurityLevel.UNKNOWN
            CognitiveKeyProtectorSecurityLevel.UNKNOWN -> false
        }
    }

    private fun rejectedWithCleanup(
        reason: AndroidCognitiveStorageFirstRunKeySetupFailure,
        descriptor: CognitiveKeyProtectorDescriptor,
        category: CognitiveEncryptionFailureCategory? = null
    ): AndroidCognitiveStorageFirstRunKeySetupResult.Rejected {
        val cleanup = try {
            when (port.retireProtector(descriptor)) {
                is CognitiveEncryptionResult.Success -> AndroidCognitiveStorageFirstRunKeySetupCleanup.RETIRED
                is CognitiveEncryptionResult.Rejected -> AndroidCognitiveStorageFirstRunKeySetupCleanup.REJECTED
                is CognitiveEncryptionResult.Failed -> AndroidCognitiveStorageFirstRunKeySetupCleanup.FAILED
            }
        } catch (_: Exception) {
            AndroidCognitiveStorageFirstRunKeySetupCleanup.FAILED
        }
        return rejected(reason, category, cleanup)
    }

    private fun rejected(
        reason: AndroidCognitiveStorageFirstRunKeySetupFailure,
        category: CognitiveEncryptionFailureCategory? = null,
        cleanup: AndroidCognitiveStorageFirstRunKeySetupCleanup? = null
    ) = AndroidCognitiveStorageFirstRunKeySetupResult.Rejected(
        reason = reason,
        category = category,
        cleanup = cleanup
    )
}
