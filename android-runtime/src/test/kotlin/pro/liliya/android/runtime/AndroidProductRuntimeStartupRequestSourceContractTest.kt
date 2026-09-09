package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageOpenResult

class AndroidProductRuntimeStartupRequestSourceContractTest {
    @Test
    fun corrupt_storage_fails_closed_before_request_build() {
        var buildCalls = 0

        val result = AndroidProductRuntimeStartupRequestSource.create(
            storageOpen = AndroidProductRuntimeStartupStorageOpenPort {
                AndroidCognitiveStorageOpenResult.Corrupt
            },
            requestBuild = AndroidProductRuntimeStartupRequestBuildPort {
                buildCalls += 1
                error("must not build request")
            }
        )

        val rejected = assertIs<AndroidProductRuntimeStartupRequestSourceResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeStartupRequestSourceFailure.COGNITIVE_STORAGE_CORRUPT,
            rejected.reason
        )
        assertEquals(0, buildCalls)
    }

    @Test
    fun storage_exception_is_bounded_before_request_build() {
        var buildCalls = 0

        val result = AndroidProductRuntimeStartupRequestSource.create(
            storageOpen = AndroidProductRuntimeStartupStorageOpenPort {
                error("private storage failure")
            },
            requestBuild = AndroidProductRuntimeStartupRequestBuildPort {
                buildCalls += 1
                error("must not build request")
            }
        )

        val rejected = assertIs<AndroidProductRuntimeStartupRequestSourceResult.Rejected>(result)
        assertEquals(AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE, rejected.reason)
        assertEquals(0, buildCalls)
    }
}
