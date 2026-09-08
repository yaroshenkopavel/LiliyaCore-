package pro.liliya.android.cognitivestorage

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import pro.liliya.core.encryption.*

class AndroidCognitiveStorageFirstRunKeySetupContractTest {
    @Test
    fun restore_exact_existing_returns_same_reference_without_creation() {
        val reference = dek("existing", 4)
        val descriptor = descriptor("protector", 2, CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        val port = FakePort().apply {
            references += reference
            envelopes[reference] = envelope(reference, descriptor.reference)
            inspectedProtector = CognitiveEncryptionResult.Success(descriptor)
        }
        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(
            AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact(reference)
        )
        val ready = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Ready>(result)
        assertEquals(reference, ready.activeDek)
        assertEquals(1, port.inspectDekCalls)
        assertEquals(1, port.inspectProtectorCalls)
        assertEquals(0, port.createCalls)
        assertEquals(0, port.registerCalls)
    }

    @Test
    fun restore_missing_reference_fails_closed_without_creation() {
        val port = FakePort()
        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(
            AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact(dek("missing", 1))
        )
        val rejected = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Rejected>(result)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REFERENCE_MISSING, rejected.reason)
        assertEquals(0, port.createCalls)
        assertEquals(0, port.registerCalls)
    }

    @Test
    fun create_once_rejects_when_any_durable_dek_exists() {
        val port = FakePort().apply { references += dek("already", 3) }
        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(
            createRequest(CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        )
        val rejected = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Rejected>(result)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupFailure.EXISTING_DEK_PRESENT, rejected.reason)
        assertEquals(0, port.createCalls)
    }

    @Test
    fun create_once_passes_exact_request_and_returns_registered_reference() {
        val descriptor = descriptor("first-run-protector", 1, CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        val registered = dek("first-run-dek", 11)
        val port = FakePort().apply {
            createdProtector = CognitiveEncryptionResult.Success(descriptor)
            registration = PersistentCognitiveDekRegistrationResult.Registered(ownership(registered))
        }
        val request = createRequest(CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(request)
        val ready = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Ready>(result)
        assertEquals(registered, ready.activeDek)
        assertEquals(request.protectorRequest, port.lastCreateRequest)
        assertEquals(request.dekId, port.lastDekId)
        assertEquals(1, port.createCalls)
        assertEquals(1, port.registerCalls)
        assertEquals(0, port.retireCalls)
    }


    @Test
    fun create_once_rechecks_durable_state_after_protector_creation() {
        val descriptor = descriptor("first-run-protector", 1, CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        val port = FakePort().apply {
            createdProtector = CognitiveEncryptionResult.Success(descriptor)
            referenceToAddOnCreate = dek("concurrent", 9)
        }
        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(
            createRequest(CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        )
        val rejected = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Rejected>(result)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupFailure.EXISTING_DEK_PRESENT, rejected.reason)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupCleanup.RETIRED, rejected.cleanup)
        assertEquals(1, port.createCalls)
        assertEquals(0, port.registerCalls)
        assertEquals(1, port.retireCalls)
    }


    @Test
    fun create_once_is_serialized_across_setup_instances_sharing_one_durable_registry() {
        val descriptor = descriptor(
            "first-run-protector",
            1,
            CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
        )
        val registered = dek("first-run-dek", 21)
        val port = FakePort().apply {
            createdProtector = CognitiveEncryptionResult.Success(descriptor)
            registration = PersistentCognitiveDekRegistrationResult.Registered(ownership(registered))
            createDelayMillis = 75
            persistRegisteredReference = true
        }
        val first = AndroidCognitiveStorageFirstRunKeySetup(port)
        val second = AndroidCognitiveStorageFirstRunKeySetup(port)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { setup ->
                pool.submit<AndroidCognitiveStorageFirstRunKeySetupResult> {
                    start.await()
                    setup.prepare(createRequest(CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT))
                }
            }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is AndroidCognitiveStorageFirstRunKeySetupResult.Ready })
            val rejected = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Rejected>(
                results.single { it is AndroidCognitiveStorageFirstRunKeySetupResult.Rejected }
            )
            assertEquals(AndroidCognitiveStorageFirstRunKeySetupFailure.EXISTING_DEK_PRESENT, rejected.reason)
            assertEquals(1, port.createCalls)
            assertEquals(1, port.registerCalls)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun requested_strongbox_is_not_silently_downgraded_and_cleanup_is_bounded() {
        val descriptor = descriptor("first-run-protector", 1, CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        val port = FakePort().apply {
            createdProtector = CognitiveEncryptionResult.Success(descriptor)
            retireResult = CognitiveEncryptionResult.Failed(
                CognitiveEncryptionFailureCategory.CLEANUP_FAILED,
                IllegalStateException("PRIVATE-RETIRE-DETAIL")
            )
        }
        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(
            createRequest(CognitiveKeyProtectorSecurityLevel.STRONGBOX)
        )
        val rejected = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Rejected>(result)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupFailure.PROTECTOR_RESULT_INVALID, rejected.reason)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupCleanup.FAILED, rejected.cleanup)
        assertEquals(1, port.createCalls)
        assertEquals(0, port.registerCalls)
        assertEquals(1, port.retireCalls)
        assertTrue(!rejected.toString().contains("PRIVATE-RETIRE-DETAIL"))
    }

    @Test
    fun invalid_registered_ownership_fails_closed_without_destructive_protector_retirement() {
        val descriptor = descriptor(
            "first-run-protector",
            1,
            CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
        )
        val port = FakePort().apply {
            createdProtector = CognitiveEncryptionResult.Success(descriptor)
            registration = PersistentCognitiveDekRegistrationResult.Registered(
                ownership(dek("unexpected-dek", 12))
            )
        }

        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(
            createRequest(CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        )

        val rejected = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Rejected>(result)
        assertEquals(
            AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REGISTRATION_RESULT_INVALID,
            rejected.reason
        )
        assertEquals(null, rejected.cleanup)
        assertEquals(1, port.registerCalls)
        assertEquals(0, port.retireCalls)
    }

    @Test
    fun registration_failure_retires_exact_new_protector_once() {
        val descriptor = descriptor("first-run-protector", 1, CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        val port = FakePort().apply {
            createdProtector = CognitiveEncryptionResult.Success(descriptor)
            registration = PersistentCognitiveDekRegistrationResult.Failed(
                CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
                IllegalStateException("PRIVATE-REGISTER-DETAIL")
            )
        }
        val result = AndroidCognitiveStorageFirstRunKeySetup(port).prepare(
            createRequest(CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT)
        )
        val rejected = assertIs<AndroidCognitiveStorageFirstRunKeySetupResult.Rejected>(result)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REGISTRATION_FAILED, rejected.reason)
        assertEquals(CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED, rejected.category)
        assertEquals(AndroidCognitiveStorageFirstRunKeySetupCleanup.RETIRED, rejected.cleanup)
        assertEquals(1, port.registerCalls)
        assertEquals(1, port.retireCalls)
        assertTrue(!rejected.toString().contains("PRIVATE-REGISTER-DETAIL"))
    }

    private fun createRequest(level: CognitiveKeyProtectorSecurityLevel) =
        AndroidCognitiveStorageFirstRunKeySetupRequest.CreateOnce(
            dekId = CognitiveDekId("first-run-dek"),
            protectorRequest = CognitiveKeyProtectorCreationRequest(
                id = CognitiveKeyProtectorId("first-run-protector"),
                generation = CognitiveKeyProtectorGeneration(1),
                requestedSecurityLevel = level
            )
        )

    private fun dek(id: String, generation: Long) = CognitiveDekReference(
        CognitiveDekId(id), CognitiveDekGeneration(generation)
    )

    private fun descriptor(id: String, generation: Long, level: CognitiveKeyProtectorSecurityLevel) =
        CognitiveKeyProtectorDescriptor(
            reference = CognitiveKeyProtectorReference(
                id = CognitiveKeyProtectorId(id),
                generation = CognitiveKeyProtectorGeneration(generation),
                platformReference = CognitiveKeyProtectorPlatformReference("opaque-$id-$generation")
            ),
            securityLevel = level,
            purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE
        )

    private fun envelope(dek: CognitiveDekReference, protector: CognitiveKeyProtectorReference) =
        WrappedCognitiveDekEnvelope(
            version = CognitiveEnvelopeVersion(1),
            dek = dek,
            protector = protector,
            wrappingAlgorithm = CognitiveDekWrappingAlgorithm.AES_256_GCM,
            purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE,
            wrappedDek = ByteArray(32) { 1 },
            nonce = ByteArray(12) { 2 },
            authenticationTag = ByteArray(16) { 3 }
        )

    private fun ownership(reference: CognitiveDekReference) = object : PersistentCognitiveDekOwnership {
        override val reference: CognitiveDekReference = reference
        override fun retireIfUnused(dependencies: CognitiveCiphertextDependencyRegistry) =
            PersistentCognitiveDekMutationResult.Retired
    }

    private class FakePort : AndroidCognitiveStorageFirstRunKeySetupPort {
        override val coordinationLock: Any = Any()
        val references = mutableListOf<CognitiveDekReference>()
        val envelopes = mutableMapOf<CognitiveDekReference, WrappedCognitiveDekEnvelope>()
        var createdProtector: CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> =
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROVIDER_FAILED)
        var inspectedProtector: CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> =
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_MISSING)
        var registration: PersistentCognitiveDekRegistrationResult =
            PersistentCognitiveDekRegistrationResult.Rejected(CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT)
        var retireResult: CognitiveEncryptionResult<Unit> = CognitiveEncryptionResult.Success(Unit)
        var inspectDekCalls = 0
        var inspectProtectorCalls = 0
        var createCalls = 0
        var registerCalls = 0
        var retireCalls = 0
        var lastCreateRequest: CognitiveKeyProtectorCreationRequest? = null
        var lastDekId: CognitiveDekId? = null
        var referenceToAddOnCreate: CognitiveDekReference? = null
        var createDelayMillis: Long = 0
        var persistRegisteredReference = false

        override fun snapshotReferences() = references.toList()
        override fun inspectDek(reference: CognitiveDekReference): WrappedCognitiveDekEnvelope? {
            inspectDekCalls += 1
            return envelopes[reference]
        }
        override fun createProtector(request: CognitiveKeyProtectorCreationRequest): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> {
            createCalls += 1
            lastCreateRequest = request
            if (createDelayMillis > 0) Thread.sleep(createDelayMillis)
            referenceToAddOnCreate?.let { references += it }
            return createdProtector
        }
        override fun inspectProtector(reference: CognitiveKeyProtectorReference): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> {
            inspectProtectorCalls += 1
            return inspectedProtector
        }
        override fun registerDek(id: CognitiveDekId, descriptor: CognitiveKeyProtectorDescriptor): PersistentCognitiveDekRegistrationResult {
            registerCalls += 1
            lastDekId = id
            val result = registration
            if (persistRegisteredReference && result is PersistentCognitiveDekRegistrationResult.Registered) {
                if (result.ownership.reference !in references) references += result.ownership.reference
            }
            return result
        }
        override fun retireProtector(descriptor: CognitiveKeyProtectorDescriptor): CognitiveEncryptionResult<Unit> {
            retireCalls += 1
            return retireResult
        }
    }
}
