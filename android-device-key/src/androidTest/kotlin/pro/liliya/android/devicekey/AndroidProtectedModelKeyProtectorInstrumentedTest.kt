package pro.liliya.android.devicekey

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.protectedmodel.*

@RunWith(AndroidJUnit4::class)
class AndroidProtectedModelKeyProtectorInstrumentedTest {
    @Test
    fun real_keystore_model_protector_wraps_unwraps_and_retires_exact_dek() {
        val protector = AndroidProtectedModelKeyProtector(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        val request = ProtectedModelKeyProtectorCreationRequest(
            ProtectedModelKeyProtectorId("instrumented-model-${UUID.randomUUID()}"),
            ProtectedModelKeyProtectorGeneration(1),
            ProtectedModelKeyProtectorSecurityLevel.SOFTWARE
        )
        val descriptor = assertIs<ProtectedModelKeyProtectorResult.Success<ProtectedModelKeyProtectorDescriptor>>(
            protector.create(request)
        ).value
        try {
            assertFalse(protector.aliasForTesting(descriptor.reference).contains(request.id.value))
            val dek = ModelDekReference(ModelDekId("model-dek-${UUID.randomUUID()}"), ModelDekGeneration(1))
            val raw = ByteArray(32) { (it * 5 + 9).toByte() }
            val wrapped = assertIs<ProtectedModelKeyProtectorResult.Success<WrappedProtectedModelDek>>(
                protector.wrap(descriptor, dek, ProtectedModelDekMaterial(raw))
            ).value
            assertEquals(dek, wrapped.dek)
            assertEquals(descriptor.reference, wrapped.protector)
            assertFalse(wrapped.copyWrapped().contentEquals(raw))
            val opened = assertIs<ProtectedModelKeyProtectorResult.Success<ProtectedModelDekMaterial>>(
                protector.unwrap(descriptor, wrapped)
            ).value
            assertContentEquals(raw, opened.copyBytes())
            assertIs<ProtectedModelKeyProtectorResult.Success<Unit>>(protector.retire(descriptor))
            assertEquals(
                ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING,
                assertIs<ProtectedModelKeyProtectorResult.Rejected>(protector.inspect(descriptor.reference)).reason
            )
        } finally {
            protector.retire(descriptor)
        }
    }

    @Test
    fun replacement_generation_is_aba_safe() {
        val protector = AndroidProtectedModelKeyProtector(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        val id = ProtectedModelKeyProtectorId("instrumented-model-aba-${UUID.randomUUID()}")
        val first = assertIs<ProtectedModelKeyProtectorResult.Success<ProtectedModelKeyProtectorDescriptor>>(
            protector.create(ProtectedModelKeyProtectorCreationRequest(id, ProtectedModelKeyProtectorGeneration(1), ProtectedModelKeyProtectorSecurityLevel.SOFTWARE))
        ).value
        try {
            assertIs<ProtectedModelKeyProtectorResult.Success<Unit>>(protector.retire(first))
            val second = assertIs<ProtectedModelKeyProtectorResult.Success<ProtectedModelKeyProtectorDescriptor>>(
                protector.create(ProtectedModelKeyProtectorCreationRequest(id, ProtectedModelKeyProtectorGeneration(2), ProtectedModelKeyProtectorSecurityLevel.SOFTWARE))
            ).value
            try {
                assertNotEquals(first.reference.platformReference, second.reference.platformReference)
                val stale = protector.wrap(
                    first,
                    ModelDekReference(ModelDekId("stale-model-dek"), ModelDekGeneration(1)),
                    ProtectedModelDekMaterial(ByteArray(32) { 1 })
                )
                assertEquals(
                    ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING,
                    assertIs<ProtectedModelKeyProtectorResult.Rejected>(stale).reason
                )
            } finally {
                protector.retire(second)
            }
        } finally {
            protector.retire(first)
        }
    }
}
