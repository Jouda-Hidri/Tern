package tern.artic

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tern.tracing.RequestIdClientInterceptor

/**
 * The channel is a bean rather than a field of [ArticService], so tests can point the service
 * at an in-process server without going near the network.
 */
@Configuration
class AntarcticClientConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    fun antarcticChannel(
        @Value("\${tern.antarctic.target}") target: String,
        requestIdClientInterceptor: RequestIdClientInterceptor,
    ): ManagedChannel =
        ManagedChannelBuilder.forTarget(target)
            .usePlaintext()
            .intercept(requestIdClientInterceptor)
            .build()
}
