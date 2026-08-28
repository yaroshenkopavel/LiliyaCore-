package pro.liliya.core.modules

import pro.liliya.core.services.CoreService

interface CoreModule {
    val descriptor: ModuleDescriptor
    val services: Collection<CoreService>
}
