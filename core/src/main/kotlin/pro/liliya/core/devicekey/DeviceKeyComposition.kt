package pro.liliya.core.devicekey

import pro.liliya.core.foundation.FoundationComposition

interface DeviceKeyOwnership {
    val state: DeviceKeyState
    val generation: DeviceKeyGeneration
    fun remove(): Boolean
}

sealed interface DeviceKeyRegisterResult {
    data class Registered(val ownership: DeviceKeyOwnership) : DeviceKeyRegisterResult
    data class Rejected(val reason: String) : DeviceKeyRegisterResult
}

class DeviceKeyComposition(
    private val foundation: FoundationComposition
) {
    private val store = DeviceKeyStore(foundation.observability)

    fun register(state: DeviceKeyState): DeviceKeyRegisterResult {
        val context = foundation.rootContext(
            operation = "registerDeviceKeyState",
            component = "DeviceKey",
            metadata = metadata(state)
        )
        return when (val result = store.register(state, context)) {
            is DeviceKeyRegistrationResult.Registered -> {
                val registration = result.registration
                DeviceKeyRegisterResult.Registered(
                    ownership = object : DeviceKeyOwnership {
                        override val state: DeviceKeyState = registration.state
                        override val generation: DeviceKeyGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = context,
                                component = "DeviceKey",
                                operation = "removeDeviceKeyState",
                                metadata = mapOf(
                                    "deviceKeyGeneration" to generation.value.toString()
                                )
                            )
                        )
                    }
                )
            }

            is DeviceKeyRegistrationResult.Rejected ->
                DeviceKeyRegisterResult.Rejected(result.reason)
        }
    }

    fun find(id: DeviceKeyId): DeviceKeyState? = store.find(id)

    fun inspect(id: DeviceKeyId): DeviceKeySnapshot? = store.inspect(id)

    fun contains(id: DeviceKeyId): Boolean = store.contains(id)

    fun snapshot(): List<DeviceKeyState> = store.snapshot()

    fun snapshotEntries(): List<DeviceKeySnapshot> = store.snapshotEntries()

    private fun metadata(state: DeviceKeyState): Map<String, String> = mapOf(
        "deviceKeyId" to state.id.value,
        "deviceKeyAlgorithm" to state.algorithm.value,
        "deviceKeySecurityLevel" to state.securityLevel.name.lowercase(),
        "deviceKeyCapabilityCount" to state.capabilities.size.toString(),
        "createdAt" to state.createdAt.toString()
    )
}
