package com.zed.idgen.snowflake.core.component;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import lombok.Getter;

public class IdCodec {
    private final IdDomainInfo idDomainInfo;

    @Getter
    private final long maxSequence;
    private final long sequenceMask;

    @Getter
    private final long maxWorker;
    private final int workerBitOffset;
    private final long workerMask;

    @Getter
    private final long maxTime;
    private final int timeBitOffset;
    private final long timeMask;

    public final static int BIT_COUNT = 64;

    public IdCodec(IdDomainInfo idDomainInfo) {
        this.idDomainInfo = idDomainInfo;

        if (idDomainInfo.prefixType() == null) {
            throw new InvalidConfigException("Unknown prefix type");
        }
        if (idDomainInfo.timeUnit() == null) {
            throw new InvalidConfigException("Unknown time unit");
        }
        int prefixBit = idDomainInfo.prefixType() == PrefixType.POSITIVE_SIGNAL ? 1 : 0;
        if (idDomainInfo.timeBit() + idDomainInfo.workerBit() + idDomainInfo.sequenceBit() + prefixBit != BIT_COUNT) {
            throw new InvalidConfigException(String.format("total bit count mismatch, prefixType: %s, timeBit: %d, workerBit: %d, sequenceBit: %d", idDomainInfo.prefixType(), idDomainInfo.timeBit(), idDomainInfo.workerBit(), idDomainInfo.sequenceBit()));
        }

        maxSequence = (1L << idDomainInfo.sequenceBit()) - 1L;
        sequenceMask = maxSequence;
        workerBitOffset = idDomainInfo.sequenceBit();
        maxWorker = (1L << idDomainInfo.workerBit()) - 1;
        workerMask = maxWorker << workerBitOffset;
        timeBitOffset = workerBitOffset + idDomainInfo.workerBit();
        maxTime = (1L << idDomainInfo.timeBit()) - 1L;
        timeMask = maxTime << timeBitOffset;
    }

    public long buildId(long time, long worker, long sequence) {
        if (time < 0 || worker < 0 || sequence < 0
                || time > maxTime || worker > maxWorker || sequence > maxSequence) {
            // params should be validated before calling this method
            // throw IllegalArgumentException for a possible code bug
            throw new IllegalArgumentException(String.format("unexpected illegal argument, time: %d, worker: %d, sequence: %d", time, worker, sequence));
        }
        return time << timeBitOffset | worker << workerBitOffset | sequence;
    }

    public long getTime(long id) {
        // fill with zero
        return (id & timeMask) >>> timeBitOffset;
    }

    public long getWorker(long id) {
        // fill with zero
        return (id & workerMask) >>> workerBitOffset;
    }

    public long getSequence(long id) {
        // fill with zero
        return id & sequenceMask;
    }

    public long getEpochMs() {
        return idDomainInfo.epochStartMs();
    }

    public TimeUnit getTimeUnit() {
        return idDomainInfo.timeUnit();
    }
}
