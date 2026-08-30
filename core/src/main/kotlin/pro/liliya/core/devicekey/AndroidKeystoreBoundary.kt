package pro.liliya.core.devicekey

import java.time.Instant

/**
 * Platform-neutral orchestration boundary for an eventual Android Keystore implementation.
 *
 * The concrete Android implementation belongs in a platform module and must translate
 * Android Keystore facts into [AndroidKeystoreKeyDescriptor] without exporting private
 * key material.
 */
internal interface AndroidKeystorePlatform {
    fun generate(
        request: DeviceKeyCreationRequest,
        createdAt: Instant
    ): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor>

    fun inspect(id: DeviceKeyId): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor>

    fun delete(id: DeviceKeyId): AndroidKeystorePlatformResult<Unit>
}

internal data class AndroidKeystoreKeyDescriptor(
    val id: DeviceKeyId,
    val algorithm: DeviceKeyAlgorithm,
    val securityLevel: DeviceKeySecurityLevel,
    val capabilities: Set<DeviceKeyCapability>,
    val createdAt: Instant
) {
    init {
        require(capabilities.isNotEmpty()) { "android keystore key capabilities must not be empty" }
    }
}

internal sealed interface AndroidKeystorePlatformResult<out T> {
    data class Success<T>(val value: T) : AndroidKeystorePlatformResult<T>
    data class Rejected(val category: DeviceKeyFailureCategory) : AndroidKeystorePlatformResult<Nothing>
    data class Failed(
        val category: DeviceKeyFailureCategory,
        val throwable: Throwable? = null
    ) : AndroidKeystorePlatformResult<Nothing> {
        override fun toString(): String =
            "Failed(category=$category, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Implements the create/resolve/retire state machine around a future Android Keystore binding.
 *
 * A key becomes ready only after generation has succeeded, the exact generated identity has been
 * inspected, and its actual properties satisfy the requested profile. If post-generation
 * validation fails, cleanup is limited to the exact key ID that the caller requested; a hostile or
 * corrupted platform descriptor cannot redirect cleanup to another key identity.
 */
internal class AndroidKeystoreDeviceKeyAdapter(
    private val platform: AndroidKeystorePlatform
) : DeviceKeyAdapter {
    override fun create(
        request: DeviceKeyCreationRequest,
        createdAt: Instant
    ): DeviceKeyOperationResult<DeviceKeyState> {
        val generated = when (val result = platform.generate(request, createdAt)) {
            is AndroidKeystorePlatformResult.Success -> result.value
            is AndroidKeystorePlatformResult.Rejected ->
                return DeviceKeyOperationResult.Rejected(result.category)
            is AndroidKeystorePlatformResult.Failed ->
                return DeviceKeyOperationResult.Failed(result.category, result.throwable)
        }

        if (generated.id != request.id) {
            return rejectAfterCleanup(
                id = request.id,
                category = DeviceKeyFailureCategory.PLATFORM_REJECTED
            )
        }

        val inspected = when (val result = platform.inspect(request.id)) {
            is AndroidKeystorePlatformResult.Success -> result.value
            is AndroidKeystorePlatformResult.Rejected ->
                return rejectAfterCleanup(request.id, result.category)
            is AndroidKeystorePlatformResult.Failed ->
                return failAfterCleanup(request.id, result.category, result.throwable)
        }

        if (inspected.id != request.id) {
            return rejectAfterCleanup(
                id = request.id,
                category = DeviceKeyFailureCategory.PLATFORM_REJECTED
            )
        }

        if (
            !DeviceKeyProfilePolicy.accepts(
                requested = request.profile,
                actualSecurityLevel = inspected.securityLevel,
                actualAlgorithm = inspected.algorithm,
                actualCapabilities = inspected.capabilities
            )
        ) {
            return rejectAfterCleanup(
                id = request.id,
                category = DeviceKeyFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        }

        return DeviceKeyOperationResult.Success(inspected.toState())
    }

    override fun resolve(id: DeviceKeyId): DeviceKeyOperationResult<DeviceKeyState> =
        when (val result = platform.inspect(id)) {
            is AndroidKeystorePlatformResult.Success -> {
                if (result.value.id != id || result.value.securityLevel == DeviceKeySecurityLevel.UNKNOWN) {
                    DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.PLATFORM_REJECTED)
                } else {
                    DeviceKeyOperationResult.Success(result.value.toState())
                }
            }

            is AndroidKeystorePlatformResult.Rejected ->
                DeviceKeyOperationResult.Rejected(result.category)

            is AndroidKeystorePlatformResult.Failed ->
                DeviceKeyOperationResult.Failed(result.category, result.throwable)
        }

    override fun retire(id: DeviceKeyId): DeviceKeyOperationResult<Unit> =
        when (val result = platform.delete(id)) {
            is AndroidKeystorePlatformResult.Success -> DeviceKeyOperationResult.Success(Unit)
            is AndroidKeystorePlatformResult.Rejected -> DeviceKeyOperationResult.Rejected(result.category)
            is AndroidKeystorePlatformResult.Failed ->
                DeviceKeyOperationResult.Failed(result.category, result.throwable)
        }

    private fun rejectAfterCleanup(
        id: DeviceKeyId,
        category: DeviceKeyFailureCategory
    ): DeviceKeyOperationResult<Nothing> = when (platform.delete(id)) {
        is AndroidKeystorePlatformResult.Success -> DeviceKeyOperationResult.Rejected(category)
        is AndroidKeystorePlatformResult.Rejected,
        is AndroidKeystorePlatformResult.Failed ->
            DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.CLEANUP_FAILED)
    }

    private fun failAfterCleanup(
        id: DeviceKeyId,
        category: DeviceKeyFailureCategory,
        throwable: Throwable?
    ): DeviceKeyOperationResult<Nothing> = when (platform.delete(id)) {
        is AndroidKeystorePlatformResult.Success -> DeviceKeyOperationResult.Failed(category, throwable)
        is AndroidKeystorePlatformResult.Rejected,
        is AndroidKeystorePlatformResult.Failed ->
            DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.CLEANUP_FAILED)
    }

    private fun AndroidKeystoreKeyDescriptor.toState(): DeviceKeyState = DeviceKeyState(
        id = id,
        algorithm = algorithm,
        securityLevel = securityLevel,
        capabilities = capabilities.toSet(),
        createdAt = createdAt
    )
}
