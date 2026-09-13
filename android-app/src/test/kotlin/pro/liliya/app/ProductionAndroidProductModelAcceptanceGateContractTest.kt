package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class ProductionAndroidProductModelAcceptanceGateContractTest {
    private val candidate = ProductionAndroidProductModelCandidate(
        modelId = "qwen3-1.7b-q4_k_m-candidate",
        artifactSha256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5",
        quantization = "Q4_K_M"
    )

    @Test
    fun every_missing_acceptance_domain_rejects_candidate_fail_closed() {
        val accepted = evidence()
        val cases = listOf(
            accepted.copy(provenanceAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.PROVENANCE_NOT_ACCEPTED,
            accepted.copy(licenseAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.LICENSE_NOT_ACCEPTED,
            accepted.copy(russianQualityAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.RUSSIAN_QUALITY_NOT_ACCEPTED,
            accepted.copy(physicalArm64ExecutionAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.PHYSICAL_ARM64_NOT_ACCEPTED,
            accepted.copy(peakMemoryAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.PEAK_MEMORY_NOT_ACCEPTED,
            accepted.copy(latencyAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.LATENCY_NOT_ACCEPTED,
            accepted.copy(contextAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.CONTEXT_NOT_ACCEPTED,
            accepted.copy(packageSizeAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.PACKAGE_SIZE_NOT_ACCEPTED,
            accepted.copy(thermalAndBatteryAccepted = false) to ProductionAndroidProductModelAcceptanceRejection.THERMAL_AND_BATTERY_NOT_ACCEPTED
        )

        cases.forEach { (evidence, expected) ->
            val rejected = assertIs<ProductionAndroidProductModelAcceptanceResult.Rejected>(
                ProductionAndroidProductModelAcceptanceGate.evaluate(candidate, evidence)
            )
            assertEquals(candidate, rejected.candidate)
            assertEquals(expected, rejected.reason)
        }
    }

    @Test
    fun complete_explicit_evidence_preserves_exact_candidate_identity() {
        val ready = assertIs<ProductionAndroidProductModelAcceptanceResult.Ready>(
            ProductionAndroidProductModelAcceptanceGate.evaluate(candidate, evidence())
        )
        assertEquals(candidate, ready.candidate)
    }

    private fun evidence() = ProductionAndroidProductModelAcceptanceEvidence(
        provenanceAccepted = true,
        licenseAccepted = true,
        russianQualityAccepted = true,
        physicalArm64ExecutionAccepted = true,
        peakMemoryAccepted = true,
        latencyAccepted = true,
        contextAccepted = true,
        packageSizeAccepted = true,
        thermalAndBatteryAccepted = true
    )
}
