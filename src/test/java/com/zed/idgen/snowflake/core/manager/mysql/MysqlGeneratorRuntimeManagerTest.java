package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.MysqlLeaseException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MysqlGeneratorRuntimeManagerTest {

    @Test
    void reuseDelayIncludesDomainUnitClockSkewAndSafetyMargin() {
        MysqlGeneratorRuntimeConfig config = runtimeConfig();

        assertEquals(1_101L, config.reuseDelayMillis(TimeUnit.MILLISECOND));
        assertEquals(2_100L, config.reuseDelayMillis(TimeUnit.SECOND));
    }

    @Test
    void reuseGuardConfigurationIsLimitedToOneDayPerComponent() {
        long oneDayMillis = 86_400_000L;
        MysqlGeneratorRuntimeConfig maximumConfig = new MysqlGeneratorRuntimeConfig(
                10,
                8_000L,
                oneDayMillis,
                oneDayMillis,
                5_000L,
                5_000L,
                1,
                1,
                16);

        assertEquals(172_801_000L, maximumConfig.reuseDelayMillis(TimeUnit.SECOND));
        assertThrows(InvalidConfigException.class, () -> new MysqlGeneratorRuntimeConfig(
                10, 8_000L, oneDayMillis + 1, 100L, 5_000L, 5_000L, 1, 1, 16));
        assertThrows(InvalidConfigException.class, () -> new MysqlGeneratorRuntimeConfig(
                10, 8_000L, 1_000L, oneDayMillis + 1, 5_000L, 5_000L, 1, 1, 16));
    }

    @Test
    void startSkipsLeaseUnavailableWorkerAndReleasesOnStop() {
        FakeRegistrationLoader registrationLoader = new FakeRegistrationLoader();
        FakeWorkerRegistrar workerRegistrar = new FakeWorkerRegistrar(registrationLoader.properties(3L));
        FakeLeaseManager leaseManager = new FakeLeaseManager(Set.of(1L));
        try (MysqlGeneratorRuntimeManager manager = new MysqlGeneratorRuntimeManager(
                registrationLoader,
                leaseManager,
                workerRegistrar,
                "node-a",
                runtimeConfig())) {

            IdGenerator generator = manager.startGeneratorInstance(11L);

            assertNotNull(generator);
            assertTrue(generator.getUid() > 0);
            assertSame(generator, manager.getGeneratorInstance(11L, 2L));
            assertEquals(List.of(1L, 2L), leaseManager.acquireRefs);
            assertEquals(1_101L, leaseManager.lastRequest.reuseDelayMillis());
            assertEquals(0, workerRegistrar.registrationCount.get());

            assertTrue(manager.stopGeneratorInstance(11L, 2L));
            assertEquals(1, leaseManager.releaseCount.get());
        }
    }

    @Test
    void exhaustedCandidatesRegisterOnceAndStartCommittedWorker() {
        FakeRegistrationLoader registrationLoader = new FakeRegistrationLoader();
        FakeLeaseManager leaseManager = new FakeLeaseManager(Set.of(1L, 2L));
        FakeWorkerRegistrar workerRegistrar = new FakeWorkerRegistrar(registrationLoader.properties(3L));
        try (MysqlGeneratorRuntimeManager manager = new MysqlGeneratorRuntimeManager(
                registrationLoader,
                leaseManager,
                workerRegistrar,
                "node-a",
                runtimeConfig())) {

            assertNotNull(manager.startGeneratorInstance(11L));

            assertSame(manager.getGeneratorInstance(11L, 3L), manager.getGeneratorInstances(11L).get(3L));
            assertEquals(List.of(1L, 2L, 3L), leaseManager.acquireRefs);
            assertEquals(1, workerRegistrar.registrationCount.get());
        }
    }

    @Test
    void emptyAutomaticStartRegistersButExactStartDoesNot() {
        List<String> events = new ArrayList<>();
        FakeRegistrationLoader registrationLoader = new FakeRegistrationLoader(List.of());
        FakeLeaseManager leaseManager = new FakeLeaseManager(Set.of(), events);
        FakeWorkerRegistrar workerRegistrar = new FakeWorkerRegistrar(
                registrationLoader.properties(0L), events);
        try (MysqlGeneratorRuntimeManager manager = new MysqlGeneratorRuntimeManager(
                registrationLoader,
                leaseManager,
                workerRegistrar,
                "node-a",
                runtimeConfig())) {

            assertThrows(InvalidConfigException.class, () -> manager.startGeneratorInstance(11L, 0L));
            assertEquals(0, workerRegistrar.registrationCount.get());

            assertNotNull(manager.startGeneratorInstance(11L));

            assertNotNull(manager.getGeneratorInstance(11L, 0L));
            assertEquals(1, workerRegistrar.registrationCount.get());
            assertEquals(List.of("register:0", "lease:0"), events);
        }
    }

    @Test
    void slowAcquireConsumesLocalSafeTermInsteadOfExtendingIt() {
        FakeLeaseManager leaseManager = new FakeLeaseManager(Set.of(), null, 80L);
        MysqlGeneratorRuntimeConfig config = new MysqlGeneratorRuntimeConfig(
                1,
                40L,
                0L,
                1L,
                10L,
                10L,
                1,
                1,
                16);

        try (MysqlGeneratorRuntimeManager manager = new MysqlGeneratorRuntimeManager(
                new FakeRegistrationLoader(),
                leaseManager,
                "node-a",
                config)) {

            IdGenerationException ex = assertThrows(
                    IdGenerationException.class,
                    () -> manager.startGeneratorInstance(11L, 1L));

            assertEquals(SnowflakeErrorCode.LeaseExpired, ex.getErrorCode());
            assertEquals(1, leaseManager.releaseCount.get());
        }
    }

    @Test
    void slowRenewConsumesLocalSafeTermInsteadOfExtendingIt() throws InterruptedException {
        Logger logger = Logger.getLogger(MysqlGeneratorRuntimeManager.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        FakeLeaseManager leaseManager = new FakeLeaseManager(
                Set.of(),
                MysqlLeaseException.operationFailed("fake renew", new IllegalStateException("unavailable")),
                0L,
                250L,
                1);
        MysqlGeneratorRuntimeConfig config = new MysqlGeneratorRuntimeConfig(
                2,
                600L,
                0L,
                1L,
                50L,
                10L,
                1,
                1,
                16);

        try {
            try (MysqlGeneratorRuntimeManager manager = new MysqlGeneratorRuntimeManager(
                    new FakeRegistrationLoader(),
                    leaseManager,
                    "node-a",
                    config)) {

                assertNotNull(manager.startGeneratorInstance(11L, 1L));
                waitForRenewCalls(leaseManager, 2);

                /*
                 * The first renew spends 250 ms inside the lease manager. Anchoring
                 * its 600 ms safe term before that call leaves roughly 350 ms after
                 * the response. The old response-time calculation would leave the
                 * full 600 ms and this entry would still be published here.
                 */
                Thread.sleep(400L);
                assertNull(manager.getGeneratorInstance(11L, 1L));
            }
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void unexpectedRenewFailureShutsDownManager() throws InterruptedException {
        Logger logger = Logger.getLogger(MysqlGeneratorRuntimeManager.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        FakeLeaseManager leaseManager = new FakeLeaseManager(Set.of(), new IllegalStateException("renew bug"));
        try {
            try (MysqlGeneratorRuntimeManager manager = new MysqlGeneratorRuntimeManager(
                    new FakeRegistrationLoader(),
                    leaseManager,
                    "node-a",
                    fastRuntimeConfig())) {

                assertNotNull(manager.startGeneratorInstance(11L));

                IdGenerationException ex = waitUntilClosed(manager);
                assertEquals(SnowflakeErrorCode.RuntimeManagerClosed, ex.getErrorCode());
            }
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    private static MysqlGeneratorRuntimeConfig runtimeConfig() {
        return new MysqlGeneratorRuntimeConfig(
                10,
                8_000L,
                1_000L,
                100L,
                5_000L,
                5_000L,
                1,
                1,
                16);
    }

    private static MysqlGeneratorRuntimeConfig fastRuntimeConfig() {
        return new MysqlGeneratorRuntimeConfig(
                10,
                8_000L,
                1_000L,
                100L,
                10L,
                10L,
                1,
                1,
                16);
    }

    private static IdGenerationException waitUntilClosed(MysqlGeneratorRuntimeManager manager)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                manager.getDomainInfo();
            } catch (IdGenerationException e) {
                return e;
            }
            Thread.sleep(20L);
        }
        return fail("manager did not shut down after unexpected renew failure");
    }

    private static void waitForRenewCalls(FakeLeaseManager leaseManager, int expected)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (leaseManager.renewCount.get() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("renew calls did not reach " + expected);
    }

    private static final class FakeRegistrationLoader implements RegistrationLoader {
        private final IdDomainInfo domainInfo = new IdDomainInfo(
                100L,
                "orders",
                PrefixType.POSITIVE_SIGNAL,
                41,
                10,
                12,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND);
        private final GeneratorConfig generatorConfig = new GeneratorConfig(
                11L,
                "order",
                new GeneratorPolicy(
                        1,
                        IdRunOutPolicy.NONE,
                        ClockRollbackPolicy.NONE));
        private final List<Long> workerIds;

        private FakeRegistrationLoader() {
            this(List.of(1L, 2L));
        }

        private FakeRegistrationLoader(List<Long> workerIds) {
            this.workerIds = List.copyOf(workerIds);
        }

        @Override
        public IdDomainInfo getDomainInfo() {
            return domainInfo;
        }

        @Override
        public long resolveGeneratorId(String generatorName) {
            return generatorConfig.generatorId();
        }

        @Override
        public List<GeneratorProperties> loadGeneratorProperties(long generatorId) {
            return workerIds.stream().map(this::properties).toList();
        }

        private GeneratorProperties properties(long workerId) {
            return new GeneratorProperties(domainInfo, generatorConfig, workerId, null);
        }
    }

    private static final class FakeWorkerRegistrar implements WorkerRegistrar {
        private final GeneratorProperties registered;
        private final List<String> events;
        private final AtomicInteger registrationCount = new AtomicInteger();

        private FakeWorkerRegistrar(GeneratorProperties registered) {
            this(registered, null);
        }

        private FakeWorkerRegistrar(GeneratorProperties registered, List<String> events) {
            this.registered = registered;
            this.events = events;
        }

        @Override
        public GeneratorProperties registerNext(long generatorId) {
            registrationCount.incrementAndGet();
            if (events != null) {
                events.add("register:" + registered.workerId());
            }
            return registered;
        }
    }

    private static final class FakeLeaseManager implements LeaseManager {
        private final Set<Long> unavailableRefs;
        private final RuntimeException renewFailure;
        private final long acquireDelayMillis;
        private final long renewDelayMillis;
        private final int successfulRenewalsBeforeFailure;
        private final List<String> events;
        private final List<Long> acquireRefs = new ArrayList<>();
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger renewCount = new AtomicInteger();
        private volatile LeaseRequest lastRequest;

        private FakeLeaseManager(Set<Long> unavailableRefs) {
            this(unavailableRefs, null, 0L, 0L, 0);
        }

        private FakeLeaseManager(Set<Long> unavailableRefs, RuntimeException renewFailure) {
            this(unavailableRefs, renewFailure, 0L, 0L, 0);
        }

        private FakeLeaseManager(Set<Long> unavailableRefs, List<String> events) {
            this(unavailableRefs, null, 0L, 0L, 0, events);
        }

        private FakeLeaseManager(
                Set<Long> unavailableRefs,
                RuntimeException renewFailure,
                long acquireDelayMillis) {
            this(unavailableRefs, renewFailure, acquireDelayMillis, 0L, 0);
        }

        private FakeLeaseManager(
                Set<Long> unavailableRefs,
                RuntimeException renewFailure,
                long acquireDelayMillis,
                long renewDelayMillis,
                int successfulRenewalsBeforeFailure) {
            this(unavailableRefs, renewFailure, acquireDelayMillis, renewDelayMillis,
                    successfulRenewalsBeforeFailure, null);
        }

        private FakeLeaseManager(
                Set<Long> unavailableRefs,
                RuntimeException renewFailure,
                long acquireDelayMillis,
                long renewDelayMillis,
                int successfulRenewalsBeforeFailure,
                List<String> events) {
            this.unavailableRefs = unavailableRefs;
            this.renewFailure = renewFailure;
            this.acquireDelayMillis = acquireDelayMillis;
            this.renewDelayMillis = renewDelayMillis;
            this.successfulRenewalsBeforeFailure = successfulRenewalsBeforeFailure;
            this.events = events;
        }

        @Override
        public Optional<LeaseHandle> tryAcquire(LeaseRequest request) {
            lastRequest = request;
            acquireRefs.add(request.leaseRef());
            if (events != null) {
                events.add("lease:" + request.leaseRef());
            }
            sleep(acquireDelayMillis);
            if (unavailableRefs.contains(request.leaseRef())) {
                return Optional.empty();
            }
            return Optional.of(new LeaseHandle(
                    request.leaseDomain(),
                    request.leaseRef(),
                    request.ownerId(),
                    request.ownerToken(),
                    1L,
                    request.leaseTermSeconds(),
                    request.reuseDelayMillis()));
        }

        @Override
        public boolean renew(LeaseHandle handle) {
            int attempt = renewCount.incrementAndGet();
            if (attempt <= successfulRenewalsBeforeFailure) {
                sleep(renewDelayMillis);
                return true;
            }
            if (renewFailure != null) {
                throw renewFailure;
            }
            return true;
        }

        @Override
        public boolean release(LeaseHandle handle) {
            releaseCount.incrementAndGet();
            return true;
        }

        private static void sleep(long delayMillis) {
            if (delayMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fake lease acquire interrupted", e);
            }
        }
    }
}
