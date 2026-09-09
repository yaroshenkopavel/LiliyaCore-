package pro.liliya.android.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningFailure
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningRequest
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

class AndroidProductRuntimeLocalModelDekArtifactContractTest {
    @Test
    fun exact_artifact_provisions_once_for_exact_model_and_dek() {
        val material = ByteArray(32) { index -> (index + 1).toByte() }
        val model = model("package", 4)
        val dek = dek("dek", 7)
        val parsed = assertIs<AndroidProductRuntimeLocalModelDekArtifactResult.Ready>(
            AndroidProductRuntimeLocalModelDekArtifact.parse(
                ByteArrayInputStream(artifact(model, dek, material))
            )
        )

        val first = assertIs<ProtectedModelDekProvisioningResult.Provisioned>(
            parsed.provider.provision(
                ProtectedModelDekProvisioningRequest(model, dek)
            )
        )
        val copied = first.evidence.material.copyBytes()
        try {
            assertContentEquals(material, copied)
        } finally {
            copied.fill(0)
            material.fill(0)
        }

        val second = assertIs<ProtectedModelDekProvisioningResult.Rejected>(
            parsed.provider.provision(
                ProtectedModelDekProvisioningRequest(model, dek)
            )
        )
        assertEquals(ProtectedModelDekProvisioningFailure.REJECTED, second.reason)
    }

    @Test
    fun wrong_reference_is_terminal_and_never_provisions_material() {
        val model = model("package", 4)
        val dek = dek("dek", 7)
        val parsed = assertIs<AndroidProductRuntimeLocalModelDekArtifactResult.Ready>(
            AndroidProductRuntimeLocalModelDekArtifact.parse(
                ByteArrayInputStream(
                    artifact(model, dek, ByteArray(32) { 9 })
                )
            )
        )

        val mismatch = assertIs<ProtectedModelDekProvisioningResult.Rejected>(
            parsed.provider.provision(
                ProtectedModelDekProvisioningRequest(
                    model.copy(generation = ProtectedModelGeneration(5)),
                    dek
                )
            )
        )
        assertEquals(
            ProtectedModelDekProvisioningFailure.REFERENCE_MISMATCH,
            mismatch.reason
        )

        val retry = assertIs<ProtectedModelDekProvisioningResult.Rejected>(
            parsed.provider.provision(ProtectedModelDekProvisioningRequest(model, dek))
        )
        assertEquals(ProtectedModelDekProvisioningFailure.REJECTED, retry.reason)
    }

    @Test
    fun trailing_and_oversized_artifacts_fail_closed() {
        val model = model("package", 1)
        val dek = dek("dek", 1)
        val exact = artifact(model, dek, ByteArray(32) { 3 })

        val trailing = assertIs<AndroidProductRuntimeLocalModelDekArtifactResult.Rejected>(
            AndroidProductRuntimeLocalModelDekArtifact.parse(
                ByteArrayInputStream(exact + byteArrayOf(1))
            )
        )
        assertEquals(
            AndroidProductRuntimeLocalModelDekArtifactFailure.TRAILING_DATA,
            trailing.reason
        )

        val oversized = assertIs<AndroidProductRuntimeLocalModelDekArtifactResult.Rejected>(
            AndroidProductRuntimeLocalModelDekArtifact.parse(
                ByteArrayInputStream(exact),
                maxArtifactBytes = exact.size - 1
            )
        )
        assertEquals(
            AndroidProductRuntimeLocalModelDekArtifactFailure.OVERSIZED,
            oversized.reason
        )
    }

    @Test
    fun unsupported_version_and_malformed_utf8_fail_closed() {
        val model = model("package", 1)
        val dek = dek("dek", 1)
        val exact = artifact(model, dek, ByteArray(32) { 4 })

        val wrongVersion = exact.copyOf().also { bytes ->
            bytes[5] = 0
            bytes[6] = 0
            bytes[7] = 0
            bytes[8] = 2
        }
        val versionResult =
            assertIs<AndroidProductRuntimeLocalModelDekArtifactResult.Rejected>(
                AndroidProductRuntimeLocalModelDekArtifact.parse(
                    ByteArrayInputStream(wrongVersion)
                )
            )
        assertEquals(
            AndroidProductRuntimeLocalModelDekArtifactFailure.UNSUPPORTED_VERSION,
            versionResult.reason
        )

        val malformed = exact.copyOf()
        val firstStringStart = 5 + 4 + 4
        malformed[firstStringStart] = 0xC3.toByte()
        malformed[firstStringStart + 1] = 0x28
        val malformedResult =
            assertIs<AndroidProductRuntimeLocalModelDekArtifactResult.Rejected>(
                AndroidProductRuntimeLocalModelDekArtifact.parse(
                    ByteArrayInputStream(malformed)
                )
            )
        assertEquals(
            AndroidProductRuntimeLocalModelDekArtifactFailure.MALFORMED,
            malformedResult.reason
        )

        exact.fill(0)
        wrongVersion.fill(0)
        malformed.fill(0)
    }

    @Test
    fun provider_rendering_redacts_material() {
        val model = model("package", 1)
        val dek = dek("dek", 1)
        val parsed = assertIs<AndroidProductRuntimeLocalModelDekArtifactResult.Ready>(
            AndroidProductRuntimeLocalModelDekArtifact.parse(
                ByteArrayInputStream(
                    artifact(model, dek, ByteArray(32) { 0x5a.toByte() })
                )
            )
        )

        assertEquals(
            "AndroidProductRuntimeLocalModelDekProvisioningProvider(" +
                "model=$model, dek=$dek, material=<redacted>, consumed=false)",
            parsed.provider.toString()
        )
        parsed.provider.close()
    }

    private fun artifact(
        model: ProtectedModelReference,
        dek: ModelDekReference,
        material: ByteArray
    ): ByteArray {
        require(material.size == 32)
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(byteArrayOf('L'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte()))
                out.writeInt(1)
                writeString(out, model.packageId.value)
                out.writeLong(model.generation.value)
                writeString(out, dek.id.value)
                out.writeLong(dek.generation.value)
                out.write(material)
            }
        }.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            out.writeInt(bytes.size)
            out.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun model(id: String, generation: Long) =
        ProtectedModelReference(
            ProtectedModelPackageId(id),
            ProtectedModelGeneration(generation)
        )

    private fun dek(id: String, generation: Long) =
        ModelDekReference(
            ModelDekId(id),
            ModelDekGeneration(generation)
        )
}
