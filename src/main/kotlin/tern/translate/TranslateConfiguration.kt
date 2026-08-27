package tern.translate

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * Settings for the third-party detector. Works against a self-hosted LibreTranslate (what
 * docker-compose runs) or the hosted one at libretranslate.com, which needs `api-key`.
 */
@ConfigurationProperties(prefix = "tern.translate")
@ConstructorBinding
data class TranslateProperties(
    val url: String,
    val apiKey: String = "",
    /** Kept short: this sits on the POST path, so a slow third party must not stall callers. */
    val timeout: Duration = Duration.ofSeconds(2),
    val connectTimeout: Duration = Duration.ofSeconds(1),
)

@Configuration
@EnableConfigurationProperties(TranslateProperties::class)
class TranslateConfiguration {

    /**
     * A dedicated client rather than a shared one: timeouts belong to the service being
     * called, and `WebClient.create(url)` has none at all - a hung third party would pin a
     * thread indefinitely.
     */
    @Bean
    fun translateClient(properties: TranslateProperties): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeout.toMillis().toInt())
            .responseTimeout(properties.timeout)

        return WebClient.builder()
            .baseUrl(properties.url)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    /** The caching decorator wraps the real one; nothing downstream knows the difference. */
    @Bean
    fun languageDetector(translateClient: WebClient, properties: TranslateProperties): LanguageDetector =
        CachingLanguageDetector(LibreTranslateDetector(translateClient, properties))
}
