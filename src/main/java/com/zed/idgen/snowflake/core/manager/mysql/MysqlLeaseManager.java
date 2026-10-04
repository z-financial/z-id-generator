package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.MysqlLeaseException;
import com.zed.idgen.snowflake.core.exception.MysqlLeaseTimeoutException;
import com.zed.idgen.snowflake.core.manager.mysql.dao.LeaseDao;
import com.zed.idgen.snowflake.core.manager.mysql.dao.LeaseDao.LeaseTimeState;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoSession;
import com.zed.idgen.snowflake.core.manager.mysql.entity.LeaseEntity;
import com.zed.idgen.snowflake.core.util.MysqlExceptionUtil;
import org.apache.ibatis.exceptions.PersistenceException;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * MySQL-backed lease manager.
 *
 * DAO methods execute SQL. This layer owns lease semantics: duplicate insert is
 * a normal acquire miss, affectedRows == 0 means stale/lost ownership, and SQL
 * failures are converted to project lease exceptions. Acquire uses an explicit
 * transaction; renew/release are single-statement auto-commit operations.
 */
public class MysqlLeaseManager implements LeaseManager {
    private final MysqlDaoFactory daoFactory;

    public MysqlLeaseManager(MysqlDaoFactory daoFactory) {
        if (daoFactory == null) {
            throw new InvalidConfigException("mysql dao factory must not be null");
        }
        this.daoFactory = daoFactory;
    }

    @Override
    public Optional<LeaseHandle> tryAcquire(LeaseRequest request) {
        try (MysqlDaoSession session = daoFactory.openSession(false)) {
            try {
                Optional<LeaseHandle> result = tryAcquire(session.leaseDao(), request);
                session.commit();
                return result;
            } catch (SQLException | PersistenceException e) {
                rollbackQuietly(session);
                throw convertDbException("try acquire lease", e);
            } catch (RuntimeException e) {
                rollbackQuietly(session);
                throw e;
            }
        }
    }

    @Override
    public boolean renew(LeaseHandle handle) {
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            try {
                int affected = session.leaseDao().renewLease(
                        handle.leaseDomain(),
                        handle.leaseRef(),
                        handle.ownerId(),
                        handle.ownerToken(),
                        handle.fencingToken(),
                        handle.leaseTermSeconds(),
                        handle.reuseDelayMillis());
                return affected == 1;
            } catch (SQLException | PersistenceException e) {
                throw convertDbException("renew lease", e);
            }
        }
    }

    @Override
    public boolean release(LeaseHandle handle) {
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            try {
                int affected = session.leaseDao().releaseLease(
                        handle.leaseDomain(),
                        handle.leaseRef(),
                        handle.ownerId(),
                        handle.ownerToken(),
                        handle.fencingToken(),
                        handle.reuseDelayMillis());
                return affected == 1;
            } catch (SQLException | PersistenceException e) {
                throw convertDbException("release lease", e);
            }
        }
    }

    private Optional<LeaseHandle> tryAcquire(
            LeaseDao leaseDao,
            LeaseRequest request) throws SQLException {
        /*
         * The acquire path is intentionally ordered around the current DB row:
         * missing row -> insert first, because that is the cheapest normal
         * first-acquire path and the unique key turns concurrent inserts into a
         * clean acquisition miss.
         *
         * Existing row -> expiration and reusability are deliberately separate.
         * Expiration stops renewal by the old owner. Reusability comes later,
         * after one Snowflake time bucket plus the configured clock-skew window
         * and safety margin. Only then may a fresh provider restart sequence 0.
         * The takeover SQL rechecks both the old fencing token and reusable_at,
         * so a concurrent owner change cannot be overwritten.
         *
         * Non-expired row -> return the current handle only when owner id,
         * owner token, and fencing token all match. This gives idempotent retry
         * without accidentally reviving an old owner after takeover advanced
         * the fencing token.
         */
        LeaseEntity lease = leaseDao.selectLeaseForUpdate(request.leaseDomain(), request.leaseRef());
        if (lease == null) {
            return insertNewLease(leaseDao, request);
        }

        LeaseTimeState timeState = leaseDao.selectLeaseTimeState(
                request.leaseDomain(), request.leaseRef());
        if (timeState == null) {
            return Optional.empty();
        }
        if (!timeState.expired() && isSameOwner(request, lease)) {
            return Optional.of(toHandle(request, lease.getFencingToken()));
        }
        if (timeState.reusable()) {
            return takeoverReusableLease(leaseDao, request, lease);
        }
        return Optional.empty();
    }

    private Optional<LeaseHandle> insertNewLease(
            LeaseDao leaseDao,
            LeaseRequest request) throws SQLException {
        try {
            int affected = leaseDao.insertLease(
                    request.leaseDomain(),
                    request.leaseRef(),
                    request.leaseDescription(),
                    request.ownerId(),
                    request.ownerToken(),
                    request.leaseTermSeconds(),
                    request.reuseDelayMillis());
            return affected == 1 ? Optional.of(toHandle(request, 0L)) : Optional.empty();
        } catch (SQLException e) {
            if (MysqlExceptionUtil.isDuplicateKey(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private Optional<LeaseHandle> takeoverReusableLease(
            LeaseDao leaseDao,
            LeaseRequest request,
            LeaseEntity lease) throws SQLException {
        long previousFencingToken = lease.getFencingToken();
        int affected = leaseDao.tryTakeoverReusableLease(
                request.leaseDomain(),
                request.leaseRef(),
                request.ownerId(),
                request.ownerToken(),
                previousFencingToken,
                request.leaseTermSeconds(),
                request.reuseDelayMillis());
        return affected == 1 ? Optional.of(toHandle(request, previousFencingToken + 1)) : Optional.empty();
    }

    private static LeaseHandle toHandle(LeaseRequest request, long fencingToken) {
        return new LeaseHandle(
                request.leaseDomain(),
                request.leaseRef(),
                request.ownerId(),
                request.ownerToken(),
                fencingToken,
                request.leaseTermSeconds(),
                request.reuseDelayMillis());
    }

    private static boolean isSameOwner(LeaseRequest request, LeaseEntity lease) {
        return request.fencingToken() != null
                && Objects.equals(request.ownerId(), lease.getOwnerId())
                && Objects.equals(request.ownerToken(), lease.getOwnerToken())
                && Objects.equals(request.fencingToken(), lease.getFencingToken());
    }

    private static RuntimeException convertDbException(String operation, Throwable cause) {
        if (MysqlExceptionUtil.isTimeout(cause)) {
            return MysqlLeaseTimeoutException.operationTimeout(operation, cause);
        }
        return MysqlLeaseException.operationFailed(operation, cause);
    }

    private static void rollbackQuietly(MysqlDaoSession session) {
        try {
            session.rollback();
        } catch (RuntimeException ignored) {
            // Preserve the original lease failure. Rollback failure is secondary.
        }
    }
}
