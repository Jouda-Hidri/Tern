package tern.artic

import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Reports whether antarctic is answering, using the standard gRPC health service that net.devh
 * already registers on the other side.
 *
 * Deliberately *not* part of the readiness group: if antarctic goes down, artic should be
 * removed from nothing - it still answers, with a 503 per request (see [ApiExceptionHandler]).
 * Failing readiness here would take artic out of the load balancer too and turn one outage into
 * two. This is for observability, so `/actuator/health` says which hop is broken.
 */
@Component("antarctic")
class AntarcticHealthIndicator(
    channel: ManagedChannel,
    @Value("\${tern.antarctic.target}") private val target: String,
) : HealthIndicator {
    private val logger = LoggerFactory.getLogger(AntarcticHealthIndicator::class.java)
    private val health = HealthGrpc.newBlockingStub(channel)

    override fun health(): Health {
        return try {
            val status = health.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .check(HealthCheckRequest.getDefaultInstance())
                .status
            if (status == ServingStatus.SERVING) {
                Health.up().withDetail("target", target).build()
            } else {
                Health.down().withDetail("target", target).withDetail("status", status.name).build()
            }
        } catch (e: StatusRuntimeException) {
            logger.warn("Antarctic health check failed with ${e.status.code}")
            Health.down()
                .withDetail("target", target)
                .withDetail("status", e.status.code.name)
                .build()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
