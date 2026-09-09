package pro.liliya.android.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekRegistrationResult
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekResolutionResult
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorCreationRequest
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorGeneration
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorId
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorResult
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorSecurityLevel
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

@RunWith(AndroidJUnit4::class)
class AndroidProductRuntimeProtectedModelDekAssemblyInstrumentedTest {
    @Test
    fun real_keystore_and_durable_backend_reopen_exact_provisioned_model_dek() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "protected-model-dek-test-" + UUID.randomUUID()
        val first = assertIs<AndroidProductRuntimeProtectedModelDekOpenResult.Ready>(
            AndroidProductRuntimeProtectedModelDekAssembly.open(
                context = context,
                foundation = foundation(),
                directoryName = directory
            )
        ).assembly

        val protectorRequest = ProtectedModelKeyProtectorCreationRequest(
            id = ProtectedModelKeyProtectorId("protector-" + UUID.randomUUID()),
            generation = ProtectedModelKeyProtectorGeneration(1),
            requestedSecurityLevel = ProtectedModelKeyProtectorSecurityLevel.SOFTWARE
        )
        val descriptor = assertIs<
            ProtectedModelKeyProtectorResult.Success<ProtectedModelKeyProtectorDescriptor>
        >(first.keyProtector.create(protectorRequest)).value

        val model = ProtectedModelReference(
            packageId = ProtectedModelPackageId("model-" + UUID.randomUUID()),
            generation = ProtectedModelGeneration(4)
        )
        val dek = ModelDekReference(
            id = ModelDekId("dek-" + UUID.randomUUID()),
            generation = ModelDekGeneration(7)
        )
        val provisioned = ByteArray(32) { (it * 7 + 3).toByte() }

        try {
            val registered = assertIs<PersistentProtectedModelDekRegistrationResult.Registered>(
                first.store.registerExact(
                    model = model,
                    reference = dek,
                    protectorDescriptor = descriptor,
                    material = ProtectedModelDekMaterial(provisioned)
                )
            )
            assertEquals(model, registered.binding.model)
            assertEquals(dek, registered.binding.dek)

            val firstResolved = assertIs<PersistentProtectedModelDekResolutionResult.Resolved>(
                first.store.resolveExact(model, dek)
            )
            val firstBytes = requireNotNull(firstResolved.key.encoded)
            assertContentEquals(provisioned, firstBytes)

            val reopened = assertIs<AndroidProductRuntimeProtectedModelDekOpenResult.Ready>(
                AndroidProductRuntimeProtectedModelDekAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = directory
                )
            ).assembly

            val reopenedResolved =
                assertIs<PersistentProtectedModelDekResolutionResult.Resolved>(
                    reopened.store.resolveExact(model, dek)
                )
            val reopenedBytes = requireNotNull(reopenedResolved.key.encoded)
            assertContentEquals(provisioned, reopenedBytes)

            val wrongModel = model.copy(
                generation = ProtectedModelGeneration(model.generation.value + 1)
            )
            assertNull(reopened.store.resolveForProtectedModelOpen(wrongModel, dek))

            firstBytes.fill(0)
            reopenedBytes.fill(0)
        } finally {
            provisioned.fill(0)
            first.keyProtector.retire(descriptor)
            File(context.filesDir, directory).deleteRecursively()
        }
    }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "android-protected-model-dek-" + sequence.incrementAndGet()
            }
        )
    }
}
