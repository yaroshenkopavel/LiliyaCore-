package pro.liliya.android.devicekey

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekMaterial
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveKeyProtectorCreationRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorGeneration
import pro.liliya.core.encryption.CognitiveKeyProtectorId
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel

@RunWith(AndroidJUnit4::class)
class AndroidCognitiveKeyProtectorInstrumentedTest {
    @Test
    fun real_keystore_protector_wraps_unwraps_and_retires_exact_dek() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val protector = AndroidCognitiveKeyProtector(context)
        val request = request("roundtrip", 1)
        val descriptor = assertIs<CognitiveEncryptionResult.Success<pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor>>(
            protector.create(request)
        ).value

        try {
            assertNotNull(descriptor.reference.platformReference)
            assertFalse(protector.aliasForTesting(descriptor.reference).contains(request.id.value))

            val dek = CognitiveDekReference(CognitiveDekId("dek-${UUID.randomUUID()}"), CognitiveDekGeneration(1))
            val materialBytes = ByteArray(32) { index -> (index * 7 + 3).toByte() }
            val material = CognitiveDekMaterial(materialBytes)
            val wrapped = assertIs<CognitiveEncryptionResult.Success<pro.liliya.core.encryption.WrappedCognitiveDekEnvelope>>(
                protector.wrap(descriptor, dek, material)
            ).value

            assertEquals(dek, wrapped.dek)
            assertEquals(descriptor.reference, wrapped.protector)
            assertFalse(wrapped.copyWrappedDek().contentEquals(materialBytes))

            val opened = assertIs<CognitiveEncryptionResult.Success<CognitiveDekMaterial>>(
                protector.unwrap(descriptor, wrapped)
            ).value
            assertContentEquals(materialBytes, opened.copyBytes())

            assertIs<CognitiveEncryptionResult.Success<Unit>>(protector.retire(descriptor))
            assertEquals(
                CognitiveEncryptionFailureCategory.PROTECTOR_MISSING,
                assertIs<CognitiveEncryptionResult.Rejected>(
                    protector.inspect(descriptor.reference)
                ).category
            )
        } finally {
            protector.retire(descriptor)
        }
    }

    @Test
    fun replacement_generation_gets_distinct_platform_reference_and_stale_descriptor_cannot_operate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val protector = AndroidCognitiveKeyProtector(context)
        val id = CognitiveKeyProtectorId("instrumented-aba-${UUID.randomUUID()}")
        val first = assertIs<CognitiveEncryptionResult.Success<pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor>>(
            protector.create(
                CognitiveKeyProtectorCreationRequest(
                    id,
                    CognitiveKeyProtectorGeneration(1),
                    CognitiveKeyProtectorSecurityLevel.SOFTWARE
                )
            )
        ).value

        try {
            assertIs<CognitiveEncryptionResult.Success<Unit>>(protector.retire(first))
            val second = assertIs<CognitiveEncryptionResult.Success<pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor>>(
                protector.create(
                    CognitiveKeyProtectorCreationRequest(
                        id,
                        CognitiveKeyProtectorGeneration(2),
                        CognitiveKeyProtectorSecurityLevel.SOFTWARE
                    )
                )
            ).value
            try {
                assertNotEquals(first.reference.platformReference, second.reference.platformReference)
                val result = protector.wrap(
                    first,
                    CognitiveDekReference(CognitiveDekId("stale-dek"), CognitiveDekGeneration(1)),
                    CognitiveDekMaterial(ByteArray(32) { 1 })
                )
                assertEquals(
                    CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP,
                    assertIs<CognitiveEncryptionResult.Rejected>(result).category
                )
            } finally {
                protector.retire(second)
            }
        } finally {
            protector.retire(first)
        }
    }

    private fun request(prefix: String, generation: Long): CognitiveKeyProtectorCreationRequest =
        CognitiveKeyProtectorCreationRequest(
            id = CognitiveKeyProtectorId("instrumented-$prefix-${UUID.randomUUID()}"),
            generation = CognitiveKeyProtectorGeneration(generation),
            requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
        )
}
