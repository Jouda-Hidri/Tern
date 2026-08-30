package tern

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
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
import tern.artic.MessageRequest
import tern.artic.MessageResponse
import tern.artic.MessageStats
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EndToEndTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @BeforeEach
    fun warmUp() {
        proxy.pass()
        repeat(2) { runCatching { post("warm up") } }
    }

    @Test
    @Order(1)
    fun `a message is stored and listed back when everything is up`() {
        val created = post("End to end, everything up")

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(listed()).contains("End to end, everything up")
    }

    @Test
    @Order(2)
    fun `an unreachable detector does not stop a message being stored, it just stays unknown`() {
        val created = post("The detector is unreachable here")

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val stats = rest.getForEntity("/stats", MessageStats::class.java)
        assertThat(stats.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(stats.body?.byLanguage).containsKey("unknown")
        assertThat(stats.body?.byLanguage?.keys?.last()).isEqualTo("unknown")
        assertThat(stats.body?.byLanguage?.values?.sum()).isEqualTo(stats.body?.total)
    }

    @Test
    @Order(3)
    fun `while antarctic hangs, POST is accepted unconfirmed and GET reports a gateway timeout`() {
        proxy.blackhole()

        await().atMost(TRANSITION).until { post("Sent while antarctic hangs").statusCode == HttpStatus.ACCEPTED }

        val listed = rest.getForEntity("/", String::class.java)
        assertThat(listed.statusCode).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        assertThat(listed.body).contains("deadline exceeded")
    }

    @Test
    @Order(4)
    fun `once antarctic refuses connections, both POST and GET fail fast as unavailable`() {
        proxy.refuse()

        await().atMost(TRANSITION).until { post("Sent while antarctic refuses").statusCode == HttpStatus.SERVICE_UNAVAILABLE }

        val listed = rest.getForEntity("/", String::class.java)
        assertThat(listed.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(listed.body).contains("unavailable")
    }

    @Test
    @Order(5)
    fun `readiness stays up while antarctic is unreachable`() {
        proxy.blackhole()
        assertThat(readiness()).isEqualTo(HttpStatus.OK)

        proxy.refuse()
        assertThat(readiness()).isEqualTo(HttpStatus.OK)
    }

    private fun post(text: String) =
        rest.postForEntity(
            "/",
            HttpEntity(MessageRequest(text), HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            String::class.java,
        )

    private fun listed() =
        rest.exchange<List<MessageResponse>>("/", HttpMethod.GET, null).body.orEmpty().map { it.text }

    private fun readiness() =
        rest.getForEntity("/actuator/health/readiness", String::class.java).statusCode

    companion object {
        private const val GRPC_TEST_PORT = 9098

        private val TRANSITION: Duration = Duration.ofSeconds(30)

        @JvmStatic
        private val proxy = ToggleProxy(GRPC_TEST_PORT).apply { start() }

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
            registry.add("tern.antarctic.target") { "localhost:${proxy.port}" }
            registry.add("tern.antarctic.deadline") { "1500ms" }
            registry.add("tern.translate.url") { "http://localhost:1" }
            registry.add("tern.translate.timeout") { "200ms" }
        }
    }
}

private class ToggleProxy(private val upstreamPort: Int) {

    @Volatile
    private var silent = false

    @Volatile
    private var server: ServerSocket? = null
    private val connections = CopyOnWriteArrayList<Socket>()

    val port: Int = ServerSocket(0).use { it.localPort }

    fun start() {
        listen()
    }

    fun pass() {
        silent = false
        if (server == null) listen()
        dropOpenConnections()
    }

    fun blackhole() {
        silent = true
        if (server == null) listen()
        dropOpenConnections()
    }

    fun refuse() {
        server?.let { runCatching { it.close() } }
        server = null
        dropOpenConnections()
    }

    private fun listen() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        }
        server = socket
        thread(isDaemon = true, name = "toggle-proxy-accept") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    return@thread
                }
                connections += client
                if (silent) continue
                forward(client)
            }
        }
    }

    private fun forward(client: Socket) {
        val upstream = try {
            Socket(InetAddress.getLoopbackAddress(), upstreamPort)
        } catch (e: IOException) {
            runCatching { client.close() }
            return
        }
        connections += upstream
        pump(client, upstream)
        pump(upstream, client)
    }

    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true, name = "toggle-proxy-pump") {
        runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
        runCatching { to.close() }
        runCatching { from.close() }
    }

    private fun dropOpenConnections() {
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }
}
