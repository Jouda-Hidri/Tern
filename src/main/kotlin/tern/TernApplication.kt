package tern

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TernApplication

fun main(args: Array<String>) {
    runApplication<TernApplication>(*args)
}