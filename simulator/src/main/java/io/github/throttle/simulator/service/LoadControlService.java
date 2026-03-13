package io.github.throttle.simulator.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple service to control and report load generator status.
 */
@Service
public class LoadControlService {

    public static class LoadControlStatus {
        private final boolean cpuLoadActive;
        private final boolean memoryLoadActive;

        public LoadControlStatus(boolean cpuLoadActive, boolean memoryLoadActive) {
            this.cpuLoadActive = cpuLoadActive;
            this.memoryLoadActive = memoryLoadActive;
        }

        public boolean isCpuLoadActive() { return cpuLoadActive; }
        public boolean isMemoryLoadActive() { return memoryLoadActive; }
    }

    private final AtomicBoolean cpuActive = new AtomicBoolean(false);
    private final AtomicBoolean memoryActive = new AtomicBoolean(false);

    public LoadControlStatus getStatus() {
        return new LoadControlStatus(cpuActive.get(), memoryActive.get());
    }

    public void setCpuActive(boolean active) { cpuActive.set(active); }
    public void setMemoryActive(boolean active) { memoryActive.set(active); }

    public boolean isCpuActive() { return cpuActive.get(); }
    public boolean isMemoryActive() { return memoryActive.get(); }
}
