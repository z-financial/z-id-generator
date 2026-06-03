package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MysqlGeneratorRuntimeManagerTest {

    @Test
    void startSkipsLeaseUnavailableWorkerAndReleasesOnStop() {
        FakeLeaseManager leaseManager = new FakeLeaseManager(Set.of(1L));
        try (MysqlGeneratorRuntimeManager manager = new MysqlGeneratorRuntimeManager(
                new FakeRegistrationLoader(),
                leaseManager,
                "node-a",
                runtimeConfig())) {

            IdGenerator generator = manager.startGeneratorInstance(11L);

            assertNotNull(generator);
            assertTrue(generator.getUid() > 0);
            assertSame(generator, manager.getGeneratorInstance(11L, 2L));
            assertEquals(List.of(1L, 2L), leaseManager.acquireRefs);

            assertTrue(manager.stopGeneratorInstance(11L, 2L));
            assertEquals(1, leaseManager.releaseCount.get());
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
            return List.of(
                    new GeneratorProperties(domainInfo, generatorConfig, 1L, null),
                    new GeneratorProperties(domainInfo, generatorConfig, 2L, null));
        }
    }

    private static final class FakeLeaseManager implements LeaseManager {
        private final Set<Long> unavailableRefs;
        private final RuntimeException renewFailure;
        private final List<Long> acquireRefs = new ArrayList<>();
        private final AtomicInteger releaseCount = new AtomicInteger();

        private FakeLeaseManager(Set<Long> unavailableRefs) {
            this(unavailableRefs, null);
        }

        private FakeLeaseManager(Set<Long> unavailableRefs, RuntimeException renewFailure) {
            this.unavailableRefs = unavailableRefs;
            this.renewFailure = renewFailure;
        }

        @Override
        public Optional<LeaseHandle> tryAcquire(LeaseRequest request) {
            acquireRefs.add(request.leaseRef());
            if (unavailableRefs.contains(request.leaseRef())) {
                return Optional.empty();
            }
            return Optional.of(new LeaseHandle(
                    request.leaseDomain(),
                    request.leaseRef(),
                    request.ownerId(),
                    request.ownerToken(),
                    1L,
                    request.leaseTermSeconds()));
        }

        @Override
        public boolean renew(LeaseHandle handle) {
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
    }
}
