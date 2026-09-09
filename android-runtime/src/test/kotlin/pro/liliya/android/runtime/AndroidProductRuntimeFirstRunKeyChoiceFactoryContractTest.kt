package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel

class AndroidProductRuntimeFirstRunKeyChoiceFactoryContractTest {
    @Test
    fun explicit_create_once_choice_maps_exact_identifiers_and_security() {
        val result = AndroidProductRuntimeFirstRunKeyChoiceFactory.create(
            AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
                dekId = "first-run-dek",
                protectorId = "first-run-protector",
                protectorGeneration = 1L,
                security = AndroidProductRuntimeFirstRunKeySecurity.TRUSTED_ENVIRONMENT
            )
        )

        val ready = assertIs<AndroidProductRuntimeFirstRunKeyChoiceResult.Ready>(result)
        val request = assertIs<AndroidCognitiveStorageFirstRunKeySetupRequest.CreateOnce>(
            ready.request
        )
        assertEquals("first-run-dek", request.dekId.value)
        assertEquals("first-run-protector", request.protectorRequest.id.value)
        assertEquals(1L, request.protectorRequest.generation.value)
        assertEquals(
            CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT,
            request.protectorRequest.requestedSecurityLevel
        )
    }

    @Test
    fun restore_exact_choice_preserves_exact_reference() {
        val result = AndroidProductRuntimeFirstRunKeyChoiceFactory.create(
            AndroidProductRuntimeFirstRunKeyChoice.RestoreExact(
                dekId = "existing-dek",
                dekGeneration = 7L
            )
        )

        val ready = assertIs<AndroidProductRuntimeFirstRunKeyChoiceResult.Ready>(result)
        val request = assertIs<AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact>(
            ready.request
        )
        assertEquals("existing-dek", request.reference.id.value)
        assertEquals(7L, request.reference.generation.value)
    }

    @Test
    fun invalid_product_choice_is_rejected_without_substitution() {
        val result = AndroidProductRuntimeFirstRunKeyChoiceFactory.create(
            AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
                dekId = "",
                protectorId = "protector",
                protectorGeneration = 1L,
                security = AndroidProductRuntimeFirstRunKeySecurity.SOFTWARE
            )
        )

        assertIs<AndroidProductRuntimeFirstRunKeyChoiceResult.Rejected>(result)
    }
}
