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

/**
 * Persistence representation of one durable lease resource.
 *
 * <p>This class only maps the {@code leases} table. Ownership rules belong to
 * {@link com.zed.idgen.snowflake.core.manager.mysql.LeaseManager}; database
 * mutations that enforce those rules belong to the lease DAO.
 */
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

    /** Stable application-instance identity, primarily for observability. */
    @TableField("owner_id")
    private String ownerId;

    /** Unique identity of the current runtime ownership session. */
    @TableField("owner_token")
    private String ownerToken;

    /** Monotonic generation advanced whenever the resource is taken over. */
    @TableField("fencing_token")
    private Long fencingToken;

    @TableField("renewed_at")
    private LocalDateTime renewedAt;

    @TableField("expired_at")
    private LocalDateTime expiredAt;

    /** Earliest database time at which a new generator may reset its sequence. */
    @TableField("reusable_at")
    private LocalDateTime reusableAt;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
