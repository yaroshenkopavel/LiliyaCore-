package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.license.LicenseDenialReason

class AndroidProductRuntimeAdmissionGateContractTest {

    @Test
    fun license_denial_remains_distinct() {
        val result = AndroidProductRuntimeAdmissionGate.admit(
            AndroidProductRuntimeAdmissionDecisionPort {
                AndroidProductRuntimeAdmissionDecision.LicenseDenied(
                    LicenseDenialReason.FEATURE_NOT_ENTITLED
                )
            }
        )

        val denied = assertIs<AndroidProductRuntimeAdmissionResult.LicenseDenied>(result)
        assertEquals(LicenseDenialReason.FEATURE_NOT_ENTITLED, denied.reason)
    }

    @Test
    fun authority_denial_remains_distinct() {
        val result = AndroidProductRuntimeAdmissionGate.admit(
            AndroidProductRuntimeAdmissionDecisionPort {
                AndroidProductRuntimeAdmissionDecision.AuthorityDenied
            }
        )

        val denied = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED,
            denied.reason
        )
    }

    @Test
    fun authorized_decision_returns_opaque_admission_ownership() {
        val result = AndroidProductRuntimeAdmissionGate.admit(
            AndroidProductRuntimeAdmissionDecisionPort {
                AndroidProductRuntimeAdmissionDecision.Authorized
            }
        )

        val admitted = assertIs<AndroidProductRuntimeAdmissionResult.Admitted>(result)
        assertEquals(
            "AndroidProductRuntimeAdmissionResult.Admitted(ownership=<redacted>)",
            admitted.toString()
        )
        assertEquals(
            "AndroidProductRuntimeAdmissionOwnership(<redacted>)",
            admitted.ownership.toString()
        )
    }

    @Test
    fun unexpected_exception_is_bounded_without_private_text() {
        val result = AndroidProductRuntimeAdmissionGate.admit(
            AndroidProductRuntimeAdmissionDecisionPort {
                error("PRIVATE-LICENSE-AUTHORITY-FAILURE")
            }
        )

        val rejected = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeAdmissionFailure.INTERNAL_FAILURE,
            rejected.reason
        )
        assertEquals(
            "Rejected(reason=INTERNAL_FAILURE)",
            rejected.toString()
        )
    }

    @Test
    fun canonical_host_bootstrap_has_no_public_start_without_admission() {
        val publicStarts = AndroidProductRuntimeHostBootstrap::class.java.methods
            .filter { it.name == "start" && it.declaringClass == AndroidProductRuntimeHostBootstrap::class.java }

        assertTrue(
            publicStarts.any { method ->
                method.parameterTypes.contentEquals(
                    arrayOf(
                        AndroidProductRuntimeHostPreparedInputs::class.java,
                        AndroidProductRuntimeAdmissionResult.Admitted::class.java
                    )
                )
            }
        )
        assertTrue(
            publicStarts.none { method ->
                method.parameterTypes.contentEquals(
                    arrayOf(AndroidProductRuntimeHostPreparedInputs::class.java)
                )
            }
        )
    }
}
