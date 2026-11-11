package io.github.lllllllleong.treasurysweep

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<TreasurySweepApplication>().with(TestcontainersConfiguration::class).run(*args)
}
