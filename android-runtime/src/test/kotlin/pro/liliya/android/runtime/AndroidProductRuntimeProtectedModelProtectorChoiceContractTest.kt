package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtector
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorCreationRequest
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorFailure
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorGeneration
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorId
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorPlatformReference
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorReference
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorResult
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorSecurityLevel
import pro.liliya.core.protectedmodel.WrappedProtectedModelDek

class AndroidProductRuntimeProtectedModelProtectorChoiceContractTest {
    @Test
    fun create_once_preserves_exact_security_request() {
        val result = AndroidProductRuntimeProtectedModelProtectorChoiceFactory.create(
            AndroidProductRuntimeProtectedModelProtectorChoice.CreateOnce(
                protectorId = "model-protector",
                generation = 4,
                security =
                    AndroidProductRuntimeProtectedModelProtectorSecurity.STRONGBOX
            )
        )

        val ready =
            assertIs<AndroidProductRuntimeProtectedModelProtectorChoiceResult.Ready>(result)
        val request =
            assertIs<AndroidProductRuntimeProtectedModelProtectorRequest.CreateOnce>(
                ready.request
            ).request
        assertEquals(ProtectedModelKeyProtectorId("model-protector"), request.id)
        assertEquals(ProtectedModelKeyProtectorGeneration(4), request.generation)
        assertEquals(
            ProtectedModelKeyProtectorSecurityLevel.STRONGBOX,
            request.requestedSecurityLevel
        )
    }

    @Test
    fun restore_exact_preserves_platform_reference_and_calls_only_inspect() {
        val choice = assertIs<AndroidProductRuntimeProtectedModelProtectorChoiceResult.Ready>(
            AndroidProductRuntimeProtectedModelProtectorChoiceFactory.create(
                AndroidProductRuntimeProtectedModelProtectorChoice.RestoreExact(
                    protectorId = "model-protector",
                    generation = 7,
                    platformReference = "random:exact-reference"
                )
            )
        )
        val request =
            assertIs<AndroidProductRuntimeProtectedModelProtectorRequest.RestoreExact>(
                choice.request
            )
        val fake = RecordingProtector(
            descriptor = ProtectedModelKeyProtectorDescriptor(
                reference = request.reference,
                securityLevel =
                    ProtectedModelKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
            )
        )

        val result = AndroidProductRuntimeProtectedModelProtectorSetup.execute(
            fake,
            request
        )

        assertIs<AndroidProductRuntimeProtectedModelProtectorSetupResult.Ready>(result)
        assertEquals(0, fake.createCalls)
        assertEquals(1, fake.inspectCalls)
        assertEquals(request.reference, fake.lastInspected)
    }

    @Test
    fun create_rejection_is_preserved_without_weaker_fallback() {
        val request =
            assertIs<AndroidProductRuntimeProtectedModelProtectorChoiceResult.Ready>(
                AndroidProductRuntimeProtectedModelProtectorChoiceFactory.create(
                    AndroidProductRuntimeProtectedModelProtectorChoice.CreateOnce(
                        protectorId = "strict",
                        generation = 1,
                        security =
                            AndroidProductRuntimeProtectedModelProtectorSecurity.STRONGBOX
                    )
                )
            ).request

        val fake = RecordingProtector(
            createResult = ProtectedModelKeyProtectorResult.Rejected(
                ProtectedModelKeyProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        )

        val result = AndroidProductRuntimeProtectedModelProtectorSetup.execute(
            fake,
            request
        )

        assertEquals(1, fake.createCalls)
        assertEquals(0, fake.inspectCalls)
        assertEquals(
            ProtectedModelKeyProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
            assertIs<AndroidProductRuntimeProtectedModelProtectorSetupResult.Rejected>(
                result
            ).reason
        )
    }

    @Test
    fun invalid_product_choice_is_rejected_without_substitution() {
        val result = AndroidProductRuntimeProtectedModelProtectorChoiceFactory.create(
            AndroidProductRuntimeProtectedModelProtectorChoice.RestoreExact(
                protectorId = "",
                generation = 0,
                platformReference = ""
            )
        )

        assertIs<AndroidProductRuntimeProtectedModelProtectorChoiceResult.Rejected>(result)
    }

    private class RecordingProtector(
        private val descriptor: ProtectedModelKeyProtectorDescriptor? = null,
        private val createResult:
            ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor>? =
            null
    ) : ProtectedModelKeyProtector {
        var createCalls = 0
        var inspectCalls = 0
        var lastInspected: ProtectedModelKeyProtectorReference? = null

        override fun create(
            request: ProtectedModelKeyProtectorCreationRequest
        ): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor> {
            createCalls += 1
            return createResult
                ?: ProtectedModelKeyProtectorResult.Rejected(
                    ProtectedModelKeyProtectorFailure.INVALID_REQUEST
                )
        }

        override fun inspect(
            reference: ProtectedModelKeyProtectorReference
        ): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor> {
            inspectCalls += 1
            lastInspected = reference
            return descriptor?.let { ProtectedModelKeyProtectorResult.Success(it) }
                ?: ProtectedModelKeyProtectorResult.Rejected(
                    ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING
                )
        }

        override fun wrap(
            expected: ProtectedModelKeyProtectorDescriptor,
            dek: ModelDekReference,
            material: ProtectedModelDekMaterial
        ): ProtectedModelKeyProtectorResult<WrappedProtectedModelDek> =
            ProtectedModelKeyProtectorResult.Rejected(
                ProtectedModelKeyProtectorFailure.INVALID_REQUEST
            )

        override fun unwrap(
            expected: ProtectedModelKeyProtectorDescriptor,
            envelope: WrappedProtectedModelDek
        ): ProtectedModelKeyProtectorResult<ProtectedModelDekMaterial> =
            ProtectedModelKeyProtectorResult.Rejected(
                ProtectedModelKeyProtectorFailure.INVALID_REQUEST
            )

        override fun retire(
            expected: ProtectedModelKeyProtectorDescriptor
        ): ProtectedModelKeyProtectorResult<Unit> =
            ProtectedModelKeyProtectorResult.Rejected(
                ProtectedModelKeyProtectorFailure.INVALID_REQUEST
            )
    }
}
