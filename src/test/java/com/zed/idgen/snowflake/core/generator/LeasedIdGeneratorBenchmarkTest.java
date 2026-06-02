package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Manual throughput benchmark for local LeasedIdGenerator usage.
 *
 * Run explicitly with:
 * mvn -q "-Dbenchmark.leased-id-generator=true" "-Dtest=LeasedIdGeneratorBenchmarkTest" test
 *
 * The benchmark is property-gated because it runs five 30-second scenarios.
 */
@EnabledIfSystemProperty(named = "benchmark.leased-id-generator", matches = "true")
class LeasedIdGeneratorBenchmarkTest {
    private static final List<Integer> THREAD_COUNTS = List.of(1, 4, 16, 64, 256);
    private static final Duration SCENARIO_DURATION = Duration.ofSeconds(30);
    private static final long RENEW_INTERVAL_SECONDS = 5L;
    private static final long RENEW_TERM_NANOS = Duration.ofSeconds(10).toNanos();
    private static final long INITIAL_LEASE_TERM_NANOS = RENEW_TERM_NANOS;
    private static final int LOOP_CHUNK = 1024;

    @Test
    void benchmarkLocalLeasedGeneratorThroughput() throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        for (int threads : THREAD_COUNTS) {
            BenchmarkResult result = runScenario(threads);
            results.add(result);
            System.out.println(result.toConsoleLine());
        }
        writeReport(results);
    }

    private static BenchmarkResult runScenario(int threads) throws Exception {
        LeasedIdGenerator generator = newGenerator();
        generator.start();

        AtomicLong renewSuccess = new AtomicLong();
        AtomicLong renewFailures = new AtomicLong();
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor();
        renewer.scheduleAtFixedRate(() -> {
            try {
                generator.renewLocalDeadline(System.nanoTime() + RENEW_TERM_NANOS);
                renewSuccess.incrementAndGet();
            } catch (RuntimeException e) {
                renewFailures.incrementAndGet();
            }
        }, RENEW_INTERVAL_SECONDS, RENEW_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

        ExecutorService workers = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        LongAdder generated = new LongAdder();
        AtomicLong errors = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            futures.add(workers.submit(() -> {
                ready.countDown();
                start.await();
                long localCount = 0L;
                while (!stop.get()) {
                    for (int j = 0; j < LOOP_CHUNK && !stop.get(); j++) {
                        generator.getUid();
                        localCount++;
                    }
                }
                generated.add(localCount);
                return null;
            }));
        }

        ready.await();
        long startedAt = System.nanoTime();
        start.countDown();
        Thread.sleep(SCENARIO_DURATION.toMillis());
        stop.set(true);

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                errors.incrementAndGet();
                throw e;
            }
        }
        long elapsedNanos = System.nanoTime() - startedAt;

        workers.shutdownNow();
        renewer.shutdownNow();
        generator.stop();

        assertEquals(0L, errors.get(), "benchmark worker errors");
        return new BenchmarkResult(
                threads,
                generated.sum(),
                elapsedNanos,
                renewSuccess.get(),
                renewFailures.get(),
                errors.get());
    }

    private static LeasedIdGenerator newGenerator() {
        IdDomainInfo domainInfo = new IdDomainInfo(
                1L,
                "leased-benchmark",
                PrefixType.POSITIVE_SIGNAL,
                39,
                8,
                16,
                1704067200000L,
                TimeUnit.MILLISECOND);
        GeneratorPolicy policy = new GeneratorPolicy(
                1,
                IdRunOutPolicy.NONE,
                ClockRollbackPolicy.NONE);
        GeneratorInstanceProperties properties = new GeneratorInstanceProperties(
                domainInfo,
                1L,
                policy,
                1L,
                "leased-benchmark-generator");
        IdGeneratorInstance delegate = IdGeneratorFactory.create(properties, 1L);
        LeaseInfo leaseInfo = new LeaseInfo("leased-benchmark", 1L, "local-benchmark", "owner-token", 1L);
        return new LeasedIdGenerator(delegate, System.nanoTime() + INITIAL_LEASE_TERM_NANOS, leaseInfo);
    }

    private static void writeReport(List<BenchmarkResult> results) throws IOException {
        Path report = Path.of("ai", "phase-1", "test", "leased-id-generator-throughput-benchmark.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, renderReport(results));
    }

    private static String renderReport(List<BenchmarkResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# LeasedIdGenerator Local Throughput Benchmark\n\n");
        builder.append("Generated at: ").append(LocalDateTime.now()).append("\n\n");
        builder.append("## Setup\n\n");
        builder.append("- Generator: `LeasedIdGenerator` wrapping `DefaultIdGenerator` / `DefaultIdProvider`\n");
        builder.append("- Renew thread: renews every 5 seconds\n");
        builder.append("- Renew term: 10 seconds per renew\n");
        builder.append("- Scenario duration: 30 seconds\n");
        builder.append("- Thread counts: 1 / 4 / 16 / 64 / 256\n");
        builder.append("- ID domain bits: prefix=positive, time=39, worker=8, sequence=16\n");
        builder.append("- Java: ").append(System.getProperty("java.version")).append("\n");
        builder.append("- JVM: ").append(ManagementFactory.getRuntimeMXBean().getVmName()).append("\n");
        builder.append("- Available processors: ").append(Runtime.getRuntime().availableProcessors()).append("\n\n");
        builder.append("## Results\n\n");
        builder.append("| Threads | IDs Generated | Elapsed Seconds | Throughput IDs/sec | Renew Success | Renew Failures | Worker Errors |\n");
        builder.append("|---:|---:|---:|---:|---:|---:|---:|\n");
        for (BenchmarkResult result : results) {
            builder.append("| ")
                    .append(result.threads())
                    .append(" | ")
                    .append(format(result.generated()))
                    .append(" | ")
                    .append(String.format(Locale.ROOT, "%.3f", result.elapsedSeconds()))
                    .append(" | ")
                    .append(format(Math.round(result.throughputPerSecond())))
                    .append(" | ")
                    .append(result.renewSuccess())
                    .append(" | ")
                    .append(result.renewFailures())
                    .append(" | ")
                    .append(result.errors())
                    .append(" |\n");
        }
        builder.append("\n## Notes\n\n");
        builder.append("- This is a local benchmark only; it does not include MySQL acquire/renew/release latency.\n");
        builder.append("- `getUid()` still serializes inside `DefaultIdProvider`, so additional caller threads mainly measure contention around one generator instance.\n");
        builder.append("- The renew thread updates only the local safe deadline after a simulated successful lease renewal.\n");
        return builder.toString();
    }

    private static String format(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    private record BenchmarkResult(
            int threads,
            long generated,
            long elapsedNanos,
            long renewSuccess,
            long renewFailures,
            long errors) {

        double elapsedSeconds() {
            return elapsedNanos / 1_000_000_000.0;
        }

        double throughputPerSecond() {
            return generated / elapsedSeconds();
        }

        String toConsoleLine() {
            return "LEASED_ID_GENERATOR_BENCHMARK"
                    + "|threads=" + threads
                    + "|ids=" + generated
                    + "|elapsedSeconds=" + String.format(Locale.ROOT, "%.3f", elapsedSeconds())
                    + "|throughputPerSecond=" + Math.round(throughputPerSecond())
                    + "|renewSuccess=" + renewSuccess
                    + "|renewFailures=" + renewFailures
                    + "|errors=" + errors;
        }
    }
}
