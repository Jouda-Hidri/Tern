package tern

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import java.time.LocalDateTime
import kotlin.system.exitProcess

@SpringBootApplication
class TernApplication : CommandLineRunner {
    private val log = LoggerFactory.getLogger(TernApplication::class.java)

    @Autowired
    lateinit var jobLauncher: JobLauncher

    @Autowired
    lateinit var job1: Job

    @Autowired
    lateinit var job2: Job

    override fun run(vararg args: String?) {
        if (args.isEmpty()) {
            log.warn("No args")
        } else {
            when (args[0]) {
                "job1" -> {
                    val currentDate = LocalDateTime.now()
                    log.info("run job1 at {}", currentDate)
                    jobLauncher.run(job1, JobParameters())
                    exitProcess(0)
                }

                "job2" -> {
                    val currentDate = LocalDateTime.now()
                    log.info("run job2 at {}", currentDate)
                    jobLauncher.run(job2, JobParameters())
                    exitProcess(0)
                }

                else -> {
                    log.error("Unknown job {}", args[0])
                    exitProcess(1)
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    SpringApplication.run(TernApplication::class.java, *args)
}