CREATE TABLE roles
(
    id      UUID         NOT NULL,
    name    VARCHAR(255) NOT NULL,
    version INTEGER,

    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE users
(
    id            UUID         NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    verified      BOOLEAN,
    created_at    TIMESTAMP(6) WITHOUT TIME ZONE,
    role_id       UUID,

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),

    CONSTRAINT fk_users_role
        FOREIGN KEY (role_id)
            REFERENCES roles (id)
);

CREATE SEQUENCE verification_tokens_seq
    START WITH 1
    INCREMENT BY 50;

CREATE TABLE verification_tokens
(
    id                 BIGINT NOT NULL,
    confirmation_token VARCHAR(255),
    user_id            UUID   NOT NULL,
    date_created       TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT pk_verification_tokens
        PRIMARY KEY (id),

    CONSTRAINT uq_verification_tokens_confirmation_token
        UNIQUE (confirmation_token),

    CONSTRAINT uq_verification_tokens_user
        UNIQUE (user_id),

    CONSTRAINT fk_verification_tokens_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
);

CREATE TABLE simulation_jobs
(
    id                UUID                        NOT NULL,
    job_id            VARCHAR(255)                NOT NULL,
    owner_id          UUID                        NOT NULL,
    directory_path    VARCHAR(255)                NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    status            VARCHAR(255),
    error_message     VARCHAR(1000),
    retry_count       INTEGER                     NOT NULL,
    started_at        TIMESTAMP(6) WITH TIME ZONE,
    completed_at      TIMESTAMP(6) WITH TIME ZONE,
    result_json       VARCHAR(10000),
    verilator_options TEXT,

    CONSTRAINT pk_simulation_jobs
        PRIMARY KEY (id),

    CONSTRAINT uq_simulation_jobs_job_id
        UNIQUE (job_id),

    CONSTRAINT fk_simulation_jobs_owner
        FOREIGN KEY (owner_id)
            REFERENCES users (id),

    CONSTRAINT ck_simulation_jobs_status
        CHECK (
            status IN (
                       'PENDING',
                       'RUNNING',
                       'COMPLETED',
                       'FAILED'
                )
            )
);

CREATE INDEX idx_simulation_jobs_status_started_at
    ON simulation_jobs (status, started_at);
