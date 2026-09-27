CREATE TABLE IF NOT EXISTS domains (
    domain_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain_name VARCHAR(128) NOT NULL UNIQUE,
    prefix_type VARCHAR(32) NOT NULL,
    time_bit TINYINT NOT NULL,
    worker_bit TINYINT NOT NULL,
    sequence_bit TINYINT NOT NULL,
    epoch_start_ms BIGINT NOT NULL,
    time_unit VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS generator_configs (
    generator_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain_id BIGINT NOT NULL,
    generator_name VARCHAR(128) NOT NULL,
    policy JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_domain_generator_name(domain_id, generator_name),
    KEY idx_domain_id(domain_id)
);

CREATE TABLE IF NOT EXISTS workers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain_id BIGINT NOT NULL,
    generator_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_domain_worker(domain_id, worker_id),
    UNIQUE KEY uk_generator_worker(generator_id, worker_id),
    KEY idx_domain_generator(domain_id, generator_id)
);

CREATE TABLE IF NOT EXISTS leases (
    lease_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    lease_domain VARCHAR(64) NOT NULL,
    lease_ref BIGINT NOT NULL,
    lease_description VARCHAR(255),
    owner_id VARCHAR(128) NOT NULL,
    owner_token VARCHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL,
    renewed_at DATETIME(3) NOT NULL,
    expired_at DATETIME(3) NOT NULL,
    reusable_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_lease_reusable_after_expiry CHECK (reusable_at > expired_at),
    UNIQUE KEY uk_lease_resource(lease_domain, lease_ref),
    KEY idx_owner(owner_id),
    KEY idx_expired(expired_at, lease_id),
    KEY idx_reusable(reusable_at, lease_id)
);
