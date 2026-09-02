package pro.liliya.android.protectedmodel.enginesource

import pro.liliya.android.protectedmodel.staging.AndroidAppPrivateProtectedModelStagingBackend
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelPhysicalEngineLoaderPort
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.StagedModelEngineLoaderPort
import pro.liliya.core.protectedmodel.LargeProtectedModelEngineSourceCapability

/**
 * Android platform adapter for the Core staged-source loader seam.
 *
 * This class does not resolve opaque ids itself. It delegates exact physical ownership
 * validation to the same app-private staging backend instance that created the artifact.
 */
class AndroidAppPrivateStagedModelEngineLoader(
    private val stagingBackend: AndroidAppPrivateProtectedModelStagingBackend,
    private val physicalLoader: AndroidProtectedModelPhysicalEngineLoaderPort
) : StagedModelEngineLoaderPort {

    override fun load(
        source: LargeProtectedModelEngineSourceCapability
    ): ModelEngineLoadResult = stagingBackend.loadEngineSource(source, physicalLoader)
}
