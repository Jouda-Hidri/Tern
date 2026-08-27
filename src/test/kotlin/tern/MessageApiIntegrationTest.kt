package tern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tern.artic.ApiError
import tern.artic.MessageRequest
import tern.artic.MessageResponse

/**
 * The whole path in one test: HTTP into artic, gRPC over to antarctic, Flyway-migrated Postgres
 * in a container, and back. The application is both client and server, so a single instance
 * talking to itself covers both hops.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MessageApiIntegrationTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Test
    fun `a posted message comes back with the id the database assigned`() {
        val created = rest.postForEntity("/", json(MessageRequest("Hello!")), MessageResponse::class.java)

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(created.body?.text).isEqualTo("Hello!")
        assertThat(created.body?.id).isNotNull()

        val listed = rest.exchange<List<MessageResponse>>("/", HttpMethod.GET, null)

        assertThat(listed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(listed.body).extracting("text").contains("Hello!")
        assertThat(listed.body?.map { it.id }).doesNotContainNull()
    }

    @Test
    fun `every response carries the request id used in the logs`() {
        val response = rest.getForEntity("/", String::class.java)

        assertThat(response.headers.getFirst("X-Request-Id")).isNotBlank()
    }

    @Test
    fun `a caller-supplied request id is reused rather than replaced`() {
        val headers = HttpHeaders().apply { set("X-Request-Id", "deadbeef") }

        val response = rest.exchange<String>("/", HttpMethod.GET, HttpEntity<Void>(headers))

        assertThat(response.headers.getFirst("X-Request-Id")).isEqualTo("deadbeef")
    }

    @Test
    fun `a blank message is rejected with 400 and an error body naming the request id`() {
        val response = rest.postForEntity("/", json(MessageRequest("   ")), ApiError::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body?.message).contains("must not be blank")
        assertThat(response.body?.requestId).isNotBlank()
    }

    @Test
    fun `a malformed body is rejected with 400 rather than a bare 500`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

        val response = rest.postForEntity("/", HttpEntity("{\"nope\":1}", headers), ApiError::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body?.message).isNotBlank()
    }

    @Test
    fun `the probes kubernetes uses report the application as live and ready`() {
        val liveness = rest.getForEntity("/actuator/health/liveness", String::class.java)
        val readiness = rest.getForEntity("/actuator/health/readiness", String::class.java)

        assertThat(liveness.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(liveness.body).contains("\"status\":\"UP\"")
        assertThat(readiness.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(readiness.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun `health reports the antarctic hop separately from readiness`() {
        val health = rest.getForEntity("/actuator/health", String::class.java)

        assertThat(health.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(health.body).contains("\"antarctic\"")
        // Reported for observability, but never a reason to fail readiness.
        val readiness = rest.getForEntity("/actuator/health/readiness", String::class.java)
        assertThat(readiness.body).doesNotContain("antarctic")
    }

    private fun json(body: Any) =
        HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })

    companion object {
        /** Fixed so the client can be pointed at the server the same application starts. */
        private const val GRPC_TEST_PORT = 9099

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:14-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("grpc.server.port") { GRPC_TEST_PORT }
            registry.add("tern.antarctic.target") { "localhost:$GRPC_TEST_PORT" }
            // Nothing listens here; the tapi download must not affect any of these assertions.
            registry.add("tern.tapi.url") { "http://localhost:1" }
        }
    }
}
