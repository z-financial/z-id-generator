package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zed.idgen.snowflake.core.manager.mysql.entity.GeneratorConfigEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GeneratorConfigDao extends BaseMapper<GeneratorConfigEntity> {

    @Select("""
            SELECT *
            FROM generator_configs
            WHERE domain_id = #{domainId}
              AND generator_id = #{generatorId}
            """)
    GeneratorConfigEntity findById(
            @Param("domainId") long domainId,
            @Param("generatorId") long generatorId);

    @Select("""
            SELECT generator_id
            FROM generator_configs
            WHERE domain_id = #{domainId}
              AND generator_name = #{generatorName}
            """)
    Long findIdByName(
            @Param("domainId") long domainId,
            @Param("generatorName") String generatorName);
}
