package tern

import io.opencensus.exporter.trace.jaeger.JaegerExporterConfiguration
import io.opencensus.exporter.trace.jaeger.JaegerTraceExporter
import io.opencensus.trace.Tracing
import io.opencensus.trace.samplers.Samplers
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication


@SpringBootApplication
class TernApplication

fun main(args: Array<String>) {
    runApplication<TernApplication>(*args)
    val jaegerEndpoint = "http://my-jaeger-instance-collector:14268/api/traces"
    JaegerTraceExporter.createAndRegister(
        JaegerExporterConfiguration.builder()
            .setThriftEndpoint(jaegerEndpoint)
            .setServiceName("my-jaeger-instance")
            .build()
    )
    val traceConfig = Tracing.getTraceConfig()
    traceConfig.updateActiveTraceParams(
        traceConfig
            .activeTraceParams
            .toBuilder()
            .setSampler(Samplers.alwaysSample()).build()
    )
}