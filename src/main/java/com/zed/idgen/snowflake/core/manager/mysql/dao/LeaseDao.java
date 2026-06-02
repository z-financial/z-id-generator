package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.mysql.entity.LeaseEntity;
import org.apache.ibatis.session.SqlSession;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Lease DAO wrapper around MyBatis mapper SQL.
 *
 * Lease mutations use timed JDBC statements on the same SqlSession connection.
 * MyBatis annotation timeouts are static, but lease acquire/renew/release
 * should fail fast under the lease request timeout configured by the caller.
 */
public class LeaseDao {
    private static final String SELECT_LEASE_FOR_UPDATE_SQL = """
            SELECT *
            FROM leases
            WHERE lease_domain = ?
              AND lease_ref = ?
            FOR UPDATE
            """;

    private static final String SELECT_EXPIRED_SQL = """
            SELECT CASE WHEN expired_at <= UTC_TIMESTAMP(3) THEN 1 ELSE 0 END AS expired
            FROM leases
            WHERE lease_domain = ?
              AND lease_ref = ?
            """;

    private static final String INSERT_LEASE_SQL = """
            INSERT INTO leases (
                lease_domain,
                lease_ref,
                lease_description,
                owner_id,
                owner_token,
                fencing_token,
                renewed_at,
                expired_at
            )
            VALUES (?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(3), TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)))
            """;

    private static final String TAKEOVER_EXPIRED_LEASE_SQL = """
            UPDATE leases
            SET owner_id = ?,
                owner_token = ?,
                fencing_token = fencing_token + 1,
                renewed_at = UTC_TIMESTAMP(3),
                expired_at = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3))
            WHERE lease_domain = ?
              AND lease_ref = ?
              AND fencing_token = ?
              AND expired_at <= UTC_TIMESTAMP(3)
            """;

    private static final String RENEW_LEASE_SQL = """
            UPDATE leases
            SET renewed_at = UTC_TIMESTAMP(3),
                expired_at = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3))
            WHERE lease_domain = ?
              AND lease_ref = ?
              AND owner_id = ?
              AND owner_token = ?
              AND fencing_token = ?
              AND expired_at > UTC_TIMESTAMP(3)
            """;

    private static final String RELEASE_LEASE_SQL = """
            DELETE FROM leases
            WHERE lease_domain = ?
              AND lease_ref = ?
              AND owner_id = ?
              AND owner_token = ?
              AND fencing_token = ?
            """;

    private final SqlSession sqlSession;
    private final LeaseSqlMapper mapper;
    private final int leaseRequestTimeoutSeconds;

    LeaseDao(SqlSession sqlSession, int leaseRequestTimeoutSeconds) {
        this.sqlSession = sqlSession;
        this.mapper = sqlSession.getMapper(LeaseSqlMapper.class);
        this.leaseRequestTimeoutSeconds = leaseRequestTimeoutSeconds;
    }

    public int insertLease(
            String leaseDomain,
            long leaseRef,
            String leaseDescription,
            String ownerId,
            String ownerToken,
            int leaseTermSeconds) throws SQLException {
        requirePositiveLeaseTerm(leaseTermSeconds);
        try (PreparedStatement statement = prepareLeaseStatement(INSERT_LEASE_SQL)) {
            statement.setString(1, leaseDomain);
            statement.setLong(2, leaseRef);
            statement.setString(3, leaseDescription);
            statement.setString(4, ownerId);
            statement.setString(5, ownerToken);
            statement.setInt(6, leaseTermSeconds);
            return statement.executeUpdate();
        }
    }

    public LeaseEntity selectLeaseForUpdate(String leaseDomain, long leaseRef) throws SQLException {
        try (PreparedStatement statement = prepareLeaseStatement(SELECT_LEASE_FOR_UPDATE_SQL)) {
            statement.setString(1, leaseDomain);
            statement.setLong(2, leaseRef);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return mapLease(resultSet);
            }
        }
    }

    public boolean isExpired(String leaseDomain, long leaseRef) throws SQLException {
        try (PreparedStatement statement = prepareLeaseStatement(SELECT_EXPIRED_SQL)) {
            statement.setString(1, leaseDomain);
            statement.setLong(2, leaseRef);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt("expired") == 1;
            }
        }
    }

    public LeaseEntity findLease(String leaseDomain, long leaseRef) {
        return mapper.findLease(leaseDomain, leaseRef);
    }

    public int tryTakeoverExpiredLease(
            String leaseDomain,
            long leaseRef,
            String ownerId,
            String ownerToken,
            long passedFencingToken,
            int leaseTermSeconds) throws SQLException {
        requirePositiveLeaseTerm(leaseTermSeconds);
        try (PreparedStatement statement = prepareLeaseStatement(TAKEOVER_EXPIRED_LEASE_SQL)) {
            statement.setString(1, ownerId);
            statement.setString(2, ownerToken);
            statement.setInt(3, leaseTermSeconds);
            statement.setString(4, leaseDomain);
            statement.setLong(5, leaseRef);
            statement.setLong(6, passedFencingToken);
            return statement.executeUpdate();
        }
    }

    /**
     * Renew only when the caller still owns the lease and the DB lease has not
     * expired. The configured lease request timeout is applied to the JDBC
     * statement; callers should stop local renewal if this returns 0 or throws.
     */
    public int renewLease(
            String leaseDomain,
            long leaseRef,
            String ownerId,
            String ownerToken,
            long fencingToken,
            int leaseTermSeconds) throws SQLException {
        requirePositiveLeaseTerm(leaseTermSeconds);
        try (PreparedStatement statement = prepareLeaseStatement(RENEW_LEASE_SQL)) {
            statement.setInt(1, leaseTermSeconds);
            statement.setString(2, leaseDomain);
            statement.setLong(3, leaseRef);
            statement.setString(4, ownerId);
            statement.setString(5, ownerToken);
            statement.setLong(6, fencingToken);
            return statement.executeUpdate();
        }
    }

    /**
     * Release by full owner identity. If release returns 0, the caller should
     * stop renewing and let the DB lease expire naturally.
     */
    public int releaseLease(
            String leaseDomain,
            long leaseRef,
            String ownerId,
            String ownerToken,
            long fencingToken) throws SQLException {
        try (PreparedStatement statement = prepareLeaseStatement(RELEASE_LEASE_SQL)) {
            statement.setString(1, leaseDomain);
            statement.setLong(2, leaseRef);
            statement.setString(3, ownerId);
            statement.setString(4, ownerToken);
            statement.setLong(5, fencingToken);
            return statement.executeUpdate();
        }
    }

    private static void requirePositiveLeaseTerm(int leaseTermSeconds) {
        if (leaseTermSeconds <= 0) {
            throw new InvalidConfigException("lease term seconds must be positive");
        }
    }

    private PreparedStatement prepareLeaseStatement(String sql) throws SQLException {
        PreparedStatement statement = sqlSession.getConnection().prepareStatement(sql);
        statement.setQueryTimeout(leaseRequestTimeoutSeconds);
        return statement;
    }

    private static LeaseEntity mapLease(ResultSet resultSet) throws SQLException {
        LeaseEntity lease = new LeaseEntity();
        lease.setLeaseId(resultSet.getLong("lease_id"));
        lease.setLeaseDomain(resultSet.getString("lease_domain"));
        lease.setLeaseRef(resultSet.getLong("lease_ref"));
        lease.setLeaseDescription(resultSet.getString("lease_description"));
        lease.setOwnerId(resultSet.getString("owner_id"));
        lease.setOwnerToken(resultSet.getString("owner_token"));
        lease.setFencingToken(resultSet.getLong("fencing_token"));
        lease.setRenewedAt(getLocalDateTime(resultSet, "renewed_at"));
        lease.setExpiredAt(getLocalDateTime(resultSet, "expired_at"));
        lease.setCreatedAt(getLocalDateTime(resultSet, "created_at"));
        lease.setUpdatedAt(getLocalDateTime(resultSet, "updated_at"));
        return lease;
    }

    private static LocalDateTime getLocalDateTime(ResultSet resultSet, String columnName) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
