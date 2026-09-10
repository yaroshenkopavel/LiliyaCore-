package pro.liliya.android.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

class AndroidProductRuntimeLocalModelDekImportContractTest {
    @Test
    fun exact_lmdk1_artifact_provisions_exact_reference_and_material_once() {
        val request = request()
        val material = ByteArray(32) { (it * 11 + 3).toByte() }
        val provider = AndroidProductRuntimeLocalModelDekProvisioningProvider {
            ByteArrayInputStream(artifact(request, material))
        }

        val result = assertIs<ProtectedModelDekProvisioningResult.Provisioned>(
            provider.provision(request)
        )
        assertEquals(request.model, result.evidence.model)
        assertEquals(request.dek, result.evidence.dek)
        val copied = result.evidence.material.copyBytes()
        try {
            assertContentEquals(material, copied)
        } finally {
            copied.fill(0)
            material.fill(0)
        }
        assertTrue(provider.isConsumed())

        val second = assertIs<ProtectedModelDekProvisioningResult.Rejected>(
            provider.provision(request)
        )
        assertEquals(ProtectedModelDekProvisioningFailure.REJECTED, second.reason)
    }

    @Test
    fun wrong_exact_model_reference_is_rejected_before_evidence_release() {
        val request = request()
        val other = request.copy(
            model = request.model.copy(generation = ProtectedModelGeneration(99))
        )
        val provider = AndroidProductRuntimeLocalModelDekProvisioningProvider {
            ByteArrayInputStream(artifact(other, ByteArray(32) { 7 }))
        }

        val result = assertIs<ProtectedModelDekProvisioningResult.Rejected>(
            provider.provision(request)
        )
        assertEquals(ProtectedModelDekProvisioningFailure.REFERENCE_MISMATCH, result.reason)
    }

    @Test
    fun wrong_exact_dek_reference_is_rejected_before_evidence_release() {
        val request = request()
        val other = request.copy(
            dek = ModelDekReference(request.dek.id, ModelDekGeneration(42))
        )
        val provider = AndroidProductRuntimeLocalModelDekProvisioningProvider {
            ByteArrayInputStream(artifact(other, ByteArray(32) { 9 }))
        }

        val result = assertIs<ProtectedModelDekProvisioningResult.Rejected>(
            provider.provision(request)
        )
        assertEquals(ProtectedModelDekProvisioningFailure.REFERENCE_MISMATCH, result.reason)
    }

    @Test
    fun malformed_utf8_trailing_bytes_wrong_key_size_and_oversize_fail_closed() {
        val request = request()

        val malformedUtf8 = artifact(request, ByteArray(32) { 1 }).copyOf().also { bytes ->
            val packageLengthOffset = 5 + 4
            val packageBytesOffset = packageLengthOffset + 4
            bytes[packageBytesOffset] = 0xC3.toByte()
            bytes[packageBytesOffset + 1] = 0x28
        }
        assertRejected(request, malformedUtf8)

        assertRejected(
            request,
            artifact(request, ByteArray(32) { 2 }) + byteArrayOf(1)
        )

        assertRejected(
            request,
            artifact(request, ByteArray(31) { 3 })
        )

        assertRejected(request, ByteArray(4097) { 4 })
    }

    @Test
    fun evidence_rendering_never_exposes_plaintext_material() {
        val request = request()
        val provider = AndroidProductRuntimeLocalModelDekProvisioningProvider {
            ByteArrayInputStream(artifact(request, ByteArray(32) { 0x5A }))
        }
        val result = assertIs<ProtectedModelDekProvisioningResult.Provisioned>(
            provider.provision(request)
        )

        val rendered = result.evidence.toString()
        assertTrue("material=<redacted>" in rendered)
        assertTrue(!rendered.contains("90, 90, 90"))
    }

    private fun assertRejected(
        request: ProtectedModelDekProvisioningRequest,
        bytes: ByteArray
    ) {
        val provider = AndroidProductRuntimeLocalModelDekProvisioningProvider {
            ByteArrayInputStream(bytes)
        }
        assertIs<ProtectedModelDekProvisioningResult.Rejected>(provider.provision(request))
    }

    private fun request(): ProtectedModelDekProvisioningRequest =
        ProtectedModelDekProvisioningRequest(
            model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("fixture-package"),
                generation = ProtectedModelGeneration(4)
            ),
            dek = ModelDekReference(
                id = ModelDekId("fixture-dek"),
                generation = ModelDekGeneration(7)
            )
        )

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
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
