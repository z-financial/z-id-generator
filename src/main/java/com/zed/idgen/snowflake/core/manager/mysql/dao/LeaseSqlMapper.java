package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zed.idgen.snowflake.core.manager.mysql.LeaseEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LeaseSqlMapper extends BaseMapper<LeaseEntity> {

    @Select("""
            SELECT *
            FROM leases
            WHERE lease_domain = #{leaseDomain}
              AND lease_ref = #{leaseRef}
            FOR UPDATE
            """)
    LeaseEntity selectLeaseForUpdate(
            @Param("leaseDomain") String leaseDomain,
            @Param("leaseRef") long leaseRef);

    @Select("""
            SELECT *
            FROM leases
            WHERE lease_domain = #{leaseDomain}
              AND lease_ref = #{leaseRef}
            """)
    LeaseEntity findLease(
            @Param("leaseDomain") String leaseDomain,
            @Param("leaseRef") long leaseRef);
}
