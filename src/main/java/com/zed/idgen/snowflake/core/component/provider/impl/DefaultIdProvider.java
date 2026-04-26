package com.zed.idgen.snowflake.core.component.provider.impl;

import com.zed.idgen.snowflake.core.constant.GeneratorInfo;
import com.zed.idgen.snowflake.core.constant.GeneratorPolicy;
import com.zed.idgen.snowflake.core.exception.ClockRollbackException;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.IdRunoutException;
import com.zed.idgen.snowflake.core.component.provider.IdProvider;
import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.strategy.StrategyExecutor;

public class DefaultIdProvider implements IdProvider {
    private final long worker;
    private final IdCodec idCodec;
    private final GeneratorInfo generatorInfo;
    private final GeneratorPolicy policy;
    private long time;
    private long sequence;

    // reusable array to avoid allocating an array on hot path of single id creation
    private final long[] singleId = new long[1];

    public DefaultIdProvider(long worker, IdCodec idCodec, GeneratorInfo generatorInfo) {
        this.worker = worker;
        this.idCodec = idCodec;
        this.generatorInfo = generatorInfo;
        this.policy = generatorInfo.generationPolicy();
    }

    private long[] doNextIds(int size, long[] arr) {
        if (size <= 0 || size > idCodec.getMaxSequence() + 1) {
            throw new IllegalArgumentException("id batch size exceeds max sequence: " + size);
        }

        for (int i = 0; i <= policy.maximumRetry(); i++) {
            long now = getCurrentTime();
            if (now > idCodec.getMaxTime()) {
                throw new IdGenerationException("time exceeds max time: " + now);
            }

            if (now < time) {
                // throw exception directly on the last retry
                if (i != policy.maximumRetry() && StrategyExecutor.clockRollbackStrategy(policy.clockRollbackPolicy())) {
                    continue;
                } else {
                    throw new ClockRollbackException(String.format("Clock roll back, last time: %d, now: %d", time, now));
                }
            }

            if (time == now) {
                if (sequence + size - 1 > idCodec.getMaxSequence()) {
                    // throw exception directly on the last retry
                    if (i != policy.maximumRetry() && StrategyExecutor.idRunOutStrategy(policy.idRunOutPolicy())) {
                        continue;
                    } else {
                        throw new IdRunoutException("id run out, time: " + time);
                    }
                }
            } else {
                sequence = 0;
                time = now;
            }

            long oldSequence = sequence;
            sequence = sequence + size;
            long[] result = arr != null ? arr : new long[size];

            for (int s = 0; s < size; ++s) {
                result[s] = idCodec.buildId(time, worker, oldSequence + s);
            }
            return result;
        }
        throw new IllegalStateException("unexpected max retry exceeded");
    }

    private long getCurrentTime() {
        long now = System.currentTimeMillis();
        return switch (idCodec.getTimeUnit()) {
            case SECOND -> (now - idCodec.getEpochMs()) / 1000;
            case MILLISECOND -> now - idCodec.getEpochMs();
        };}

    @Override
    public synchronized long nextId() {
        long[] result = doNextIds(1, singleId);
        return result[0];
    }

    @Override
    public synchronized long[] nextIds(int size) {
        return doNextIds(size, null);
    }


}
