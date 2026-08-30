package pro.liliya.core.devicekey

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidKeystoreProofSignerContractTest {
    private val createdAt = Instant.parse("2026-08-30T21:15:00Z")
    private val algorithm = DeviceKeyAlgorithm("EC-P256-SHA256")

    private class FakePlatform(
        var inspected: AndroidKeystoreKeyDescriptor,
        var signed: AndroidKeystoreSignatureDescriptor
    ) : AndroidKeystorePlatform {
        var signCalls = 0
        var signedId: DeviceKeyId? = null

        override fun generate(
            request: DeviceKeyCreationRequest,
            createdAt: Instant
        ): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> =
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.UNSUPPORTED_PROFILE)

        override fun inspect(id: DeviceKeyId): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> =
            AndroidKeystorePlatformResult.Success(inspected)

        override fun signChallenge(
            id: DeviceKeyId,
            challenge: DeviceKeyChallenge
        ): AndroidKeystorePlatformResult<AndroidKeystoreSignatureDescriptor> {
            signCalls += 1
            signedId = id
            return AndroidKeystorePlatformResult.Success(signed)
        }

        override fun delete(id: DeviceKeyId): AndroidKeystorePlatformResult<Unit> =
            AndroidKeystorePlatformResult.Success(Unit)
    }

    private fun state(id: String = "device-main") = DeviceKeyState(
        id = DeviceKeyId(id),
        algorithm = algorithm,
        securityLevel = DeviceKeySecurityLevel.STRONGBOX,
        capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
        createdAt = createdAt
    )

    private fun descriptor(id: String = "device-main", createdAt: Instant = this.createdAt) =
        AndroidKeystoreKeyDescriptor(
            id = DeviceKeyId(id),
            algorithm = algorithm,
            securityLevel = DeviceKeySecurityLevel.STRONGBOX,
            capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
            createdAt = createdAt
        )

    @Test
    fun signer_reinspects_exact_expected_state_before_platform_sign() {
        val expected = state()
        val platform = FakePlatform(
            inspected = descriptor(createdAt = createdAt.plusSeconds(1)),
            signed = AndroidKeystoreSignatureDescriptor(
                id = expected.id,
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
    fun signer_uses_exact_id_and_accepts_only_matching_signature_descriptor() {
        val expected = state()
        val signature = DeviceKeyProofSignature(byteArrayOf(4, 5, 6))
        val platform = FakePlatform(
            inspected = descriptor(),
            signed = AndroidKeystoreSignatureDescriptor(expected.id, signature)
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
        assertEquals(expected.id, platform.signedId)
    }

    @Test
    fun signer_rejects_platform_signature_identity_substitution() {
        val expected = state()
        val platform = FakePlatform(
            inspected = descriptor(),
            signed = AndroidKeystoreSignatureDescriptor(
                id = DeviceKeyId("other-key"),
                signature = DeviceKeyProofSignature(byteArrayOf(7))
            )
        )
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)

        val result = adapter.signChallenge(
            expectedState = expected,
            challenge = DeviceKeyChallenge(byteArrayOf(6))
        )

        assertEquals(
            DeviceKeyFailureCategory.PLATFORM_REJECTED,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertEquals(1, platform.signCalls)
    }
}
