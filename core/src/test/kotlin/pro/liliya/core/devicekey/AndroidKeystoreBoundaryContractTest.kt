package pro.liliya.core.devicekey

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidKeystoreBoundaryContractTest {
    private val algorithm = DeviceKeyAlgorithm("EC-P256-SHA256")
    private val createdAt = Instant.parse("2026-08-30T21:00:00Z")

    private class FakePlatform : AndroidKeystorePlatform {
        var generatedDescriptor: AndroidKeystoreKeyDescriptor? = null
        var inspectedDescriptor: AndroidKeystoreKeyDescriptor? = null
        var generateResult: AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor>? = null
        var inspectResult: AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor>? = null
        var deleteResult: AndroidKeystorePlatformResult<Unit> = AndroidKeystorePlatformResult.Success(Unit)
        val deletedIds = mutableListOf<DeviceKeyId>()

        override fun generate(
            request: DeviceKeyCreationRequest,
            createdAt: Instant
        ): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> =
            generateResult ?: generatedDescriptor?.let { AndroidKeystorePlatformResult.Success(it) }
                ?: AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.PLATFORM_REJECTED)

        override fun inspect(id: DeviceKeyId): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> =
            inspectResult ?: inspectedDescriptor?.let { AndroidKeystorePlatformResult.Success(it) }
                ?: AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)

        override fun signChallenge(
            expected: AndroidKeystoreKeyDescriptor,
            challenge: DeviceKeyChallenge
        ): AndroidKeystorePlatformResult<AndroidKeystoreSignatureDescriptor> =
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.UNSUPPORTED_PROFILE)

        override fun delete(id: DeviceKeyId): AndroidKeystorePlatformResult<Unit> {
            deletedIds += id
            return deleteResult
        }
    }

    private fun profile(
        level: DeviceKeySecurityLevel = DeviceKeySecurityLevel.STRONGBOX,
        fallback: DeviceKeyFallbackPolicy = DeviceKeyFallbackPolicy.FAIL_CLOSED
    ) = DeviceKeyProfile(
        algorithm = algorithm,
        requestedSecurityLevel = level,
        fallbackPolicy = fallback,
        capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
    )

    private fun descriptor(
        id: String = "device-main",
        level: DeviceKeySecurityLevel = DeviceKeySecurityLevel.STRONGBOX,
        algorithm: DeviceKeyAlgorithm = this.algorithm,
        capabilities: Set<DeviceKeyCapability> = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
        platformReference: DeviceKeyPlatformReference = DeviceKeyPlatformReference("platform-ref-main")
    ) = AndroidKeystoreKeyDescriptor(
        id = DeviceKeyId(id),
        algorithm = algorithm,
        securityLevel = level,
        capabilities = capabilities,
        createdAt = createdAt,
        platformReference = platformReference
    )

    @Test
    fun create_publishes_only_after_exact_post_generation_inspection() {
        val platform = FakePlatform().apply {
            generatedDescriptor = descriptor()
            inspectedDescriptor = descriptor()
        }
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val request = DeviceKeyCreationRequest(DeviceKeyId("device-main"), profile())

        val state = assertIs<DeviceKeyOperationResult.Success<DeviceKeyState>>(
            adapter.create(request, createdAt)
        ).value

        assertEquals(request.id, state.id)
        assertEquals(DeviceKeySecurityLevel.STRONGBOX, state.securityLevel)
        assertEquals(DeviceKeyPlatformReference("platform-ref-main"), state.platformReference)
        assertTrue(platform.deletedIds.isEmpty())
    }

    @Test
    fun post_generation_security_mismatch_fails_closed_and_cleans_exact_requested_id() {
        val platform = FakePlatform().apply {
            generatedDescriptor = descriptor(level = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT)
            inspectedDescriptor = descriptor(level = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT)
        }
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val request = DeviceKeyCreationRequest(DeviceKeyId("device-main"), profile())

        val result = adapter.create(request, createdAt)

        assertEquals(
            DeviceKeyFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertEquals(listOf(request.id), platform.deletedIds)
    }

    @Test
    fun replaced_platform_instance_between_generate_and_inspect_fails_closed() {
        val platform = FakePlatform().apply {
            generatedDescriptor = descriptor(platformReference = DeviceKeyPlatformReference("platform-ref-old"))
            inspectedDescriptor = descriptor(platformReference = DeviceKeyPlatformReference("platform-ref-new"))
        }
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val request = DeviceKeyCreationRequest(DeviceKeyId("device-main"), profile())

        assertEquals(
            DeviceKeyFailureCategory.PLATFORM_REJECTED,
            assertIs<DeviceKeyOperationResult.Rejected>(adapter.create(request, createdAt)).category
        )
        assertEquals(listOf(request.id), platform.deletedIds)
    }

    @Test
    fun corrupted_generated_identity_cannot_redirect_cleanup_to_another_key() {
        val platform = FakePlatform().apply {
            generatedDescriptor = descriptor(id = "other-key")
        }
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val request = DeviceKeyCreationRequest(DeviceKeyId("device-main"), profile())

        assertIs<DeviceKeyOperationResult.Rejected>(adapter.create(request, createdAt))

        assertEquals(listOf(request.id), platform.deletedIds)
        assertFalse(DeviceKeyId("other-key") in platform.deletedIds)
    }

    @Test
    fun cleanup_failure_is_explicit_and_never_publishes_ready_state() {
        val platform = FakePlatform().apply {
            generatedDescriptor = descriptor(level = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT)
            inspectedDescriptor = descriptor(level = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT)
            deleteResult = AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_INVALIDATED)
        }
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val request = DeviceKeyCreationRequest(DeviceKeyId("device-main"), profile())

        val result = adapter.create(request, createdAt)

        assertEquals(
            DeviceKeyFailureCategory.CLEANUP_FAILED,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertEquals(listOf(request.id), platform.deletedIds)
    }

    @Test
    fun resolve_rejects_unknown_or_wrong_identity_without_claiming_hardware_state() {
        val platform = FakePlatform()
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val id = DeviceKeyId("device-main")

        platform.inspectedDescriptor = descriptor(level = DeviceKeySecurityLevel.UNKNOWN)
        assertIs<DeviceKeyOperationResult.Rejected>(adapter.resolve(id))

        platform.inspectedDescriptor = descriptor(id = "other-key")
        assertIs<DeviceKeyOperationResult.Rejected>(adapter.resolve(id))
        assertFalse(DeviceKeySecurityLevel.UNKNOWN.hardwareBacked)
    }

    @Test
    fun explicit_tee_fallback_preserves_actual_inspected_level() {
        val platform = FakePlatform().apply {
            generatedDescriptor = descriptor(level = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT)
            inspectedDescriptor = descriptor(level = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT)
        }
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val request = DeviceKeyCreationRequest(
            DeviceKeyId("device-main"),
            profile(
                level = DeviceKeySecurityLevel.STRONGBOX,
                fallback = DeviceKeyFallbackPolicy.ALLOW_TRUSTED_ENVIRONMENT
            )
        )

        val state = assertIs<DeviceKeyOperationResult.Success<DeviceKeyState>>(
            adapter.create(request, createdAt)
        ).value

        assertEquals(DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT, state.securityLevel)
        assertTrue(state.securityLevel.hardwareBacked)
    }

    @Test
    fun platform_failure_rendering_does_not_expose_exception_message() {
        val secret = "ANDROID-KEYSTORE-PRIVATE-DETAIL"
        val failure = AndroidKeystorePlatformResult.Failed(
            category = DeviceKeyFailureCategory.PLATFORM_REJECTED,
            throwable = IllegalStateException("platform leaked $secret")
        )
        val rendered = failure.toString()

        assertFalse(secret in rendered)
        assertFalse("platform leaked" in rendered)
        assertTrue(IllegalStateException::class.java.name in rendered)
    }

    @Test
    fun retire_propagates_exact_platform_outcome() {
        val platform = FakePlatform()
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val id = DeviceKeyId("device-main")

        assertIs<DeviceKeyOperationResult.Success<Unit>>(adapter.retire(id))
        assertEquals(listOf(id), platform.deletedIds)

        platform.deleteResult = AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)
        assertEquals(
            DeviceKeyFailureCategory.KEY_MISSING,
            assertIs<DeviceKeyOperationResult.Rejected>(adapter.retire(id)).category
        )
    }
}
