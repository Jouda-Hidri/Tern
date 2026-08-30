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
    fun translateClient(
        @Value("\${tern.translate.url:}") url: String,
        @Value("\${tern.translate.timeout:1s}") timeout: Duration,
        @Value("\${tern.antarctic.deadline:2s}") deadline: Duration,
    ): WebClient {
        check(timeout < deadline) {
            "tern.translate.timeout ($timeout) must be shorter than tern.antarctic.deadline " +
                "($deadline), or a slow detector loses messages instead of delaying them"
        }
        return client(url, timeout)
    }

    private fun client(url: String, timeout: Duration): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.toMillis().toInt())
            .responseTimeout(timeout)
        return WebClient.builder()
            .baseUrl(url.ifBlank { "http://detector-not-configured" })
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
