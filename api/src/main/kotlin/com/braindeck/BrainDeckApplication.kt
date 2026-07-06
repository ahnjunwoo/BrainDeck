package com.braindeck

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class BrainDeckApplication

fun main(args: Array<String>) {
    runApplication<BrainDeckApplication>(*args)
}
