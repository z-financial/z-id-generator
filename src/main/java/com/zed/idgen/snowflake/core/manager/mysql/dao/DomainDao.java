package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zed.idgen.snowflake.core.manager.mysql.entity.DomainEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DomainDao extends BaseMapper<DomainEntity> {

    @Select("SELECT * FROM domains WHERE domain_id = #{domainId}")
    DomainEntity findById(@Param("domainId") long domainId);

    @Select("""
            SELECT *
            FROM domains
            WHERE domain_name = #{domainName}
            """)
    DomainEntity findByName(@Param("domainName") String domainName);
}
