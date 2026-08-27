package tern.tapi

import io.netty.channel.ChannelOption
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@ConfigurationProperties(prefix = "tern.tapi")
@ConstructorBinding
data class TapiProperties(
    val url: String,
    /**
     * An idle-read timeout, not a cap on the whole download. reactor-netty applies this
     * between successive reads, which is what a large streamed CSV needs: a slow but
     * progressing transfer is fine, a stalled one is not.
     */
    val readTimeout: Duration = Duration.ofSeconds(10),
    val connectTimeout: Duration = Duration.ofSeconds(2),
)

@Configuration
@EnableConfigurationProperties(TapiProperties::class)
class TapiConfiguration {

    @Bean
    fun tapiClient(properties: TapiProperties): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeout.toMillis().toInt())
            .responseTimeout(properties.readTimeout)

        return WebClient.builder()
            .baseUrl(properties.url)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
