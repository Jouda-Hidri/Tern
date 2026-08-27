package tern.artic

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import tern.tracing.RequestIdClientInterceptor

/**
 * The channel and the web client are beans rather than fields of [ArticService], so tests can
 * point the service at an in-process server or a stub without going near the network.
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

    @Bean
    fun tapiClient(@Value("\${tern.tapi.url}") tapiUrl: String): WebClient = WebClient.create(tapiUrl)
}
