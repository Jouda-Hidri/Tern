package tern.tracing

import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TracingConfiguration {

    @GrpcGlobalServerInterceptor
    @Bean
    fun requestIdServerInterceptor(): RequestIdServerInterceptor = RequestIdServerInterceptor()

    @Bean
    fun requestIdClientInterceptor(): RequestIdClientInterceptor = RequestIdClientInterceptor()
}
