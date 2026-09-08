package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test
import pro.liliya.core.license.LicenseDenialReason

class AndroidProductRuntimeHostSessionContractTest {

    @Test
    fun admitted_revalidation_retains_the_exact_live_runtime() {
        val bridge = FakeSessionBridge()
        val session = AndroidProductRuntimeHostSession(bridge)

        val result = session.revalidate(
            AndroidProductRuntimeAdmissionResult.Admitted(
                AndroidProductRuntimeAdmissionOwnership()
            )
        )

        assertEquals(AndroidProductRuntimeAdmissionRevalidationResult.Retained, result)
        assertEquals(0, bridge.closeCalls)
        assertEquals(HeartRuntimeState.READY, session.state())
    }

    @Test
    fun license_denial_closes_exact_runtime_once_and_makes_session_inactive() {
        val bridge = FakeSessionBridge()
        val session = AndroidProductRuntimeHostSession(bridge)

        val result = session.revalidate(
            AndroidProductRuntimeAdmissionResult.LicenseDenied(
                LicenseDenialReason.EXPIRED
            )
        )

        val closed = assertIs<AndroidProductRuntimeAdmissionRevalidationResult.Closed>(result)
        assertEquals(
            AndroidProductRuntimeAdmissionRevalidationFailure.LICENSE_DENIED,
            closed.reason
        )
        assertEquals(HeartRuntimeCloseResult.Closed, closed.cleanup)
        assertEquals(1, bridge.closeCalls)
        assertNull(session.chat())
        assertNull(session.learningFollowUp())
        assertEquals(
            AndroidProductRuntimeAdmissionRevalidationResult.Inactive,
            session.revalidate(
                AndroidProductRuntimeAdmissionResult.Admitted(
                    AndroidProductRuntimeAdmissionOwnership()
                )
            )
        )
        assertEquals(1, bridge.closeCalls)
    }

    @Test
    fun authority_denial_closes_exact_runtime_once() {
        val bridge = FakeSessionBridge()
        val session = AndroidProductRuntimeHostSession(bridge)

        val result = session.revalidate(
            AndroidProductRuntimeAdmissionResult.Rejected(
                AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
            )
        )

        val closed = assertIs<AndroidProductRuntimeAdmissionRevalidationResult.Closed>(result)
        assertEquals(
            AndroidProductRuntimeAdmissionRevalidationFailure.AUTHORITY_DENIED,
            closed.reason
        )
        assertEquals(1, bridge.closeCalls)
    }

    @Test
    fun admission_internal_failure_closes_exact_runtime_once() {
        val bridge = FakeSessionBridge()
        val session = AndroidProductRuntimeHostSession(bridge)

        val result = session.revalidate(
            AndroidProductRuntimeAdmissionResult.Rejected(
                AndroidProductRuntimeAdmissionFailure.INTERNAL_FAILURE
            )
        )

        val closed = assertIs<AndroidProductRuntimeAdmissionRevalidationResult.Closed>(result)
        assertEquals(
            AndroidProductRuntimeAdmissionRevalidationFailure.ADMISSION_INTERNAL_FAILURE,
            closed.reason
        )
        assertEquals(1, bridge.closeCalls)
    }

    @Test
    fun cleanup_exception_is_bounded_and_session_stays_inactive() {
        val bridge = FakeSessionBridge(closeThrows = true)
        val session = AndroidProductRuntimeHostSession(bridge)

        val result = session.revalidate(
            AndroidProductRuntimeAdmissionResult.LicenseDenied(
                LicenseDenialReason.EXPIRED
            )
        )

        val closed = assertIs<AndroidProductRuntimeAdmissionRevalidationResult.Closed>(result)
        assertEquals(null, closed.cleanup)
        assertEquals(1, bridge.closeCalls)
        assertEquals(
            AndroidProductRuntimeAdmissionRevalidationResult.Inactive,
            session.revalidate(
                AndroidProductRuntimeAdmissionResult.Admitted(
                    AndroidProductRuntimeAdmissionOwnership()
                )
            )
        )
    }

    private class FakeSessionBridge(
        private val closeThrows: Boolean = false
    ) : AndroidProductRuntimeHostSessionBridge {
        var closeCalls: Int = 0
            private set

        override fun state(): HeartRuntimeState = HeartRuntimeState.READY

        override fun chat(): ProductChatHost? = null

        override fun conversation(
            maxRetainedMessages: Int,
            maxRetainedCharacters: Int,
            maxMessageCharacters: Int
        ): ProductConversationHost? = null

        override fun learningFollowUp(): ProductLearningFollowUpHost? = null

        override fun recoverSemantic(): AndroidProductRuntimeSemanticRecoveryResult =
            AndroidProductRuntimeSemanticRecoveryResult.NotRequired

        override fun close(): HeartRuntimeCloseResult {
            closeCalls += 1
            if (closeThrows) {
                error("PRIVATE-SESSION-CLOSE-FAILURE")
            }
            return HeartRuntimeCloseResult.Closed
        }
    }
}
