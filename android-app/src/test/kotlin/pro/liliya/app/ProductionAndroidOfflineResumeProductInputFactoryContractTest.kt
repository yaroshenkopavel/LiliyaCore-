package pro.liliya.app

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership
import sun.misc.Unsafe

class ProductionAndroidOfflineResumeProductInputFactoryContractTest {
    @Test
    fun directory_mismatch_is_rejected_deterministically() {
        assertEquals(
            ProductionAndroidOfflineResumeProductInputFailure.DIRECTORY_MISMATCH,
            ProductionAndroidOfflineResumeDefaultProductInputFactory.preflight(
                deploymentSemanticDirectoryName = "semantic-a",
                deploymentCognitiveStorageDirectoryName = "cognitive",
                resumeSemanticDirectoryName = "semantic-b",
                resumeCognitiveStorageDirectoryName = "cognitive",
                preparedPrincipal = "principal",
                admissionPrincipal = "principal"
            )
        )
    }

    @Test
    fun principal_mismatch_is_rejected_deterministically() {
        assertEquals(
            ProductionAndroidOfflineResumeProductInputFailure.PRINCIPAL_MISMATCH,
            ProductionAndroidOfflineResumeDefaultProductInputFactory.preflight(
                deploymentSemanticDirectoryName = "semantic",
                deploymentCognitiveStorageDirectoryName = "cognitive",
                resumeSemanticDirectoryName = "semantic",
                resumeCognitiveStorageDirectoryName = "cognitive",
                preparedPrincipal = "principal-a",
                admissionPrincipal = "principal-b"
            )
        )
    }

    @Test
    fun trust_rejection_stops_before_admission_and_input_build() {
        val fixture = fixture()

        val result = ProductionAndroidOfflineResumeDefaultProductInputFactory.buildAfterPreflight(
            material = fixture.material,
            policy = fixture.policy,
            trustPort = ProductionAndroidOfflineResumeTrustPort {
                ProductionAndroidOfflineResumeTrustResult.Rejected(
                    ProductionAndroidOfflineResumeTrustFailure.VERIFICATION_REJECTED
                )
            },
            admissionPort = ProductionAndroidOfflineResumeAdmissionPort { _, _, _ ->
                error("admission must not run after trust rejection")
            },
            inputBuildPort = ProductionAndroidOfflineResumeInputBuildPort { _, _, _ ->
                error("input build must not run after trust rejection")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeProductInputFailure.TRUST_REJECTED,
            assertIs<ProductionAndroidOfflineResumeProductInputResult.Rejected>(result).reason
        )
    }

    @Test
    fun admission_rejection_stops_before_input_build() {
        val fixture = fixture()
        val trust = allocateWithoutConstructor<AndroidProductRuntimeStartupTrustOwnership>()

        val result = ProductionAndroidOfflineResumeDefaultProductInputFactory.buildAfterPreflight(
            material = fixture.material,
            policy = fixture.policy,
            trustPort = ProductionAndroidOfflineResumeTrustPort {
                ProductionAndroidOfflineResumeTrustResult.Ready(trust)
            },
            admissionPort = ProductionAndroidOfflineResumeAdmissionPort { _, _, _ ->
                ProductionAndroidOfflineResumeAdmissionResult.Rejected(
                    ProductionAndroidOfflineResumeAdmissionFailure.SECURITY_STATE_REJECTED
                )
            },
            inputBuildPort = ProductionAndroidOfflineResumeInputBuildPort { _, _, _ ->
                error("input build must not run after admission rejection")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeProductInputFailure.ADMISSION_REJECTED,
            assertIs<ProductionAndroidOfflineResumeProductInputResult.Rejected>(result).reason
        )
    }

    @Test
    fun successful_chain_returns_exact_reconstructed_input() {
        val fixture = fixture()
        val trust = allocateWithoutConstructor<AndroidProductRuntimeStartupTrustOwnership>()
        val admission = allocateWithoutConstructor<ProductionAndroidOfflineResumeAdmissionResult.Ready>()
        val input = allocateWithoutConstructor<AndroidProductRuntimeFirstRunProductInput>()
        val calls = mutableListOf<String>()

        val result = ProductionAndroidOfflineResumeDefaultProductInputFactory.buildAfterPreflight(
            material = fixture.material,
            policy = fixture.policy,
            trustPort = ProductionAndroidOfflineResumeTrustPort {
                calls += "trust"
                ProductionAndroidOfflineResumeTrustResult.Ready(trust)
            },
            admissionPort = ProductionAndroidOfflineResumeAdmissionPort { _, exactTrust, _ ->
                calls += "admission"
                assertSame(trust, exactTrust)
                admission
            },
            inputBuildPort = ProductionAndroidOfflineResumeInputBuildPort { exactMaterial, exactPolicy, exactAdmission ->
                calls += "build"
                assertSame(fixture.material, exactMaterial)
                assertSame(fixture.policy, exactPolicy)
                assertSame(admission, exactAdmission)
                input
            }
        )

        assertEquals(listOf("trust", "admission", "build"), calls)
        assertSame(
            input,
            assertIs<ProductionAndroidOfflineResumeProductInputResult.Ready>(result).input
        )
    }

    private fun fixture(): Fixture {
        val material = allocateWithoutConstructor<ProductionAndroidOfflineResumeMaterial>()
        val policy = allocateWithoutConstructor<ProductionAndroidOfflineResumeProcessPolicy>()

        // The real preflight uses nested durable/policy fields. For these orchestration contracts,
        // bypass that structural check by allocating field-bearing shells and setting only the
        // identity values through Unsafe-backed objects.
        installFixtureFields(material, policy)
        return Fixture(material, policy)
    }

    private fun installFixtureFields(
        material: ProductionAndroidOfflineResumeMaterial,
        policy: ProductionAndroidOfflineResumeProcessPolicy
    ) {
        val deployment = allocateWithoutConstructor<ProductionAndroidOfflineDeploymentProfile>()
        val metadata = allocateWithoutConstructor<ProductionAndroidOfflineResumeMetadata>()
        val prepared = allocateWithoutConstructor<
            pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
        >()
        val admissionPolicy = ProductionAndroidOfflineResumeAdmissionPolicy(
            feature = "chat",
            principal = "principal",
            capability = "model.local",
            authorityScope = "global"
        )

        setObjectField(material, "deploymentProfile", deployment)
        setObjectField(material, "resumeMetadata", metadata)
        setObjectField(material, "localModelFile", File("model.lpm1"))
        setObjectField(deployment, "semanticDirectoryName", "semantic")
        setObjectField(deployment, "cognitiveStorageDirectoryName", "cognitive")
        setObjectField(metadata, "semanticDirectoryName", "semantic")
        setObjectField(metadata, "cognitiveStorageDirectoryName", "cognitive")
        setObjectField(policy, "admission", admissionPolicy)
        setObjectField(policy, "preparedInputOwners", prepared)

        val principal = pro.liliya.core.authority.AuthorityPrincipal("principal")
        setObjectField(prepared, "principal", principal)
    }

    private data class Fixture(
        val material: ProductionAndroidOfflineResumeMaterial,
        val policy: ProductionAndroidOfflineResumeProcessPolicy
    )

    private fun setObjectField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        val unsafe = unsafe()
        unsafe.putObject(target, unsafe.objectFieldOffset(field), value)
    }

    private inline fun <reified T> allocateWithoutConstructor(): T =
        unsafe().allocateInstance(T::class.java) as T

    private fun unsafe(): Unsafe {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null) as Unsafe
    }
}
