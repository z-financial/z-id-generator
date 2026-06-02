package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.MysqlLeaseException;
import com.zed.idgen.snowflake.core.manager.mysql.LeaseEntity;
import org.apache.ibatis.session.SqlSession;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Lease DAO wrapper around MyBatis mapper SQL.
 *
 * Lease mutations use timed JDBC statements on the same SqlSession connection.
 * MyBatis annotation timeouts are static, but lease acquire/renew/release
 * should fail fast under the lease request timeout configured by the caller.
 */
public class LeaseDao {
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
            int leaseTermSeconds) {
        requirePositiveLeaseTerm(leaseTermSeconds);
        try (PreparedStatement statement = prepareLeaseStatement(INSERT_LEASE_SQL)) {
            statement.setString(1, leaseDomain);
            statement.setLong(2, leaseRef);
            statement.setString(3, leaseDescription);
            statement.setString(4, ownerId);
            statement.setString(5, ownerToken);
            statement.setInt(6, leaseTermSeconds);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw MysqlLeaseException.operationFailed("insert lease", e);
        }
    }

    public LeaseEntity selectLeaseForUpdate(String leaseDomain, long leaseRef) {
        return mapper.selectLeaseForUpdate(leaseDomain, leaseRef);
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
            int leaseTermSeconds) {
        requirePositiveLeaseTerm(leaseTermSeconds);
        try (PreparedStatement statement = prepareLeaseStatement(TAKEOVER_EXPIRED_LEASE_SQL)) {
            statement.setString(1, ownerId);
            statement.setString(2, ownerToken);
            statement.setInt(3, leaseTermSeconds);
            statement.setString(4, leaseDomain);
            statement.setLong(5, leaseRef);
            statement.setLong(6, passedFencingToken);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw MysqlLeaseException.operationFailed("takeover expired lease", e);
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
            int leaseTermSeconds) {
        requirePositiveLeaseTerm(leaseTermSeconds);
        try (PreparedStatement statement = prepareLeaseStatement(RENEW_LEASE_SQL)) {
            statement.setInt(1, leaseTermSeconds);
            statement.setString(2, leaseDomain);
            statement.setLong(3, leaseRef);
            statement.setString(4, ownerId);
            statement.setString(5, ownerToken);
            statement.setLong(6, fencingToken);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw MysqlLeaseException.operationFailed("renew lease", e);
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
            long fencingToken) {
        try (PreparedStatement statement = prepareLeaseStatement(RELEASE_LEASE_SQL)) {
            statement.setString(1, leaseDomain);
            statement.setLong(2, leaseRef);
            statement.setString(3, ownerId);
            statement.setString(4, ownerToken);
            statement.setLong(5, fencingToken);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw MysqlLeaseException.operationFailed("release lease", e);
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
}
