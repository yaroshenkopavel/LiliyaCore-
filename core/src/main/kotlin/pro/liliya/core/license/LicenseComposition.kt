package pro.liliya.core.license

import pro.liliya.core.foundation.FoundationComposition

interface LicenseOwnership {
    val entitlement: LicenseEntitlement
    val generation: LicenseGeneration
    fun remove(): Boolean
}

sealed interface LicenseRegisterResult {
    data class Registered(val ownership: LicenseOwnership) : LicenseRegisterResult
    data class Rejected(val reason: String) : LicenseRegisterResult
}

class LicenseComposition(
    private val foundation: FoundationComposition
) {
    private val store = LicenseStore(foundation.observability)

    fun register(entitlement: LicenseEntitlement): LicenseRegisterResult {
        val context = foundation.rootContext(
            operation = "registerLicense",
            component = "License",
            metadata = metadata(entitlement)
        )
        return when (val result = store.register(entitlement, context)) {
            is LicenseRegistrationResult.Registered -> {
                val registration = result.registration
                LicenseRegisterResult.Registered(
                    ownership = object : LicenseOwnership {
                        override val entitlement: LicenseEntitlement = registration.entitlement
                        override val generation: LicenseGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = context,
                                component = "License",
                                operation = "removeLicense",
                                metadata = mapOf("licenseGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LicenseRegistrationResult.Rejected -> LicenseRegisterResult.Rejected(result.reason)
        }
    }

    fun find(id: LicenseId): LicenseEntitlement? = store.find(id)

    fun inspect(id: LicenseId): LicenseSnapshot? = store.inspect(id)

    fun contains(id: LicenseId): Boolean = store.contains(id)

    fun snapshot(): List<LicenseEntitlement> = store.snapshot()

    fun snapshotEntries(): List<LicenseSnapshot> = store.snapshotEntries()

    private fun metadata(entitlement: LicenseEntitlement): Map<String, String> = mapOf(
        "licenseId" to entitlement.id.value,
        "licenseProductId" to entitlement.productId.value,
        "licenseVersion" to entitlement.version.value.toString(),
        "licenseSigningKeyId" to entitlement.signingKeyId.value,
        "licenseFeatureCount" to entitlement.features.size.toString()
    )
}
