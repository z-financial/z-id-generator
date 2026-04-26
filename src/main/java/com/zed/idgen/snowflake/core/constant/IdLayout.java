//package com.zed.idgen.snowflake.core.constant;
//
//public class IdLayout {
//    public final static int SIGNAL_BIT = 1;
//    public final static int TIME_BIT = 41;
//    public final static int WORKER_BIT = 10;
//    public final static int SEQUENCE_BIT = 12;
//
//    public final static int SEQUENCE_OFFSET = 0;
//    public final static long SEQUENCE_MASK = (1L << SEQUENCE_BIT) -1L;
//    public final static int WORKER_OFFSET = SEQUENCE_BIT;
//    public final static long WORKER_MASK = ((1L << WORKER_BIT) - 1L) << WORKER_OFFSET;
//    public final static int TIME_OFFSET = WORKER_OFFSET + WORKER_BIT;
//    public final static long TIME_MASK = ((1L << TIME_BIT) - 1L) << TIME_OFFSET;
//    public final static int SIGNAL_OFFSET = TIME_OFFSET + TIME_BIT;
//    public final static long SIGNAL_MASK = 1L << SIGNAL_OFFSET;
//}
