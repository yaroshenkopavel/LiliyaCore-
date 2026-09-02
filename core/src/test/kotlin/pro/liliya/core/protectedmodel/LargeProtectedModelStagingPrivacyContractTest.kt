package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertFalse

class LargeProtectedModelStagingPrivacyContractTest {
    @Test
    fun staging_rendering_redacts_opaque_artifact_identifiers() {
        val model = ProtectedModelReference(
            ProtectedModelPackageId("private-package-name"),
            ProtectedModelGeneration(1)
        )
        val attempt = LargeProtectedModelStagingAttemptReference(
            LargeProtectedModelStagingGeneration(1),
            model
        )
        val opaque = LargeProtectedModelOpaqueArtifactId("/data/user/0/private/model.gguf")
        val handle = LargeProtectedModelWorkingArtifactHandle(
            LargeProtectedModelStagingBackendId("backend"),
            attempt,
            opaque
        )
        val candidate = LargeProtectedModelSealedArtifactCandidate(
            LargeProtectedModelStagingBackendId("backend"),
            attempt,
            opaque,
            10,
            LargeProtectedModelStagingDurabilityLevel.WRITE_CLOSED
        )

        assertFalse(opaque.toString().contains("/data/user/0/private/model.gguf"))
        assertFalse(handle.toString().contains("/data/user/0/private/model.gguf"))
        assertFalse(candidate.toString().contains("/data/user/0/private/model.gguf"))
    }
}
