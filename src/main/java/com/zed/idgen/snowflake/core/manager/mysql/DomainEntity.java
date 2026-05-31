package com.zed.idgen.snowflake.core.manager.mysql;

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
@TableName("domain")
public class DomainEntity {

    @TableId(value = "domain_id", type = IdType.AUTO)
    private Long domainId;

    @TableField("domain_name")
    private String domainName;

    @TableField("prefix_type")
    private DomainPrefixType prefixType;

    @TableField("time_bit")
    private Integer timeBit;

    @TableField("worker_bit")
    private Integer workerBit;

    @TableField("sequence_bit")
    private Integer sequenceBit;

    @TableField("epoch_start_ms")
    private Long epochStartMs;

    @TableField("time_unit")
    private DomainTimeUnit timeUnit;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
