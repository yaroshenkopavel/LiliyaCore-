package pro.liliya.app

import android.content.ContextWrapper
import java.io.File
import java.lang.reflect.Modifier
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoice
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeySecurity
import pro.liliya.android.runtime.AndroidProductRuntimeObservability
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelBudgetInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelStagingProvisioning
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAdmissionInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion
import sun.misc.Unsafe

class ProductionAndroidFirstRunProductInputTemplateContractTest {
    @Test
    fun template_owns_only_explicit_static_product_inputs() {
        val fields = ProductionAndroidFirstRunProductInputTemplate::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }

        assertEquals(
            setOf(
                "context",
                "observability",
                "supportedLicenseSchemaVersion",
                "licenseTrustKeys",
                "authorityPlan",
                "admission",
                "keyChoice",
                "protectedModelBudgets",
                "staging",
                "preparedInputOwners",
                "semanticDirectoryName",
                "cognitiveStorageDirectoryName"
            ),
            fields.map { it.name }.toSet()
        )

        val ownedTypeNames = fields.map { it.type.name }
        assertFalse(ownedTypeNames.any { it.contains("LicenseSignedEnvelope") })
        assertFalse(ownedTypeNames.any { it == "java.io.File" })
        assertFalse(ownedTypeNames.any { it.contains("Bearer", ignoreCase = true) })
        assertFalse(ownedTypeNames.any { it.contains("Transport", ignoreCase = true) })
        assertFalse(ownedTypeNames.any { it.contains("Credential", ignoreCase = true) })
    }

    @Test
    fun product_input_port_forwards_exact_attempt_inputs_without_substitution() {
        val context = allocateWithoutConstructor<ContextWrapper>()
        val observability = AndroidProductRuntimeObservability.create(
            createTempDirectory("liliya-first-run-template-observability-").toFile()
        )
        val authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
            capabilities = emptyList(),
            directGrants = emptyList()
        )
        val admission = AndroidProductRuntimeStartupAdmissionInput(
            productId = "liliya-pro",
            feature = "chat",
            subject = "subject",
            now = Instant.EPOCH,
            minimumRevocationEpoch = 0,
            minimumReplaySequence = null,
            suspiciousTimeOrReplayState = false,
            principal = "principal",
            capability = "chat",
            authorityScope = "local"
        )
        val keyChoice = AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
            dekId = "dek",
            protectorId = "protector",
            protectorGeneration = 1,
            security = AndroidProductRuntimeFirstRunKeySecurity.SOFTWARE
        )
        val budgets = AndroidProductRuntimeProtectedModelBudgetInput(
            maxTotalPlaintextBytes = 1,
            maxTotalCiphertextBodyBytes = 1,
            maxTotalProtectedPayloadBytes = 1,
            maxSegmentCount = 1,
            minNonFinalSegmentPlaintextBytes = 1,
            maxSegmentPlaintextBytes = 1,
            maxSegmentCiphertextBodyBytes = 1,
            maxStructuralIdentifierChars = 1,
            maxCanonicalManifestBytes = 1,
            maxModelProfileIdChars = 1,
            maxSignerIdChars = 1,
            maxCanonicalSignedManifestBytes = 1,
            maxContainerBytes = 1,
            maxSignatureBytes = 1
        )
        val staging = allocateWithoutConstructor<AndroidProductRuntimeProtectedModelStagingProvisioning>()
        val preparedOwners = allocateWithoutConstructor<AndroidProductRuntimeStartupPreparedInputOwnerTemplate>()
        val template = ProductionAndroidFirstRunProductInputTemplate(
            context = context,
            observability = observability,
            supportedLicenseSchemaVersion = 7,
            licenseTrustKeys = emptyList(),
            authorityPlan = authorityPlan,
            admission = admission,
            keyChoice = keyChoice,
            protectedModelBudgets = budgets,
            staging = staging,
            preparedInputOwners = preparedOwners,
            semanticDirectoryName = "semantic-exact",
            cognitiveStorageDirectoryName = "cognitive-exact"
        )
        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            signingKeyId = LicenseKeyId("template-test-key"),
            payload = LicenseCanonicalPayload.of(byteArrayOf(1, 2, 3)),
            signature = LicenseSignature.of(byteArrayOf(4, 5, 6))
        )
        val localModel = File("/exact/already-selected/model.lpm1")

        val input = template.productInputPort().create(envelope, localModel)

        assertSame(envelope, input.licenseEnvelope)
        assertSame(localModel, input.localModelFile)
        assertSame(context, input.context)
        assertSame(observability, input.observability)
        assertSame(authorityPlan, input.authorityPlan)
        assertSame(admission, input.admission)
        assertSame(keyChoice, input.keyChoice)
        assertSame(budgets, input.protectedModelBudgets)
        assertSame(staging, input.staging)
        assertSame(preparedOwners, input.preparedInputOwners)
        assertEquals(7, input.supportedLicenseSchemaVersion)
        assertEquals("semantic-exact", input.semanticDirectoryName)
        assertEquals("cognitive-exact", input.cognitiveStorageDirectoryName)
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
