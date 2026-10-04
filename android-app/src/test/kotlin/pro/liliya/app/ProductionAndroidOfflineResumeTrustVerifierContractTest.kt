package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustAssemblyResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustInputFactoryResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership
import sun.misc.Unsafe

class ProductionAndroidOfflineResumeTrustVerifierContractTest {
    @Test
    fun rejected_trust_input_stops_before_verification_assembly() {
        val result = ProductionAndroidOfflineResumeTrustVerifier.resolve(
            inputResult = AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected,
            assemblyPort = ProductionAndroidOfflineResumeTrustAssemblyPort {
                error("verification assembly must not run after trust input rejection")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeTrustFailure.TRUST_INPUT_REJECTED,
            assertIs<ProductionAndroidOfflineResumeTrustResult.Rejected>(result).reason
        )
    }

    @Test
    fun failed_verification_assembly_fails_closed() {
        val input = allocateWithoutConstructor<AndroidProductRuntimeStartupTrustInput>()

        val result = ProductionAndroidOfflineResumeTrustVerifier.resolve(
            inputResult = AndroidProductRuntimeStartupTrustInputFactoryResult.Ready(input),
            assemblyPort = ProductionAndroidOfflineResumeTrustAssemblyPort {
                AndroidProductRuntimeStartupTrustAssemblyResult.Failed
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeTrustFailure.FAILED,
            assertIs<ProductionAndroidOfflineResumeTrustResult.Rejected>(result).reason
        )
    }

    @Test
    fun verified_trust_returns_only_fresh_trust_ownership() {
        val input = allocateWithoutConstructor<AndroidProductRuntimeStartupTrustInput>()
        val ownership = allocateWithoutConstructor<AndroidProductRuntimeStartupTrustOwnership>()

        val result = ProductionAndroidOfflineResumeTrustVerifier.resolve(
            inputResult = AndroidProductRuntimeStartupTrustInputFactoryResult.Ready(input),
            assemblyPort = ProductionAndroidOfflineResumeTrustAssemblyPort {
                AndroidProductRuntimeStartupTrustAssemblyResult.Ready(ownership)
            }
        )

        assertSame(
            ownership,
            assertIs<ProductionAndroidOfflineResumeTrustResult.Ready>(result).ownership
        )
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
