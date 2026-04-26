package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.model.GeneratorPolicy;

/**
 * Local file YAML shape for one registered generator.
 * Local registration maps one generator file to at most one worker, so the
 * file uses a single optional workerId. The loader maps it to the provider's
 * worker list after validating it against the filename worker id.
 */
record LocalFileGeneratorConfig(Long generatorId,
                                String generatorName,
                                Long domainId,
                                String domainName,
                                Long workerId,
                                GeneratorPolicy generationPolicy) {
}
