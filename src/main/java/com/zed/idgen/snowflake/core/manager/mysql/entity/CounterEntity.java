package com.zed.idgen.snowflake.core.manager.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("counters")
public class CounterEntity {
    private static final String WORKER_ID_DOMAIN_PREFIX = "worker-id:domain:";

    @TableId(value = "counter_id", type = IdType.AUTO)
    private Long counterId;

    @TableField(value = "counter_ref", updateStrategy = FieldStrategy.NEVER)
    private String counterRef;

    @TableField("counter_name")
    private String counterName;

    @TableField("next_value")
    private Long nextValue;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    public static String workerIdDomainRef(long domainId) {
        return WORKER_ID_DOMAIN_PREFIX + domainId;
    }
}
