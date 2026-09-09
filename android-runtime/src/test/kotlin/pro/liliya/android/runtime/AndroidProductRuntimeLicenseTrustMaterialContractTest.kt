package pro.liliya.android.runtime

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseKeyId

class AndroidProductRuntimeLicenseTrustMaterialContractTest {
    @Test
    fun exact_product_owned_key_material_is_resolved_without_substitution() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val result = AndroidProductRuntimeLicenseTrustMaterial.create(
            listOf(AndroidProductRuntimeLicenseTrustKey("primary", bytes))
        )

        val ready = assertIs<AndroidProductRuntimeLicenseTrustMaterialResult.Ready>(result)
        val key = requireNotNull(ready.resolver.resolve(LicenseKeyId("primary")))
        assertEquals(LicenseKeyId("primary"), key.keyId)
        assertEquals(LicenseAlgorithm("ECDSA-P256-SHA256"), key.algorithm)
        assertContentEquals(bytes, key.copyMaterial())
        assertNull(ready.resolver.resolve(LicenseKeyId("other")))
    }

    @Test
    fun caller_mutation_after_creation_does_not_change_trusted_material() {
        val bytes = byteArrayOf(7, 8, 9)
        val input = AndroidProductRuntimeLicenseTrustKey("primary", bytes)
        bytes.fill(0)

        val result = AndroidProductRuntimeLicenseTrustMaterial.create(listOf(input))

        val ready = assertIs<AndroidProductRuntimeLicenseTrustMaterialResult.Ready>(result)
        val key = requireNotNull(ready.resolver.resolve(LicenseKeyId("primary")))
        assertContentEquals(byteArrayOf(7, 8, 9), key.copyMaterial())
    }

    @Test
    fun duplicate_key_ids_are_rejected() {
        val result = AndroidProductRuntimeLicenseTrustMaterial.create(
            listOf(
                AndroidProductRuntimeLicenseTrustKey("same", byteArrayOf(1)),
                AndroidProductRuntimeLicenseTrustKey("same", byteArrayOf(2))
            )
        )

        assertIs<AndroidProductRuntimeLicenseTrustMaterialResult.Rejected>(result)
    }

    @Test
    fun empty_key_set_is_rejected() {
        val result = AndroidProductRuntimeLicenseTrustMaterial.create(emptyList())

        assertIs<AndroidProductRuntimeLicenseTrustMaterialResult.Rejected>(result)
    }
}
