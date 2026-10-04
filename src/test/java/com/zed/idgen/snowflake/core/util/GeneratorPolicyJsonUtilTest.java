package com.zed.idgen.snowflake.core.util;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratorPolicyJsonUtilTest {

    @Test
    void policyEqualityIgnoresJsonFormattingAndPropertyOrder() {
        GeneratorPolicy expected = new GeneratorPolicy(
                3, IdRunOutPolicy.NONE, ClockRollbackPolicy.NONE);
        String reordered = """
                { "clockRollbackPolicy": "none", "maximumRetry": 3,
                  "idRunOutPolicy": "NONE" }
                """;

        assertEquals(expected, GeneratorPolicyJsonUtil.fromJson(reordered));
        assertEquals(expected, GeneratorPolicyJsonUtil.fromJson(
                GeneratorPolicyJsonUtil.toJson(expected)));
    }
}
