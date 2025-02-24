package tern.jobs

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tern.grpc.TernServiceGrpc

@Configuration
@EnableBatchProcessing
class BatchConfig {

    private val log = LoggerFactory.getLogger(BatchConfig::class.java)

    private var channel: ManagedChannel = ManagedChannelBuilder.forTarget("antarctic.default.svc.cluster.local:30000")
        .usePlaintext()
        .build()
    private var blockingStub = TernServiceGrpc.newBlockingStub(channel)

    @Bean
    fun job1(jobBuilderFactory: JobBuilderFactory, step1: Step): Job {
        return jobBuilderFactory.get("job1")
            .start(step1)
            .build()
    }

    @Bean
    fun job2(jobBuilderFactory: JobBuilderFactory, step2: Step): Job {
        return jobBuilderFactory.get("job2")
            .start(step2)
            .build()
    }

    @Bean
    fun step1(stepBuilderFactory: StepBuilderFactory): Step {
        return stepBuilderFactory.get("step1")
            .tasklet { contribution, chunkContext ->
                log.info("Executing job 1")
                val response = blockingStub.getMessage(Empty.getDefaultInstance())
                response.forEachRemaining { message ->
                    log.info("Batch response - $message")
                }
                RepeatStatus.FINISHED
            }
            .build()
    }

    @Bean
    fun step2(stepBuilderFactory: StepBuilderFactory): Step {
        return stepBuilderFactory.get("step2")
            .tasklet { contribution, chunkContext ->
                log.info("Executing job 2 step...")
                RepeatStatus.FINISHED
            }
            .build()
    }
}
