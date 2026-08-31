package pro.liliya.core.devicekey

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceKeyAdapterContractTest {
    private val algorithm = DeviceKeyAlgorithm("EC-P256-SHA256")
    private val createdAt = Instant.parse("2026-08-30T20:15:00Z")

    private class FakeDeviceKeyAdapter(
        private val actualSecurityLevel: DeviceKeySecurityLevel,
        private val actualAlgorithm: DeviceKeyAlgorithm,
        private val actualCapabilities: Set<DeviceKeyCapability>
    ) : DeviceKeyAdapter {
        private val entries = mutableMapOf<DeviceKeyId, DeviceKeyState>()

        override fun create(
            request: DeviceKeyCreationRequest,
            createdAt: Instant
        ): DeviceKeyOperationResult<DeviceKeyState> {
            if (entries.containsKey(request.id)) {
                return DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.PLATFORM_REJECTED)
            }
            if (
                !DeviceKeyProfilePolicy.accepts(
                    requested = request.profile,
                    actualSecurityLevel = actualSecurityLevel,
                    actualAlgorithm = actualAlgorithm,
                    actualCapabilities = actualCapabilities
                )
            ) {
                return DeviceKeyOperationResult.Rejected(
                    DeviceKeyFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
                )
            }

            val state = DeviceKeyState(
                id = request.id,
                algorithm = actualAlgorithm,
                securityLevel = actualSecurityLevel,
                capabilities = actualCapabilities,
                createdAt = createdAt
            )
            entries[request.id] = state
            return DeviceKeyOperationResult.Success(state)
        }

        override fun resolve(id: DeviceKeyId): DeviceKeyOperationResult<DeviceKeyState> =
            entries[id]?.let { DeviceKeyOperationResult.Success(it) }
                ?: DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)

        override fun retire(id: DeviceKeyId): DeviceKeyOperationResult<Unit> =
            if (entries.remove(id) != null) {
                DeviceKeyOperationResult.Success(Unit)
            } else {
                DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)
            }

        fun rawState(id: DeviceKeyId): DeviceKeyState? = entries[id]
    }

    private fun profile(
        requestedSecurityLevel: DeviceKeySecurityLevel,
        capabilities: Set<DeviceKeyCapability> = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
    ) = DeviceKeyProfile(
        algorithm = algorithm,
        requestedSecurityLevel = requestedSecurityLevel,
        capabilities = capabilities
    )

    @Test
    fun mandatory_strongbox_cannot_silently_downgrade_to_tee() {
        val adapter = FakeDeviceKeyAdapter(
            actualSecurityLevel = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT,
            actualAlgorithm = algorithm,
            actualCapabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
        )
        val request = DeviceKeyCreationRequest(
            id = DeviceKeyId("device-main"),
            profile = profile(DeviceKeySecurityLevel.STRONGBOX)
        )

        val result = adapter.create(request, createdAt)

        assertEquals(
            DeviceKeyFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertNull(adapter.rawState(request.id))
    }

    @Test
    fun lower_security_requires_a_new_explicit_request() {
        val adapter = FakeDeviceKeyAdapter(
            actualSecurityLevel = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT,
            actualAlgorithm = algorithm,
            actualCapabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
        )
        val id = DeviceKeyId("device-main")

        val strongboxAttempt = adapter.create(
            DeviceKeyCreationRequest(id, profile(DeviceKeySecurityLevel.STRONGBOX)),
            createdAt
        )
        assertEquals(
            DeviceKeyFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
            assertIs<DeviceKeyOperationResult.Rejected>(strongboxAttempt).category
        )
        assertNull(adapter.rawState(id))

        val teeAttempt = assertIs<DeviceKeyOperationResult.Success<DeviceKeyState>>(
            adapter.create(
                DeviceKeyCreationRequest(id, profile(DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT)),
                createdAt
            )
        ).value
        assertEquals(DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT, teeAttempt.securityLevel)
        assertTrue(teeAttempt.securityLevel.hardwareBacked)
    }

    @Test
    fun unknown_actual_security_level_never_creates_ready_state() {
        val adapter = FakeDeviceKeyAdapter(
            actualSecurityLevel = DeviceKeySecurityLevel.UNKNOWN,
            actualAlgorithm = algorithm,
            actualCapabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
        )
        val request = DeviceKeyCreationRequest(
            id = DeviceKeyId("device-main"),
            profile = profile(DeviceKeySecurityLevel.SOFTWARE)
        )

        assertIs<DeviceKeyOperationResult.Rejected>(adapter.create(request, createdAt))
        assertNull(adapter.rawState(request.id))
    }

    @Test
    fun algorithm_and_capability_mismatch_fail_before_ready_publication() {
        val wrongAlgorithm = FakeDeviceKeyAdapter(
            actualSecurityLevel = DeviceKeySecurityLevel.STRONGBOX,
            actualAlgorithm = DeviceKeyAlgorithm("RSA-2048-SHA256"),
            actualCapabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
        )
        val missingCapability = FakeDeviceKeyAdapter(
            actualSecurityLevel = DeviceKeySecurityLevel.STRONGBOX,
            actualAlgorithm = algorithm,
            actualCapabilities = emptySet()
        )
        val id1 = DeviceKeyId("wrong-algorithm")
        val id2 = DeviceKeyId("missing-capability")

        assertIs<DeviceKeyOperationResult.Rejected>(
            wrongAlgorithm.create(
                DeviceKeyCreationRequest(id1, profile(DeviceKeySecurityLevel.STRONGBOX)),
                createdAt
            )
        )
        assertIs<DeviceKeyOperationResult.Rejected>(
            missingCapability.create(
                DeviceKeyCreationRequest(id2, profile(DeviceKeySecurityLevel.STRONGBOX)),
                createdAt
            )
        )
        assertNull(wrongAlgorithm.rawState(id1))
        assertNull(missingCapability.rawState(id2))
    }

    @Test
    fun lifecycle_is_deterministic_and_missing_key_fails_closed() {
        val adapter = FakeDeviceKeyAdapter(
            actualSecurityLevel = DeviceKeySecurityLevel.STRONGBOX,
            actualAlgorithm = algorithm,
            actualCapabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
        )
        val id = DeviceKeyId("device-main")
        val request = DeviceKeyCreationRequest(id, profile(DeviceKeySecurityLevel.STRONGBOX))

        val created = assertIs<DeviceKeyOperationResult.Success<DeviceKeyState>>(
            adapter.create(request, createdAt)
        ).value
        val resolved = assertIs<DeviceKeyOperationResult.Success<DeviceKeyState>>(
            adapter.resolve(id)
        ).value
        assertEquals(created, resolved)
        assertIs<DeviceKeyOperationResult.Success<Unit>>(adapter.retire(id))
        assertEquals(
            DeviceKeyFailureCategory.KEY_MISSING,
            assertIs<DeviceKeyOperationResult.Rejected>(adapter.resolve(id)).category
        )
        assertEquals(
            DeviceKeyFailureCategory.KEY_MISSING,
            assertIs<DeviceKeyOperationResult.Rejected>(adapter.retire(id)).category
        )
    }

    @Test
    fun stronger_security_satisfies_software_minimum_without_being_misreported() {
        val accepted = DeviceKeyProfilePolicy.accepts(
            requested = profile(DeviceKeySecurityLevel.SOFTWARE),
            actualSecurityLevel = DeviceKeySecurityLevel.STRONGBOX,
            actualAlgorithm = algorithm,
            actualCapabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
        )

        assertTrue(accepted)
        assertFalse(DeviceKeySecurityLevel.SOFTWARE.hardwareBacked)
        assertTrue(DeviceKeySecurityLevel.STRONGBOX.hardwareBacked)
    }
}
