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

CREATE TABLE interview_conversation (
    id BIGSERIAL PRIMARY KEY,
    candidate_sender_id VARCHAR(100) NOT NULL,
    candidate_name VARCHAR(255) NOT NULL,
    applied_position VARCHAR(255) NOT NULL,
    department VARCHAR(255) NOT NULL,
    cv_score NUMERIC(6, 2) NOT NULL,
    state VARCHAR(40) NOT NULL,
    last_presented_slot_ids TEXT,
    selected_slot_id BIGINT,
    selection_lock_expires_at TIMESTAMP WITH TIME ZONE,
    hr_decision_deadline_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_interview_conversation_sender_state
    ON interview_conversation (candidate_sender_id, state, updated_at DESC);

CREATE TABLE interview_slot (
    id BIGSERIAL PRIMARY KEY,
    slot_key VARCHAR(255) NOT NULL UNIQUE,
    department VARCHAR(255) NOT NULL,
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL,
    locked_by_conversation_id BIGINT,
    lock_expires_at TIMESTAMP WITH TIME ZONE,
    booked_by_conversation_id BIGINT,
    booked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_interview_slot_status_start
    ON interview_slot (status, start_time);

CREATE TABLE hr_interview_notification (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES interview_conversation(id),
    hr_recipient_id VARCHAR(100) NOT NULL,
    interview_slot_id BIGINT NOT NULL REFERENCES interview_slot(id),
    status VARCHAR(30) NOT NULL,
    message_body TEXT NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    response_deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    responded_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_hr_notification_status_deadline
    ON hr_interview_notification (status, response_deadline_at);

CREATE TABLE candidate_profile (
    id BIGSERIAL PRIMARY KEY,
    sender_id VARCHAR(100) NOT NULL UNIQUE,
    candidate_name VARCHAR(255) NOT NULL,
    applied_position VARCHAR(255) NOT NULL,
    department VARCHAR(255) NOT NULL,
    cv_score NUMERIC(6, 2) NOT NULL,
    pass_cv BOOLEAN NOT NULL,
    assigned_hr_sender_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_candidate_profile_sender_pass_cv
    ON candidate_profile (sender_id, pass_cv);
