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
