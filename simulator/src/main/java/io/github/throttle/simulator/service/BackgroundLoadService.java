package io.github.throttle.simulator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates a constant low-level background JVM load to make the dashboard
 * look realistic during demos (baseline ~15-20% CPU, ~300MB memory ballast).
 */
@Component
public class BackgroundLoadService {
    private static final Logger log = LoggerFactory.getLogger(BackgroundLoadService.class);

    private ScheduledExecutorService scheduler;
    private volatile boolean running = true;

    // Memory ballast — holds ~300MB to show meaningful memory usage
    private final List<byte[]> memoryBallast = new ArrayList<>();

    @PostConstruct
    public void start() {
        log.info("Starting background JVM load for demo (baseline CPU ~15-20%, Memory ballast ~300MB)");

        // Allocate 300MB in 1MB blocks
        for (int i = 0; i < 300; i++) {
            byte[] block = new byte[1024 * 1024];
            ThreadLocalRandom.current().nextBytes(block);
            memoryBallast.add(block);
        }

        scheduler = Executors.newScheduledThreadPool(6, r -> {
            Thread t = new Thread(r, "bg-load");
            t.setDaemon(true);
            return t;
        });

        // 4 CPU burn threads — each burns ~40ms every 50ms interval
        for (int t = 0; t < 4; t++) {
            scheduler.scheduleAtFixedRate(() -> {
                if (!running) return;
                try {
                    cpuBurn(40);
                } catch (Exception ignored) {}
            }, 500 + t * 100, 50, TimeUnit.MILLISECONDS);
        }

        // GC pressure thread — creates short-lived objects
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            try {
                gcPressure();
            } catch (Exception ignored) {}
        }, 1000, 100, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        memoryBallast.clear();
        log.info("Background load stopped");
    }

    /**
     * Burns CPU for the specified duration using tight math loops.
     */
    private void cpuBurn(int targetMs) {
        long deadline = System.nanoTime() + (targetMs * 1_000_000L);
        double accumulator = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 100; i++) {
                accumulator += Math.sin(accumulator + i) * Math.cos(accumulator - i);
            }
        }
        // Prevent dead code elimination
        if (accumulator == Double.MAX_VALUE) {
            log.trace("unreachable: {}", accumulator);
        }
    }

    /**
     * Creates short-lived objects to generate GC pressure,
     * making memory usage fluctuate on the dashboard.
     */
    private void gcPressure() {
        List<byte[]> temp = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            byte[] chunk = new byte[4096]; // 4KB per object × 100 = 400KB churn per call
            ThreadLocalRandom.current().nextBytes(chunk);
            temp.add(chunk);
        }
        // Force sort to prevent optimization
        temp.sort((a, b) -> Byte.compare(a[0], b[0]));
    }
}
