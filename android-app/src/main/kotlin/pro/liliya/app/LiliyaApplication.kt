package pro.liliya.app

import android.app.Application
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupCompositionRequest
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningPorts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputAssemblyInput

class LiliyaApplication : Application() {
    val runtimeOwner: ProductionAndroidAppRuntimeOwner = ProductionAndroidAppRuntimeOwner()
    private val startupCoordinator = ProductionAndroidRuntimeStartupCoordinator()

    @Volatile
    private var startupTask = ProductionAndroidAppStartupTask()

    @Volatile
    private var chatTask = ProductionAndroidAppChatTask()

    fun configureRuntime(sources: ProductionAndroidRuntimeWiringSources): Boolean =
        ProductionAndroidRuntimeConfiguration.install(sources)

    fun configureFirstRun(
        input: AndroidProductRuntimeFirstRunProductInput
    ): ProductionAndroidFirstRunProductInstallResult =
        ProductionAndroidFirstRunProductInstall.prepareAndInstall(input)

    fun provisionRuntime(
        ports: AndroidProductRuntimeStartupProvisioningPorts
    ): ProductionAndroidRuntimeStartupInstallResult =
        ProductionAndroidRuntimeStartupInstall.prepareAndInstall(ports)

    fun provisionRuntime(
        request: AndroidProductRuntimeStartupCompositionRequest
    ): ProductionAndroidRuntimeStartupInstallResult =
        ProductionAndroidRuntimeStartupCompositionInstall.prepareAndInstall(request)

    fun provisionRuntime(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): ProductionAndroidRuntimeStartupSourceInstallResult =
        ProductionAndroidRuntimeStartupSourceInstall.prepareAndInstall(input)

    fun configureStartup(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): Boolean = ProductionAndroidRuntimeStartupInputConfiguration.install(input)

    fun configureStartup(
        input: AndroidProductRuntimeStartupInputAssemblyInput
    ): ProductionAndroidRuntimeStartupInputAssemblyInstallResult =
        ProductionAndroidRuntimeStartupInputAssemblyInstall.prepareAndInstall(input)

    internal fun startApplicationRuntimeAsync(
        callback: (ProductionAndroidAppStartupTaskResult) -> Unit
    ) {
        startupTask.request(
            startup = { startApplicationRuntime() },
            callback = callback
        )
    }

    internal fun requestApplicationChat(
        message: String,
        listener: (ProductionAndroidAppChatTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidAppChatTaskRequestResult =
        chatTask.request(
            message = message,
            send = runtimeOwner::send,
            listener = listener
        )

    internal fun observeApplicationChat(
        listener: (ProductionAndroidAppChatTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidAppChatTaskSnapshot = chatTask.observe(listener)

    internal fun consumeApplicationChat(requestId: Long): Boolean =
        chatTask.consume(requestId)

    @Synchronized
    internal fun replaceStartupTaskForTests(
        replacement: ProductionAndroidAppStartupTask
    ): ProductionAndroidAppStartupTask {
        val previous = startupTask
        startupTask = replacement
        return previous
    }

    @Synchronized
    internal fun replaceChatTaskForTests(
        replacement: ProductionAndroidAppChatTask
    ): ProductionAndroidAppChatTask {
        val previous = chatTask
        chatTask = replacement
        return previous
    }

    fun startApplicationRuntime(): ProductionAndroidAppStartupOutcome {
        val input = ProductionAndroidRuntimeStartupInputConfiguration.current()
        return startupCoordinator.start(
            hasStartupInput = input != null,
            configuredPort = ProductionAndroidRuntimeConfiguredPort {
                ProductionAndroidRuntimeConfiguration.current() != null ||
                    ProductionAndroidAppTrustedWiring.current() != null
            },
            provisionPort = ProductionAndroidRuntimeStartupProvisionPort {
                val exact = input
                if (exact == null) {
                    ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(
                        pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE
                    )
                } else {
                    ProductionAndroidRuntimeStartupSourceInstall.prepareAndInstall(exact)
                }
            },
            runtimeStartPort = ProductionAndroidRuntimeStartStatePort { startRuntime() }
        )
    }

    fun startRuntime(): ProductionAndroidAppRuntimeState {
        ProductionAndroidRuntimeConfigurationInstaller.ensureTrustedWiringInstalled()
        return runtimeOwner.start(ProductionAndroidAppTrustedWiring.current())
    }

    override fun onTerminate() {
        runtimeOwner.close()
        super.onTerminate()
    }
}
