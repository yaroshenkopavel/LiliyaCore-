package pro.liliya.android.protectedmodel.staging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.protectedmodel.LargeProtectedModelOpaqueArtifactId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingGeneration
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

@RunWith(AndroidJUnit4::class)
class AndroidAppPrivateProtectedModelStagingHardeningInstrumentedTest {

    @Test
    fun direct_incomplete_seal_rejects_without_poisoning_working_artifact() = withCleanRoot { backend ->
        val prepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            backend.prepare(attempt(), 10)
        )
        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            backend.append(prepared.handle, 0, "alpha".encodeToByteArray())
        )
        assertIs<LargeProtectedModelStagingSealResult.Rejected>(backend.seal(prepared.handle))
        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            backend.append(prepared.handle, 1, "omega".encodeToByteArray())
        )
        assertIs<LargeProtectedModelStagingSealResult.Sealed>(backend.seal(prepared.handle))
    }

    @Test
    fun foreign_id_cannot_delete_known_artifact_and_rendering_redacts_token_and_path() = withCleanRoot { backend ->
        val prepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            backend.prepare(attempt(), 5)
        )
        val knownFile = assertNotNull(backend.physicalFileForTesting(prepared.handle.artifactId))
        val token = prepared.handle.artifactId.value

        assertIs<LargeProtectedModelStagingDeleteResult.Rejected>(
            backend.delete(LargeProtectedModelOpaqueArtifactId("f".repeat(32)))
        )
        assertTrue(knownFile.exists())
        assertFalse(prepared.toString().contains(token))
        assertFalse(prepared.toString().contains(knownFile.absolutePath))

        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            backend.append(prepared.handle, 0, "alpha".encodeToByteArray())
        )
        val sealed = assertIs<LargeProtectedModelStagingSealResult.Sealed>(backend.seal(prepared.handle))
        assertFalse(sealed.toString().contains(token))
        assertFalse(sealed.toString().contains(knownFile.parentFile?.absolutePath.orEmpty()))
    }

    private fun attempt() = LargeProtectedModelStagingAttemptReference(
        generation = LargeProtectedModelStagingGeneration(1),
        model = ProtectedModelReference(
            ProtectedModelPackageId("android-staging-hardening"),
            ProtectedModelGeneration(1)
        )
    )

    private inline fun withCleanRoot(
        block: (AndroidAppPrivateProtectedModelStagingBackend) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val backend = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { "9".repeat(32) }
        val root = backend.adapterRootForTesting()
        root.deleteRecursively()
        try {
            block(backend)
        } finally {
            root.deleteRecursively()
        }
    }
}
