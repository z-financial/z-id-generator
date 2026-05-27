package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.GeneratorParam;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.GenerationPolicyConfig;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.GeneratorConfig;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.IdDomainConfig;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigRegistrationLoaderTest {

    @Test
    void appliesDefaultsAndAllocatesMissingWorkerIds() {
        ConfigRegistrationLoader loader = new ConfigRegistrationLoader(config(
                domain(null, null),
                List.of(
                        generator(11L, "order", null, null),
                        generator(12L, "payment", null, 2L),
                        generator(13L, "invoice", policy(0, null, null), null)
                )
        ));

        IdDomainInfo domainInfo = loader.getDomainInfo();
        assertEquals(PrefixType.POSITIVE_SIGNAL, domainInfo.prefixType());
        assertEquals(TimeUnit.MILLISECOND, domainInfo.timeUnit());

        GeneratorProperties order = loader.loadGenerator(new GeneratorParam(11L, null));
        assertEquals(List.of(0L), order.workerIds());
        assertEquals(1, order.generatorConfig().generationPolicy().maximumRetry());
        assertEquals(IdRunOutPolicy.NONE, order.generatorConfig().generationPolicy().idRunOutPolicy());
        assertEquals(ClockRollbackPolicy.NONE, order.generatorConfig().generationPolicy().clockRollbackPolicy());

        GeneratorProperties payment = loader.loadGenerator(new GeneratorParam(null, "payment"));
        assertEquals(List.of(2L), payment.workerIds());

        GeneratorProperties invoice = loader.loadGenerator(new GeneratorParam(13L, "invoice"));
        assertEquals(List.of(1L), invoice.workerIds());
        assertEquals(0, invoice.generatorConfig().generationPolicy().maximumRetry());
    }

    @Test
    void rejectsDuplicateGeneratorId() {
        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        domain(null, null),
                        List.of(
                                generator(11L, "order", null, null),
                                generator(11L, "payment", null, null)
                        )
                )));

        assertTrue(ex.getMessage().contains("duplicated generator id"));
    }

    @Test
    void rejectsDuplicateGeneratorName() {
        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        domain(null, null),
                        List.of(
                                generator(11L, "order", null, null),
                                generator(12L, "order", null, null)
                        )
                )));

        assertTrue(ex.getMessage().contains("duplicated generator name"));
    }

    @Test
    void rejectsDuplicateExplicitWorkerId() {
        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        domain(null, null),
                        List.of(
                                generator(11L, "order", null, 7L),
                                generator(12L, "payment", null, 7L)
                        )
                )));

        assertTrue(ex.getMessage().contains("duplicated worker id"));
    }

    @Test
    void rejectsWorkerIdOutsideDomainRange() {
        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        domain(null, null),
                        List.of(generator(11L, "order", null, 1024L))
                )));

        assertTrue(ex.getMessage().contains("worker id exceeds domain worker range"));
    }

    @Test
    void rejectsMissingRequiredDomainIdentity() {
        assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        new IdDomainConfig(null, "orders", null, 41, 10, 12, 1_700_000_000_000L, null),
                        List.of(generator(11L, "order", null, null))
                )));

        assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        new IdDomainConfig(100L, " ", null, 41, 10, 12, 1_700_000_000_000L, null),
                        List.of(generator(11L, "order", null, null))
                )));
    }

    @Test
    void rejectsGeneratorParamIdAndNameMismatch() {
        ConfigRegistrationLoader loader = new ConfigRegistrationLoader(config(
                domain(null, null),
                List.of(generator(11L, "order", null, null))
        ));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> loader.loadGenerator(new GeneratorParam(11L, "payment")));

        assertTrue(ex.getMessage().contains("generator name mismatch"));
    }

    private static ConfigRuntimeConfig config(IdDomainConfig domainInfo, List<GeneratorConfig> generators) {
        return new ConfigRuntimeConfig(domainInfo, generators);
    }

    private static IdDomainConfig domain(PrefixType prefixType, TimeUnit timeUnit) {
        return new IdDomainConfig(
                100L,
                "orders",
                prefixType,
                41,
                10,
                12,
                1_700_000_000_000L,
                timeUnit
        );
    }

    private static GeneratorConfig generator(Long generatorId,
                                             String generatorName,
                                             GenerationPolicyConfig policy,
                                             Long workerId) {
        return new GeneratorConfig(generatorId, generatorName, policy, workerId);
    }

    private static GenerationPolicyConfig policy(Integer maximumRetry,
                                                 IdRunOutPolicy idRunOutPolicy,
                                                 ClockRollbackPolicy clockRollbackPolicy) {
        return new GenerationPolicyConfig(maximumRetry, idRunOutPolicy, clockRollbackPolicy);
    }
}
