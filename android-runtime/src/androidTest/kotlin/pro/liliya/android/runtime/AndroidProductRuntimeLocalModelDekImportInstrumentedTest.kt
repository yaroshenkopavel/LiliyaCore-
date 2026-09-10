package pro.liliya.android.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekResolutionResult
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisionAndRegisterResult
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningRequest
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
class AndroidProductRuntimeLocalModelDekImportInstrumentedTest {
    @Test
    fun lmdk1_import_wraps_durably_and_reopens_exact_dek_without_plaintext_file() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "local-model-dek-import-" + UUID.randomUUID()
        val first = assertIs<AndroidProductRuntimeProtectedModelDekOpenResult.Ready>(
            AndroidProductRuntimeProtectedModelDekAssembly.open(
                context = context,
                foundation = foundation(),
                directoryName = directory
            )
        ).assembly
        val descriptor = assertIs<
            ProtectedModelKeyProtectorResult.Success<ProtectedModelKeyProtectorDescriptor>
        >(
            first.keyProtector.create(
                ProtectedModelKeyProtectorCreationRequest(
                    id = ProtectedModelKeyProtectorId("lmdk-protector-" + UUID.randomUUID()),
                    generation = ProtectedModelKeyProtectorGeneration(1),
                    requestedSecurityLevel = ProtectedModelKeyProtectorSecurityLevel.SOFTWARE
                )
            )
        ).value

        val request = ProtectedModelDekProvisioningRequest(
            model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("lmdk-model-" + UUID.randomUUID()),
                generation = ProtectedModelGeneration(4)
            ),
            dek = ModelDekReference(
                id = ModelDekId("lmdk-dek-" + UUID.randomUUID()),
                generation = ModelDekGeneration(7)
            )
        )
        val plaintext = ByteArray(32) { (it * 5 + 11).toByte() }
        val artifact = artifact(request, plaintext)

        try {
            val registered = assertIs<ProtectedModelDekProvisionAndRegisterResult.Registered>(
                AndroidProductRuntimeLocalModelDekImport.importAndRegister(
                    openInput = { ByteArrayInputStream(artifact) },
                    store = first.store,
                    request = request,
                    protectorDescriptor = descriptor
                )
            )
            assertEquals(request.model, registered.binding.model)
            assertEquals(request.dek, registered.binding.dek)

            val durableRoot = File(context.filesDir, directory)
            assertTrue(durableRoot.exists())
            durableRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val bytes = file.readBytes()
                try {
                    assertTrue(!containsSubsequence(bytes, plaintext))
                } finally {
                    bytes.fill(0)
                }
            }

            val reopened = assertIs<AndroidProductRuntimeProtectedModelDekOpenResult.Ready>(
                AndroidProductRuntimeProtectedModelDekAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = directory
                )
            ).assembly
            val resolved = assertIs<PersistentProtectedModelDekResolutionResult.Resolved>(
                reopened.store.resolveExact(request.model, request.dek)
            )
            val resolvedBytes = requireNotNull(resolved.key.encoded)
            try {
                assertContentEquals(plaintext, resolvedBytes)
            } finally {
                resolvedBytes.fill(0)
            }

            val wrongModel = request.model.copy(
                generation = ProtectedModelGeneration(request.model.generation.value + 1)
            )
            assertNull(reopened.store.resolveForProtectedModelOpen(wrongModel, request.dek))
        } finally {
            artifact.fill(0)
            plaintext.fill(0)
            first.keyProtector.retire(descriptor)
            File(context.filesDir, directory).deleteRecursively()
        }
    }

    private fun artifact(
        request: ProtectedModelDekProvisioningRequest,
        material: ByteArray
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(byteArrayOf('L'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte()))
            data.writeInt(1)
            writeString(data, request.model.packageId.value)
            data.writeLong(request.model.generation.value)
            writeString(data, request.dek.id.value)
            data.writeLong(request.dek.generation.value)
            data.writeInt(material.size)
            data.write(material)
        }
        return output.toByteArray()
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            output.writeInt(bytes.size)
            output.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "lmdk-import-" + sequence.incrementAndGet()
            }
        )
    }
}
