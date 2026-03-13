package io.github.throttle.simulator.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates CPU load by performing CPU-intensive calculations.
 * Designed for Apple M4 Air with 10 cores.
 */
@Service
public class CpuLoadGenerator {
    private static final Logger log = LoggerFactory.getLogger(CpuLoadGenerator.class);

    private final List<Thread> workerThreads = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Start generating CPU load.
     *
     * @param targetCpuPercent Target CPU usage (0-100)
     * @param durationMs Duration to generate load in milliseconds
     */
    public void generateLoad(int targetCpuPercent, long durationMs) {
        if (running.get()) {
            log.warn("CPU load generator already running");
            return;
        }

        running.set(true);
        int availableCores = Runtime.getRuntime().availableProcessors();
        int threadsToUse = Math.max(1, (targetCpuPercent * availableCores) / 100);

        log.info("Generating CPU load: target={}%, duration={}ms, cores={}, threads={}",
            targetCpuPercent, durationMs, availableCores, threadsToUse);

        for (int i = 0; i < threadsToUse; i++) {
            Thread worker = new Thread(new CpuBurner(durationMs), "CPU-Burner-" + i);
            worker.setDaemon(true);
            workerThreads.add(worker);
            worker.start();
        }
    }

    /**
     * Stop generating CPU load.
     */
    public void stopLoad() {
        running.set(false);
        for (Thread thread : workerThreads) {
            thread.interrupt();
        }
        workerThreads.clear();
        log.info("Stopped CPU load generation");
    }

    /**
     * Wait for load generation to complete.
     */
    public void await() throws InterruptedException {
        for (Thread thread : workerThreads) {
            thread.join();
        }
        running.set(false);
    }

    /**
     * Check if CPU load is currently running.
     */
    public boolean isRunning() {
        return running.get() && !workerThreads.isEmpty();
    }

    /**
     * CPU burning task that performs intensive calculations.
     */
    private class CpuBurner implements Runnable {
        private final long durationMs;

        public CpuBurner(long durationMs) {
            this.durationMs = durationMs;
        }

        @Override
        public void run() {
            long startTime = System.currentTimeMillis();
            long endTime = startTime + durationMs;

            log.debug("CPU burner started: thread={}", Thread.currentThread().getName());

            // Perform CPU-intensive calculations
            while (System.currentTimeMillis() < endTime && running.get() && !Thread.interrupted()) {
                // Complex mathematical operations to burn CPU - increased intensity
                double result = 0;
                for (int i = 0; i < 100000; i++) {
                    result += Math.sqrt(i) * Math.sin(i) * Math.cos(i);
                    result += Math.pow(i, 2.5) / (i + 1);
                    result += Math.log(i + 1) * Math.exp(i % 10);
                }

                // Prevent optimization
                if (result == Double.NEGATIVE_INFINITY) {
                    System.out.println(result);
                }
            }

            log.debug("CPU burner finished: thread={}, duration={}ms",
                Thread.currentThread().getName(),
                System.currentTimeMillis() - startTime);
        }
    }
}
