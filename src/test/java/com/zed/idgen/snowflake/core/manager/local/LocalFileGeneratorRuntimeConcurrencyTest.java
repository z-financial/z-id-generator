package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.manager.GeneratorParam;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocalFileGeneratorRuntimeConcurrencyTest {
    private static final long RUN_SECONDS = 10L;
    private static final long RUN_NANOS = TimeUnit.SECONDS.toNanos(RUN_SECONDS);
    private static final long LEGALITY_SAMPLE_INTERVAL = 4_096L;
    private static final long MIN_IDS_PER_SECOND = 10_000L;

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void localFileRuntimeGeneratesIdsForTenSeconds(Scenario scenario) throws Exception {
        String baseDir = scenario.baseDir();
        Path root = prepareRegistrationFiles(baseDir, scenario.generatorCount());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, baseDir)) {
            IdCodec codec = new IdCodec(manager.getDomainInfo());
            List<RunningGenerator> generators = startAllGenerators(manager, scenario.generatorCount());

            BenchmarkResult result = generateForTenSeconds(manager, codec, generators, scenario);

            assertTrue(result.totalIds() > 0, "no id was generated");
            assertTrue(result.idsPerSecond() >= MIN_IDS_PER_SECOND,
                    () -> "id generation throughput is too low: " + result.idsPerSecond() + " ids/sec");
            System.out.println(result.toReportLine(scenario));
        }
    }

    private static Stream<Arguments> scenarios() {
        List<Arguments> scenarios = new ArrayList<>();
        for (int generatorCount : List.of(1, 4, 20)) {
            for (int threadsPerGenerator : List.of(1, 4, 16)) {
                scenarios.add(Arguments.of(new Scenario(generatorCount, threadsPerGenerator, GenerationMode.NORMAL, 1)));
                for (int batchSize : List.of(16, 256)) {
                    scenarios.add(Arguments.of(new Scenario(generatorCount, threadsPerGenerator, GenerationMode.BATCH, batchSize)));
                }
            }
        }
        return scenarios.stream();
    }

    private List<RunningGenerator> startAllGenerators(LocalFileGeneratorRuntimeManager manager, int generatorCount) {
        List<RunningGenerator> generators = new ArrayList<>(generatorCount);
        for (int i = 0; i < generatorCount; i++) {
            long generatorId = i + 1L;
            long workerId = i;
            String generatorName = generatorName(i);
            GeneratorParam param = new GeneratorParam(generatorId, generatorName);

            GeneratorProperties properties = manager.getGeneratorProperties(param);
            assertEquals(generatorId, properties.generatorConfig().generatorId());
            assertEquals(generatorName, properties.generatorConfig().generatorName());
            assertEquals(List.of(workerId), properties.workerIds());

            IdGenerator generator = manager.startGeneratorInstance(param);
            assertNotNull(generator);
            generators.add(new RunningGenerator(generatorId, generatorName, workerId, generator));
        }
        return generators;
    }

    private BenchmarkResult generateForTenSeconds(LocalFileGeneratorRuntimeManager manager,
                                                  IdCodec codec,
                                                  List<RunningGenerator> generators,
                                                  Scenario scenario) throws Exception {
        int taskCount = generators.size() * scenario.threadsPerGenerator();
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong deadlineNanos = new AtomicLong();
        List<Future<Long>> futures = new ArrayList<>(taskCount);

        try {
            for (RunningGenerator generator : generators) {
                for (int i = 0; i < scenario.threadsPerGenerator(); i++) {
                    futures.add(executor.submit(() -> generateFromOneWorker(
                            manager, codec, generator, scenario, ready, start, deadlineNanos)));
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "generator tasks did not become ready");
            long startedAt = System.nanoTime();
            deadlineNanos.set(startedAt + RUN_NANOS);
            start.countDown();

            long total = 0;
            for (Future<Long> future : futures) {
                total += future.get(RUN_SECONDS + 15, TimeUnit.SECONDS);
            }
            long elapsedNanos = System.nanoTime() - startedAt;

            return new BenchmarkResult(total, elapsedNanos);
        } catch (java.util.concurrent.TimeoutException e) {
            fail("10-second id generation run timed out, possible deadlock", e);
            return null;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private long generateFromOneWorker(LocalFileGeneratorRuntimeManager manager,
                                       IdCodec codec,
                                       RunningGenerator runningGenerator,
                                       Scenario scenario,
                                       CountDownLatch ready,
                                       CountDownLatch start,
                                       AtomicLong deadlineNanos) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS), "start gate was not released");

        IdGenerator indexedGenerator = manager.getGeneratorInstance(
                new GeneratorParam(runningGenerator.generatorId(), runningGenerator.generatorName()));
        assertSame(runningGenerator.generator(), indexedGenerator);

        return switch (scenario.mode()) {
            case NORMAL -> generateNormal(runningGenerator, codec, deadlineNanos.get());
            case BATCH -> generateByBatch(runningGenerator, scenario.batchSize(), codec, deadlineNanos.get());
        };
    }

    private long generateNormal(RunningGenerator runningGenerator, IdCodec codec, long deadlineNanos) {
        long generated = 0;
        long previousId = -1L;
        while (System.nanoTime() < deadlineNanos) {
            long id = runningGenerator.generator().getUid();
            assertTrue(id > previousId, "ids generated by one task must be strictly increasing");
            if (shouldSample(generated)) {
                assertLegalId(id, runningGenerator.workerId(), codec);
            }
            previousId = id;
            generated++;
        }
        assertLastGeneratedIdWasLegal(previousId, runningGenerator.workerId(), codec);
        return generated;
    }

    private long generateByBatch(RunningGenerator runningGenerator, int batchSize, IdCodec codec, long deadlineNanos) {
        long generated = 0;
        long previousId = -1L;
        while (System.nanoTime() < deadlineNanos) {
            long[] ids = runningGenerator.generator().getUidByBatch(batchSize);
            assertEquals(batchSize, ids.length);
            for (long id : ids) {
                assertTrue(id > previousId, "ids generated by one task must be strictly increasing");
                if (shouldSample(generated)) {
                    assertLegalId(id, runningGenerator.workerId(), codec);
                }
                previousId = id;
                generated++;
            }
        }
        assertLastGeneratedIdWasLegal(previousId, runningGenerator.workerId(), codec);
        return generated;
    }

    private static boolean shouldSample(long generated) {
        return generated % LEGALITY_SAMPLE_INTERVAL == 0;
    }

    private static void assertLastGeneratedIdWasLegal(long id, long expectedWorkerId, IdCodec codec) {
        if (id >= 0) {
            assertLegalId(id, expectedWorkerId, codec);
        }
    }

    private static void assertLegalId(long id, long expectedWorkerId, IdCodec codec) {
        assertTrue(id > 0, "id must be positive: " + id);
        long time = codec.getTime(id);
        long worker = codec.getWorker(id);
        long sequence = codec.getSequence(id);

        assertTrue(time >= 0 && time <= codec.getMaxTime(), "illegal time field: " + time);
        assertEquals(expectedWorkerId, worker, "illegal worker field");
        assertTrue(sequence >= 0 && sequence <= codec.getMaxSequence(), "illegal sequence field: " + sequence);
        assertEquals(id, codec.buildId(time, worker, sequence), "id cannot be rebuilt from decoded fields");
    }

    private Path prepareRegistrationFiles(String baseDir, int generatorCount) throws IOException {
        Path root = tempDir.resolve("uid-register");
        Path base = root.resolve(baseDir);
        Path generatorDir = base.resolve("generator");
        Files.createDirectories(generatorDir);
        long epochStartMs = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        Files.writeString(base.resolve("domain.yml"), """
                domainId: 100
                domainName: orders
                prefixType: POSITIVE_SIGNAL
                timeBit: 27
                workerBit: 5
                sequenceBit: 31
                epochStartMs: %d
                timeUnit: SECOND
                """.formatted(epochStartMs));

        for (int i = 0; i < generatorCount; i++) {
            long generatorId = i + 1L;
            long workerId = i;
            String fileName = generatorId + "-" + workerId + "-" + generatorName(i) + ".yml";
            Files.writeString(generatorDir.resolve(fileName), """
                    generationPolicy:
                      maximumRetry: 1
                      idRunOutPolicy: NONE
                      clockRollbackPolicy: NONE
                    """);
        }
        return root;
    }

    private static String generatorName(int index) {
        return "generator-" + index;
    }

    private enum GenerationMode {
        NORMAL,
        BATCH
    }

    private record Scenario(int generatorCount, int threadsPerGenerator, GenerationMode mode, int batchSize) {
        private String baseDir() {
            return "ten-seconds-" + generatorCount + "-" + threadsPerGenerator + "-" + mode + "-" + batchSize;
        }

        @Override
        public String toString() {
            if (mode == GenerationMode.NORMAL) {
                return "generators=" + generatorCount + ", threadsPerGenerator=" + threadsPerGenerator + ", mode=normal";
            }
            return "generators=" + generatorCount + ", threadsPerGenerator=" + threadsPerGenerator
                    + ", mode=batch, batchSize=" + batchSize;
        }
    }

    private record RunningGenerator(long generatorId, String generatorName, long workerId, IdGenerator generator) {
    }

    private record BenchmarkResult(long totalIds, long elapsedNanos) {
        private long idsPerSecond() {
            if (elapsedNanos <= 0) {
                return Long.MAX_VALUE;
            }
            return Math.round(totalIds * 1_000_000_000.0 / elapsedNanos);
        }

        private double elapsedSeconds() {
            return elapsedNanos / 1_000_000_000.0;
        }

        private String toReportLine(Scenario scenario) {
            return "TEN_SECOND_THROUGHPUT|generators=" + scenario.generatorCount()
                    + "|threadsPerGenerator=" + scenario.threadsPerGenerator()
                    + "|mode=" + scenario.mode()
                    + "|batchSize=" + scenario.batchSize()
                    + "|totalIds=" + totalIds
                    + "|elapsedSeconds=" + String.format("%.3f", elapsedSeconds())
                    + "|idsPerSecond=" + idsPerSecond();
        }
    }
}
