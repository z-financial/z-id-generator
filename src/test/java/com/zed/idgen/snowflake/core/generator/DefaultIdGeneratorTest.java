package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.component.provider.IdProvider;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultIdGeneratorTest {

    @Test
    void startStopAndCanGenerateFollowLifecycleContract() {
        DefaultIdGenerator generator = new DefaultIdGenerator(new FixedIdProvider(), 7L);

        assertEquals(7L, generator.getInstanceId());
        assertFalse(generator.canGenerate());
        assertEquals(SnowflakeErrorCode.GeneratorNotStarted,
                assertThrows(IdGenerationException.class, generator::getUid).getErrorCode());

        assertTrue(generator.start());
        assertFalse(generator.start());
        assertTrue(generator.canGenerate());
        assertEquals(100L, generator.getUid());

        assertTrue(generator.stop());
        assertFalse(generator.stop());
        assertFalse(generator.canGenerate());
        assertEquals(SnowflakeErrorCode.GeneratorStopped,
                assertThrows(IdGenerationException.class, generator::start).getErrorCode());
    }

    private static final class FixedIdProvider implements IdProvider {
        @Override
        public long nextId() {
            return 100L;
        }

        @Override
        public long[] nextIds(int size) {
            return new long[size];
        }
    }
}
