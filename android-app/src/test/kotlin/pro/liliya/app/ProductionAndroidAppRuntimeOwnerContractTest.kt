package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.ProductChatFailure
import pro.liliya.android.runtime.ProductChatResult

class ProductionAndroidAppRuntimeOwnerContractTest {
    @After
    fun cleanup() {
        ProductionAndroidAppTrustedWiring.clearForTests()
    }

    @Test
    fun missing_trusted_wiring_stays_configuration_required() {
        val owner = ProductionAndroidAppRuntimeOwner()

        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            owner.start(null)
        )
        assertEquals(
            ProductChatFailure.HEART_NOT_READY,
            (owner.send("hello") as ProductChatResult.Rejected).reason
        )
    }

    @Test
    fun exact_ready_session_is_retained_and_start_is_not_repeated() {
        val session = FakeSession()
        var starts = 0
        val port = ProductionAndroidAppRuntimeStartPort {
            starts += 1
            ProductionAndroidAppRuntimeStartResult.Ready(session)
        }
        val owner = ProductionAndroidAppRuntimeOwner()

        assertEquals(ProductionAndroidAppRuntimeState.READY, owner.start(port))
        assertEquals(ProductionAndroidAppRuntimeState.READY, owner.start(port))
        assertEquals(1, starts)
        assertEquals("reply:hello", (owner.send("hello") as ProductChatResult.Completed).reply)
    }

    @Test
    fun rejection_is_terminal_and_does_not_auto_retry() {
        var starts = 0
        val port = ProductionAndroidAppRuntimeStartPort {
            starts += 1
            ProductionAndroidAppRuntimeStartResult.Rejected(
                ProductionAndroidAppRuntimeFailure.BOOTSTRAP_REJECTED
            )
        }
        val owner = ProductionAndroidAppRuntimeOwner()

        assertEquals(ProductionAndroidAppRuntimeState.FAILED, owner.start(port))
        assertEquals(ProductionAndroidAppRuntimeState.FAILED, owner.start(port))
        assertEquals(1, starts)
        assertEquals(ProductionAndroidAppRuntimeFailure.BOOTSTRAP_REJECTED, owner.failure())
    }

    @Test
    fun close_releases_exact_session_once_and_is_idempotent() {
        val session = FakeSession()
        val owner = ProductionAndroidAppRuntimeOwner()
        owner.start(
            ProductionAndroidAppRuntimeStartPort {
                ProductionAndroidAppRuntimeStartResult.Ready(session)
            }
        )

        owner.close()
        owner.close()

        assertEquals(1, session.closeCalls)
        assertEquals(ProductionAndroidAppRuntimeState.CLOSED, owner.state())
    }

    @Test
    fun trusted_wiring_is_install_once() {
        val first = ProductionAndroidAppRuntimeStartPort {
            ProductionAndroidAppRuntimeStartResult.Rejected(
                ProductionAndroidAppRuntimeFailure.BOOTSTRAP_REJECTED
            )
        }
        val second = ProductionAndroidAppRuntimeStartPort {
            ProductionAndroidAppRuntimeStartResult.Rejected(
                ProductionAndroidAppRuntimeFailure.INTERNAL_FAILURE
            )
        }

        assertTrue(ProductionAndroidAppTrustedWiring.install(first))
        assertFalse(ProductionAndroidAppTrustedWiring.install(second))
        assertTrue(ProductionAndroidAppTrustedWiring.current() === first)
    }

    private class FakeSession : ProductionAndroidAppRuntimeSession {
        var closeCalls: Int = 0
            private set

        override fun send(text: String): ProductChatResult =
            ProductChatResult.Completed(
                reply = "reply:$text",
                streamedChunkCount = 0,
                streamedCharacterCount = 0
            )

        override fun close() {
            closeCalls += 1
        }
    }
}
