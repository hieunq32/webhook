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
