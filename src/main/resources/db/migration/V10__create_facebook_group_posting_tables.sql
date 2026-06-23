CREATE TABLE facebook_group_target (
    id BIGSERIAL PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    group_reference VARCHAR(500) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    priority_order INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_facebook_group_target_active_priority
    ON facebook_group_target (active, priority_order, created_at);

CREATE TABLE facebook_group_post_history (
    id BIGSERIAL PRIMARY KEY,
    job_description_id BIGINT NOT NULL REFERENCES job_description(id),
    facebook_group_target_id BIGINT NOT NULL REFERENCES facebook_group_target(id),
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL,
    generated_content TEXT NOT NULL,
    error_reason TEXT,
    open_claw_response TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_facebook_group_post_history_group_day
    ON facebook_group_post_history (facebook_group_target_id, status, posted_at);

CREATE INDEX idx_facebook_group_post_history_job
    ON facebook_group_post_history (job_description_id, posted_at DESC);
