package tern

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class TernApplication

fun main(args: Array<String>) {
    runApplication<TernApplication>(*args)
}