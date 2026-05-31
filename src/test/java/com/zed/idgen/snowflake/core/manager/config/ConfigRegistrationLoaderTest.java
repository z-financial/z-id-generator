package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
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

        GeneratorProperties order = loader.loadGeneratorProperties(11L).getFirst();
        assertEquals(0L, order.workerId());
        assertEquals(1, order.generatorConfig().generationPolicy().maximumRetry());
        assertEquals(IdRunOutPolicy.NONE, order.generatorConfig().generationPolicy().idRunOutPolicy());
        assertEquals(ClockRollbackPolicy.NONE, order.generatorConfig().generationPolicy().clockRollbackPolicy());

        GeneratorProperties payment = loader.loadGeneratorProperties(loader.resolveGeneratorId("payment")).getFirst();
        assertEquals(2L, payment.workerId());

        GeneratorProperties invoice = loader.loadGeneratorProperties(13L).getFirst();
        assertEquals(1L, invoice.workerId());
        assertEquals(0, invoice.generatorConfig().generationPolicy().maximumRetry());
    }

    @Test
    void loadsMultipleWorkerBindingsUnderSameGeneratorConfig() {
        ConfigRegistrationLoader loader = new ConfigRegistrationLoader(config(
                domain(null, null),
                List.of(
                        generator(11L, "order", null, 1L),
                        generator(11L, "order", null, 2L),
                        generator(11L, "order", null, 3L)
                )
        ));

        List<GeneratorProperties> properties = loader.loadGeneratorProperties(11L);

        assertEquals(List.of(1L, 2L, 3L), properties.stream().map(GeneratorProperties::workerId).toList());
    }

    @Test
    void rejectsSameGeneratorIdWithDifferentName() {
        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        domain(null, null),
                        List.of(
                                generator(11L, "order", null, null),
                                generator(11L, "payment", null, null)
                        )
                )));

        assertTrue(ex.getMessage().contains("same generator id has different names"));
    }

    @Test
    void rejectsSameGeneratorIdWithDifferentPolicy() {
        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new ConfigRegistrationLoader(config(
                        domain(null, null),
                        List.of(
                                generator(11L, "order", null, 1L),
                                generator(11L, "order", policy(2, null, null), 2L)
                        )
                )));

        assertTrue(ex.getMessage().contains("different generation policies"));
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
    void resolvesGeneratorNameToId() {
        ConfigRegistrationLoader loader = new ConfigRegistrationLoader(config(
                domain(null, null),
                List.of(generator(11L, "order", null, null))
        ));

        assertEquals(11L, loader.resolveGeneratorId(" order "));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> loader.resolveGeneratorId("payment"));
        assertTrue(ex.getMessage().contains("generator config not found by name"));
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
