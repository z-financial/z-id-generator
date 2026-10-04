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
            ORDER BY worker_id ASC
            """)
    List<WorkerEntity> findByGenerator(
            @Param("domainId") long domainId,
            @Param("generatorId") long generatorId);

    /**
     * Returns a non-locking snapshot of workers whose lease is absent or reusable,
     * ordered by worker id. Lease acquisition remains the authoritative contention
     * check.
     */
    @Select("""
            SELECT w.*
            FROM workers w
            LEFT JOIN leases l
              ON l.lease_domain = #{leaseDomain}
             AND l.lease_ref = w.worker_id
            WHERE w.domain_id = #{domainId}
              AND w.generator_id = #{generatorId}
              AND (l.lease_id IS NULL OR l.reusable_at <= UTC_TIMESTAMP(3))
            ORDER BY w.worker_id ASC
            """)
    List<WorkerEntity> findEligibleByGenerator(
            @Param("domainId") long domainId,
            @Param("generatorId") long generatorId,
            @Param("leaseDomain") String leaseDomain);
}
