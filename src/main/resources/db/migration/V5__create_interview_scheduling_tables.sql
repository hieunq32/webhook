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
