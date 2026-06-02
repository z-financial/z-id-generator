package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zed.idgen.snowflake.core.manager.mysql.entity.WorkerEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WorkerDao extends BaseMapper<WorkerEntity> {

    @Select("""
            SELECT *
            FROM workers
            WHERE domain_id = #{domainId}
              AND generator_id = #{generatorId}
            """)
    List<WorkerEntity> findByGenerator(
            @Param("domainId") long domainId,
            @Param("generatorId") long generatorId);
}
