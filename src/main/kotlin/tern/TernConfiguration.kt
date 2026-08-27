package tern

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import tern.tracing.RequestIdClientInterceptor
import java.time.Duration

@Configuration
class TernConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    fun antarcticChannel(
        @Value("\${tern.antarctic.target}") target: String,
        requestIdClientInterceptor: RequestIdClientInterceptor,
    ): ManagedChannel = ManagedChannelBuilder.forTarget(target)
        .usePlaintext()
        .intercept(requestIdClientInterceptor)
        .build()

    @Bean
    fun translateClient(@Value("\${tern.translate.url}") url: String): WebClient =
        client(url, Duration.ofSeconds(2))

    // WebClient.create(url) has no timeouts at all, so a hung third party pins a thread.
    private fun client(url: String, timeout: Duration): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
            .responseTimeout(timeout)
        return WebClient.builder()
            .baseUrl(url)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
