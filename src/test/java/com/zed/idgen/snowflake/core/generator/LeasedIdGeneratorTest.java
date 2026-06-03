package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.component.provider.IdProvider;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeasedIdGeneratorTest {

    @Test
    void validLeaseDelegatesGenerationAndLifecycle() {
        DefaultIdGenerator delegate = new DefaultIdGenerator(new FixedIdProvider(), 11L);
        LeaseInfo leaseInfo = leaseInfo();
        LeasedIdGenerator generator = new LeasedIdGenerator(delegate, System.nanoTime() + 1_000_000_000L, leaseInfo);

        assertEquals(11L, generator.getInstanceId());
        assertSame(leaseInfo, generator.getLeaseInfo());
        assertTrue(generator.start());
        assertTrue(generator.canGenerate());
        assertEquals(200L, generator.getUid());

        generator.renewLocalDeadline(System.nanoTime() + 1_500_000_000L);

        assertTrue(generator.stop());
        assertFalse(generator.canGenerate());
    }

    @Test
    void expiredLeaseCannotStartGenerateOrRenew() {
        LeasedIdGenerator generator = new LeasedIdGenerator(
                new DefaultIdGenerator(new FixedIdProvider(), 12L),
                System.nanoTime() - 1L,
                leaseInfo()
        );

        IdGenerationException startEx = assertThrows(IdGenerationException.class, generator::start);
        assertEquals(SnowflakeErrorCode.LeaseExpired, startEx.getErrorCode());
        assertFalse(generator.canGenerate());

        IdGenerationException renewEx = assertThrows(IdGenerationException.class,
                () -> generator.renewLocalDeadline(System.nanoTime() + 1_000_000_000L));
        assertEquals(SnowflakeErrorCode.LeaseExpired, renewEx.getErrorCode());
    }

    @Test
    void stopIfLocalExpiredMarksLeaseLostAndStopsDelegate() {
        LeasedIdGenerator generator = new LeasedIdGenerator(
                new DefaultIdGenerator(new FixedIdProvider(), 14L),
                System.nanoTime() - 1L,
                leaseInfo()
        );

        assertTrue(generator.stopIfLocalExpired());
        assertFalse(generator.canGenerate());

        IdGenerationException ex = assertThrows(IdGenerationException.class, generator::getUid);
        assertEquals(SnowflakeErrorCode.LeaseExpired, ex.getErrorCode());
    }

    @Test
    void secondGenerationGuardRejectsIdsProducedAfterDeadlinePasses() {
        SlowIdProvider provider = new SlowIdProvider();
        LeasedIdGenerator generator = new LeasedIdGenerator(
                new DefaultIdGenerator(provider, 13L),
                System.nanoTime() + 100_000_000L,
                leaseInfo()
        );
        generator.start();

        IdGenerationException ex = assertThrows(IdGenerationException.class, generator::getUid);

        assertEquals(SnowflakeErrorCode.LeaseExpired, ex.getErrorCode());
        assertEquals(1, provider.generatedCount);
    }

    private static LeaseInfo leaseInfo() {
        return new LeaseInfo("orders", 7L, "owner-a", "token-a", 1L);
    }

    private static final class FixedIdProvider implements IdProvider {
        @Override
        public long nextId() {
            return 200L;
        }

        @Override
        public long[] nextIds(int size) {
            return new long[size];
        }
    }

    private static final class SlowIdProvider implements IdProvider {
        private int generatedCount;

        @Override
        public long nextId() {
            generatedCount++;
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return 300L;
        }

        @Override
        public long[] nextIds(int size) {
            return new long[size];
        }
    }
}
