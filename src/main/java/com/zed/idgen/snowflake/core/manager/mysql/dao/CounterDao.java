package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zed.idgen.snowflake.core.manager.mysql.entity.CounterEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CounterDao extends BaseMapper<CounterEntity> {

    @Select("""
            SELECT *
            FROM counters
            WHERE counter_ref = #{counterRef}
            """)
    CounterEntity findByRef(@Param("counterRef") String counterRef);

    @Select("""
            SELECT *
            FROM counters
            WHERE counter_ref = #{counterRef}
            FOR UPDATE
            """)
    CounterEntity findByRefForUpdate(@Param("counterRef") String counterRef);

    @Update("""
            UPDATE counters
            SET next_value = next_value + 1
            WHERE counter_id = #{counterId}
              AND next_value = #{observedNextValue}
            """)
    int advanceIfCurrent(
            @Param("counterId") long counterId,
            @Param("observedNextValue") long observedNextValue);
}
