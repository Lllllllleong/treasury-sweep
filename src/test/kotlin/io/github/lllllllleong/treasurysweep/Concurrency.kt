package io.github.lllllllleong.treasurysweep

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs [task] [times] times, each on its own thread, and returns the results in index order.
 *
 * Every thread waits at a start gate until all of them are submitted, then they're released together, so the calls
 * really overlap instead of trickling in as threads start. Go: N goroutines blocked on `<-start`, then `close(start)`.
 * If a task throws, its exception is rethrown here.
 */
fun <T> runConcurrently(times: Int, task: (Int) -> T): List<T> {
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(times)
    // try/finally rather than use {}: close() would wait for stuck tasks forever, shutdownNow() interrupts them.
    try {
        val futures = (0 until times).map { i ->
            pool.submit(Callable { start.await(); task(i) })
        }
        start.countDown()
        return futures.map { future ->
            try {
                future.get(30, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    } finally {
        pool.shutdownNow()
    }
}
