package pro.liliya.android.devicekey

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.devicekey.AndroidKeystoreDeviceKeyAdapter
import pro.liliya.core.devicekey.DeviceKeyAlgorithm
import pro.liliya.core.devicekey.DeviceKeyCapability
import pro.liliya.core.devicekey.DeviceKeyChallenge
import pro.liliya.core.devicekey.DeviceKeyCreationRequest
import pro.liliya.core.devicekey.DeviceKeyFailureCategory
import pro.liliya.core.devicekey.DeviceKeyId
import pro.liliya.core.devicekey.DeviceKeyOperationResult
import pro.liliya.core.devicekey.DeviceKeyProfile
import pro.liliya.core.devicekey.DeviceKeyProofSignature
import pro.liliya.core.devicekey.DeviceKeySecurityLevel

@RunWith(AndroidJUnit4::class)
class AndroidSystemKeystorePlatformInstrumentedTest {
    private val algorithm = DeviceKeyAlgorithm("EC-P256-SHA256")

    @Test
    fun create_resolve_sign_and_retire_use_real_android_keystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = AndroidSystemKeystorePlatform(context)
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val id = uniqueId("lifecycle")
        val createdAt = Instant.parse("2026-08-31T00:00:00Z")

        try {
            val state = assertIs<DeviceKeyOperationResult.Success<pro.liliya.core.devicekey.DeviceKeyState>>(
                adapter.create(
                    DeviceKeyCreationRequest(
                        id = id,
                        profile = profile(DeviceKeySecurityLevel.SOFTWARE)
                    ),
                    createdAt
                )
            ).value

            assertEquals(id, state.id)
            assertEquals(algorithm, state.algorithm)
            assertTrue(state.securityLevel != DeviceKeySecurityLevel.UNKNOWN)
            assertTrue(DeviceKeyCapability.SIGN_CHALLENGE in state.capabilities)
            assertNotNull(state.platformReference)
            assertFalse(platform.aliasForTesting(id).contains(id.value))

            val resolved = assertIs<DeviceKeyOperationResult.Success<pro.liliya.core.devicekey.DeviceKeyState>>(
                adapter.resolve(id)
            ).value
            assertEquals(state, resolved)

            val signature = assertIs<DeviceKeyOperationResult.Success<DeviceKeyProofSignature>>(
                adapter.signChallenge(
                    expectedState = state,
                    challenge = DeviceKeyChallenge("device-key-proof".encodeToByteArray())
                )
            ).value
            assertTrue(signature.copyBytes().isNotEmpty())

            assertIs<DeviceKeyOperationResult.Success<Unit>>(adapter.retire(id))
            assertEquals(
                DeviceKeyFailureCategory.KEY_MISSING,
                assertIs<DeviceKeyOperationResult.Rejected>(adapter.resolve(id)).category
            )
        } finally {
            platform.delete(id)
        }
    }

    @Test
    fun reused_logical_id_gets_a_new_platform_instance_reference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = AndroidSystemKeystorePlatform(context)
        val adapter = AndroidKeystoreDeviceKeyAdapter(platform)
        val id = uniqueId("aba")

        try {
            val first = assertIs<DeviceKeyOperationResult.Success<pro.liliya.core.devicekey.DeviceKeyState>>(
                adapter.create(
                    DeviceKeyCreationRequest(id, profile(DeviceKeySecurityLevel.SOFTWARE)),
                    Instant.parse("2026-08-31T00:01:00Z")
                )
            ).value
            val firstReference = assertNotNull(first.platformReference)

            assertIs<DeviceKeyOperationResult.Success<Unit>>(adapter.retire(id))

            val second = assertIs<DeviceKeyOperationResult.Success<pro.liliya.core.devicekey.DeviceKeyState>>(
                adapter.create(
                    DeviceKeyCreationRequest(id, profile(DeviceKeySecurityLevel.SOFTWARE)),
                    Instant.parse("2026-08-31T00:02:00Z")
                )
            ).value
            val secondReference = assertNotNull(second.platformReference)

            assertNotEquals(firstReference, secondReference)
        } finally {
            platform.delete(id)
        }
    }

    private fun profile(level: DeviceKeySecurityLevel): DeviceKeyProfile = DeviceKeyProfile(
        algorithm = algorithm,
        requestedSecurityLevel = level,
        capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
    )

    private fun uniqueId(prefix: String): DeviceKeyId =
        DeviceKeyId("instrumented-$prefix-${UUID.randomUUID()}")
}
