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
@TableName("leases")
public class LeaseEntity {

    @TableId(value = "lease_id", type = IdType.AUTO)
    private Long leaseId;

    @TableField("lease_domain")
    private String leaseDomain;

    @TableField("lease_ref")
    private Long leaseRef;

    @TableField("lease_description")
    private String leaseDescription;

    @TableField("owner_id")
    private String ownerId;

    @TableField("owner_token")
    private String ownerToken;

    @TableField("fencing_token")
    private Long fencingToken;

    @TableField("renewed_at")
    private LocalDateTime renewedAt;

    @TableField("expired_at")
    private LocalDateTime expiredAt;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
