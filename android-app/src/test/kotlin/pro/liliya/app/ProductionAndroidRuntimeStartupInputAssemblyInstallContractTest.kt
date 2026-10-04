package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityOwnership
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputAssemblyResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputOwnership
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership
import pro.liliya.core.authority.CapabilityAuthorityComposition
import sun.misc.Unsafe

class ProductionAndroidRuntimeStartupInputAssemblyInstallContractTest {
    @After
    fun cleanup() {
        ProductionAndroidRuntimeStartupInputAssemblyInstall.clearForTests()
        ProductionAndroidRuntimeStartupInputConfiguration.clearForTests()
    }

    @Test
    fun authority_rejection_is_preserved_without_installing_startup_input() {
        val result = ProductionAndroidRuntimeStartupInputAssemblyInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupInputAssemblyPort {
                AndroidProductRuntimeStartupInputAssemblyResult.AuthorityRejected(
                    AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED
                )
            }
        )

        val rejected = assertIs<ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AuthorityRejected>(
            result
        )
        assertEquals(
            AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED,
            rejected.reason
        )
        assertEquals(null, ProductionAndroidRuntimeStartupInputConfiguration.current())
    }

    @Test
    fun rejected_ready_commit_leaves_startup_uninstalled() {
        var commitCalls = 0
        val ownership = AndroidProductRuntimeStartupInputOwnership(
            sourceInput = allocateWithoutConstructor<AndroidProductRuntimeStartupRequestSourceInput>(),
            trust = allocateWithoutConstructor<AndroidProductRuntimeStartupTrustOwnership>(),
            authority = AndroidProductRuntimeStartupAuthorityOwnership(
                authority = allocateWithoutConstructor<CapabilityAuthorityComposition>(),
                capabilities = emptyList(),
                directGrants = emptyList()
            )
        )

        val result = ProductionAndroidRuntimeStartupInputAssemblyInstall.prepareAndInstall(
            assemblyPort = ProductionAndroidRuntimeStartupInputAssemblyPort {
                AndroidProductRuntimeStartupInputAssemblyResult.Ready(ownership)
            },
            readyCommit = ProductionAndroidRuntimeStartupReadyCommitPort {
                commitCalls += 1
                false
            }
        )

        assertIs<ProductionAndroidRuntimeStartupInputAssemblyInstallResult.DurableCommitRejected>(
            result
        )
        assertEquals(1, commitCalls)
        assertEquals(null, ProductionAndroidRuntimeStartupInputConfiguration.current())
    }

    @Test
    fun assembly_exception_is_bounded() {
        val result = ProductionAndroidRuntimeStartupInputAssemblyInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupInputAssemblyPort {
                error("private assembly failure")
            }
        )

        assertIs<ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Failed>(result)
        assertEquals(null, ProductionAndroidRuntimeStartupInputConfiguration.current())
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
