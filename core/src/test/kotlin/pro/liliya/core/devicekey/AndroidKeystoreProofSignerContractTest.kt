package pro.liliya.core.devicekey

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidKeystoreProofSignerContractTest {
    private val createdAt = Instant.parse("2026-08-30T21:15:00Z")
    private val algorithm = DeviceKeyAlgorithm("EC-P256-SHA256")
    private val platformReference = DeviceKeyPlatformReference("platform-ref-main")

    private class FakePlatform(
        var inspected: AndroidKeystoreKeyDescriptor,
        var signed: AndroidKeystoreSignatureDescriptor
    ) : AndroidKeystorePlatform {
        var signCalls = 0
        var signedExpected: AndroidKeystoreKeyDescriptor? = null

        override fun generate(
            request: DeviceKeyCreationRequest,
            createdAt: Instant
        ): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> =
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.UNSUPPORTED_PROFILE)

        override fun inspect(id: DeviceKeyId): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> =
            AndroidKeystorePlatformResult.Success(inspected)

        override fun signChallenge(
            expected: AndroidKeystoreKeyDescriptor,
            challenge: DeviceKeyChallenge
        ): AndroidKeystorePlatformResult<AndroidKeystoreSignatureDescriptor> {
            signCalls += 1
            signedExpected = expected
            return AndroidKeystorePlatformResult.Success(signed)
        }

        override fun delete(id: DeviceKeyId): AndroidKeystorePlatformResult<Unit> =
            AndroidKeystorePlatformResult.Success(Unit)
    }

    private fun state(
        id: String = "device-main",
        platformReference: DeviceKeyPlatformReference = this.platformReference
    ) = DeviceKeyState(
        id = DeviceKeyId(id),
        algorithm = algorithm,
        securityLevel = DeviceKeySecurityLevel.STRONGBOX,
        capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
        createdAt = createdAt,
        platformReference = platformReference
    )

    private fun descriptor(
        id: String = "device-main",
        createdAt: Instant = this.createdAt,
        platformReference: DeviceKeyPlatformReference = this.platformReference
    ) = AndroidKeystoreKeyDescriptor(
        id = DeviceKeyId(id),
        algorithm = algorithm,
        securityLevel = DeviceKeySecurityLevel.STRONGBOX,
        capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
        createdAt = createdAt,
        platformReference = platformReference
    )

    @Test
    fun signer_reinspects_exact_expected_state_before_platform_sign() {
        val expected = state()
        val platform = FakePlatform(
            inspected = descriptor(createdAt = createdAt.plusSeconds(1)),
            signed = AndroidKeystoreSignatureDescriptor(
                id = expected.id,
                platformReference = platformReference,
                signature = DeviceKeyProofSignature(byteArrayOf(1, 2, 3))
            )
        )
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)

        val result = adapter.signChallenge(
            expectedState = expected,
            challenge = DeviceKeyChallenge(byteArrayOf(9))
        )

        assertEquals(
            DeviceKeyFailureCategory.STALE_OWNERSHIP,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertEquals(0, platform.signCalls)
    }

    @Test
    fun signer_rejects_same_alias_replacement_with_new_platform_instance() {
        val expected = state()
        val platform = FakePlatform(
            inspected = descriptor(platformReference = DeviceKeyPlatformReference("platform-ref-replaced")),
            signed = AndroidKeystoreSignatureDescriptor(
                id = expected.id,
                platformReference = DeviceKeyPlatformReference("platform-ref-replaced"),
                signature = DeviceKeyProofSignature(byteArrayOf(3))
            )
        )
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)

        val result = adapter.signChallenge(expected, DeviceKeyChallenge(byteArrayOf(5)))

        assertEquals(
            DeviceKeyFailureCategory.STALE_OWNERSHIP,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertEquals(0, platform.signCalls)
    }

    @Test
    fun signer_passes_exact_descriptor_and_accepts_only_matching_signature_instance() {
        val expected = state()
        val signature = DeviceKeyProofSignature(byteArrayOf(4, 5, 6))
        val inspected = descriptor()
        val platform = FakePlatform(
            inspected = inspected,
            signed = AndroidKeystoreSignatureDescriptor(expected.id, platformReference, signature)
        )
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)

        val result = assertIs<DeviceKeyOperationResult.Success<DeviceKeyProofSignature>>(
            adapter.signChallenge(
                expectedState = expected,
                challenge = DeviceKeyChallenge(byteArrayOf(8, 8))
            )
        )

        assertEquals(signature, result.value)
        assertEquals(1, platform.signCalls)
        assertEquals(inspected, platform.signedExpected)
    }

    @Test
    fun signer_rejects_platform_signature_identity_or_instance_substitution() {
        val expected = state()
        val adapterWrongId = AndroidKeystoreDeviceKeyAdapter(
            FakePlatform(
                inspected = descriptor(),
                signed = AndroidKeystoreSignatureDescriptor(
                    id = DeviceKeyId("other-key"),
                    platformReference = platformReference,
                    signature = DeviceKeyProofSignature(byteArrayOf(7))
                )
            )
        )
        val adapterWrongInstance = AndroidKeystoreDeviceKeyAdapter(
            FakePlatform(
                inspected = descriptor(),
                signed = AndroidKeystoreSignatureDescriptor(
                    id = expected.id,
                    platformReference = DeviceKeyPlatformReference("platform-ref-other"),
                    signature = DeviceKeyProofSignature(byteArrayOf(8))
                )
            )
        )

        assertEquals(
            DeviceKeyFailureCategory.PLATFORM_REJECTED,
            assertIs<DeviceKeyOperationResult.Rejected>(
                adapterWrongId.signChallenge(expected, DeviceKeyChallenge(byteArrayOf(6)))
            ).category
        )
        assertEquals(
            DeviceKeyFailureCategory.PLATFORM_REJECTED,
            assertIs<DeviceKeyOperationResult.Rejected>(
                adapterWrongInstance.signChallenge(expected, DeviceKeyChallenge(byteArrayOf(7)))
            ).category
        )
    }
}
