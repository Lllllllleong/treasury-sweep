package io.github.lllllllleong.treasurysweep

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// @EnableScheduling runs @Scheduled methods (LedgerMetrics.checkImbalance) on a background thread.
@SpringBootApplication
@EnableScheduling
class TreasurySweepApplication

fun main(args: Array<String>) {
    runApplication<TreasurySweepApplication>(*args)
}
