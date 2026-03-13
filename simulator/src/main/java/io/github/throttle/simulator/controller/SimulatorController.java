package io.github.throttle.simulator.controller;

import io.github.throttle.service.api.ThrottleService;
import io.github.throttle.simulator.load.CpuLoadGenerator;
import io.github.throttle.simulator.load.MemoryLoadGenerator;
import io.github.throttle.simulator.service.LoadControlService;
import io.github.throttle.simulator.test.ScenarioRunner;
import io.github.throttle.simulator.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Controller
@RequestMapping("/api/simulator")
public class SimulatorController {
    private static final Logger log = LoggerFactory.getLogger(SimulatorController.class);

    @Autowired(required = false)
    private ThrottleService executor;

    @Autowired(required = false)
    private ScenarioRunner scenarioRunner;

    @Autowired
    private CpuLoadGenerator cpuLoadGenerator;

    @Autowired
    private MemoryLoadGenerator memoryLoadGenerator;

    @Autowired
    private LoadControlService loadControlService;

    // Single shared executor for background tasks
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "simulator-bg-");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        try {
            bgExecutor.shutdownNow();
        } catch (Exception e) {
            log.warn("Error shutting down bgExecutor", e);
        }
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        try {
            if (executor != null) {
                model.addAttribute("queueSize", executor.getMetrics().getQueueSize());
                model.addAttribute("activeThreads", executor.getMetrics().getActiveThreads());
            } else {
                model.addAttribute("queueSize", 0);
                model.addAttribute("activeThreads", 0);
            }
        } catch (Exception e) {
            log.warn("Error populating dashboard model", e);
            model.addAttribute("queueSize", 0);
            model.addAttribute("activeThreads", 0);
        }

        return "dashboard";
    }

    @PostMapping("/load/cpu/start")
    @ResponseBody
    public ResponseEntity<?> startCpuLoad(@RequestParam int targetPercent, @RequestParam long durationMs) {
        log.info("API: startCpuLoad target={} duration={}", targetPercent, durationMs);
        bgExecutor.submit(() -> {
            try {
                loadControlService.setCpuActive(true);
                cpuLoadGenerator.generateLoad(targetPercent, durationMs);
            } finally {
                loadControlService.setCpuActive(false);
            }
        });
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "CPU load started");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/load/cpu/stop")
    @ResponseBody
    public ResponseEntity<?> stopCpuLoad() {
        log.info("API: stopCpuLoad");
        cpuLoadGenerator.stopLoad();
        loadControlService.setCpuActive(false);
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "CPU load stop requested");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/load/memory/start")
    @ResponseBody
    public ResponseEntity<?> startMemoryLoad(@RequestParam int targetPercent, @RequestParam long durationMs) {
        log.info("API: startMemoryLoad target={} duration={}", targetPercent, durationMs);
        bgExecutor.submit(() -> {
            try {
                loadControlService.setMemoryActive(true);
                memoryLoadGenerator.generateLoad(targetPercent, durationMs);
            } finally {
                loadControlService.setMemoryActive(false);
            }
        });
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Memory load started");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/load/memory/stop")
    @ResponseBody
    public ResponseEntity<?> stopMemoryLoad() {
        log.info("API: stopMemoryLoad");
        memoryLoadGenerator.releaseMemory();
        loadControlService.setMemoryActive(false);
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Memory load stop requested");
        return ResponseEntity.ok(resp);
    }

    // Run a single test; attempt to wait for result up to a timeout and return structured JSON
    @PostMapping("/run/{testName}")
    @ResponseBody
    public ResponseEntity<?> runTest(@PathVariable String testName) {
        log.info("API: runTest {} (attempting to execute)", testName);
        if (scenarioRunner == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "ScenarioRunner not available"));
        }

        Callable<TestResult> task = () -> {
            switch (testName.toLowerCase()) {
                case "normal":
                case "normal-operation":
                    return scenarioRunner.runNormalOperationTest();
                case "resource-spike":
                case "spike":
                    return scenarioRunner.runResourceSpikeTest();
                case "sustained-load":
                case "sustained":
                    return scenarioRunner.runSustainedLoadTest();
                case "memory-pressure":
                case "memory":
                    return scenarioRunner.runMemoryPressureTest();
                case "task-killing":
                case "killing":
                case "taskkilling":
                    return scenarioRunner.runTaskKillingTest();
                case "priority-scheduling":
                case "priority":
                    return scenarioRunner.runPrioritySchedulingTest();
                case "stress-test":
                case "stress":
                    return scenarioRunner.runStressTest();
                case "flapping-monitor":
                case "flapping":
                    return scenarioRunner.runFlappingMonitorTest();
                case "queue-overflow":
                case "overflow":
                    return scenarioRunner.runQueueOverflowTest();
                case "failing-tasks":
                case "failing":
                    return scenarioRunner.runFailingTasksTest();
                case "cascade-kill":
                case "cascade":
                    return scenarioRunner.runCascadeKillTest();
                case "shutdown-under-load":
                case "shutdown":
                    return scenarioRunner.runShutdownUnderLoadTest();
                default:
                    return scenarioRunner.runNormalOperationTest();
            }
        };

        Future<TestResult> future = bgExecutor.submit(task);
        try {
            // Wait up to 120 seconds for test to finish; if not ready, return started message
            TestResult res = future.get(120, TimeUnit.SECONDS);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", res.isSuccess());
            resp.put("duration", res.getDuration());
            resp.put("tasksCompleted", res.getTasksCompleted());
            resp.put("tasksFailed", res.getTasksFailed());
            resp.put("tasksKilled", res.getTasksKilled());
            resp.put("pauseCount", res.getPauseCount());
            resp.put("error", res.getError() == null ? "" : res.getError());
            return ResponseEntity.ok(resp);
        } catch (TimeoutException te) {
            log.info("Test {} is still running; returning started response", testName);
            return ResponseEntity.ok(Map.of("message", "Test started and running in background"));
        } catch (Exception e) {
            log.error("Error running test {}", testName, e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // Run all tests synchronously with a generous timeout; return summary
    @PostMapping("/run-all")
    @ResponseBody
    public ResponseEntity<?> runAllTests() {
        log.info("API: runAll (submitted)");
        if (scenarioRunner == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "ScenarioRunner not available"));
        }

        Future<List<TestResult>> future = bgExecutor.submit(scenarioRunner::runAllTests);
        try {
            List<TestResult> results = future.get(300, TimeUnit.SECONDS);
            int total = results.size();
            int passed = (int) results.stream().filter(TestResult::isSuccess).count();
            Map<String, Object> resp = new HashMap<>();
            resp.put("totalTests", total);
            resp.put("passed", passed);
            resp.put("results", results);
            return ResponseEntity.ok(resp);
        } catch (TimeoutException te) {
            log.info("runAll exceeded wait timeout - running in background");
            return ResponseEntity.ok(Map.of("message", "run-all started in background"));
        } catch (Exception e) {
            log.error("Error running all tests", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> map = new HashMap<>();
        if (executor != null) {
            try {
                map.put("queueSize", executor.getMetrics().getQueueSize());
                map.put("activeThreads", executor.getMetrics().getActiveThreads());
                map.put("paused", executor.isPaused());
            } catch (Exception e) {
                map.put("error", "executor-unavailable");
            }
        } else {
            map.put("queueSize", 0);
            map.put("activeThreads", 0);
            map.put("paused", false);
        }

        var s = loadControlService.getStatus();
        map.put("cpuLoadActive", s.isCpuLoadActive());
        map.put("memoryLoadActive", s.isMemoryLoadActive());

        return map;
    }
}

