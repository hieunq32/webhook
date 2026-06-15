CREATE TABLE job_description (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    requirements TEXT NOT NULL,
    salary VARCHAR(100) NOT NULL,
    location VARCHAR(255) NOT NULL,
    work_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    applicant_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE facebook_post (
    id BIGSERIAL PRIMARY KEY,
    job_description_id BIGINT NOT NULL REFERENCES job_description(id),
    facebook_post_id VARCHAR(255) NOT NULL UNIQUE,
    generated_content TEXT NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_job_description_status_created_at
    ON job_description (status, applicant_count, created_at);

CREATE INDEX idx_facebook_post_job_status
    ON facebook_post (job_description_id, status, posted_at DESC);

CREATE TABLE page_admin_account (
    id BIGSERIAL PRIMARY KEY,
    sender_id VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    role VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE page_admin_account_permission (
    page_admin_account_id BIGINT NOT NULL REFERENCES page_admin_account(id) ON DELETE CASCADE,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (page_admin_account_id, permission)
);

CREATE INDEX idx_page_admin_account_sender_active
    ON page_admin_account (sender_id, active);
