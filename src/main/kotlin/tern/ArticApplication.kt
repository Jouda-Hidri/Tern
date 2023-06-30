package tern

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication


@SpringBootApplication
class ArticApplication

fun main(args: Array<String>) {
    runApplication<ArticApplication>(*args)
}