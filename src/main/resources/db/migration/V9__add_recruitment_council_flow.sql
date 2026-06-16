ALTER TABLE candidate_profile
ADD COLUMN job_description_id BIGINT;

ALTER TABLE interview_conversation
ADD COLUMN job_description_id BIGINT,
ADD COLUMN selected_council_id BIGINT,
ADD COLUMN selected_council_sender_id VARCHAR(100);

CREATE TABLE recruitment_council (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    representative_sender_id VARCHAR(100) NOT NULL,
    interviewer_one VARCHAR(255),
    interviewer_two VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_recruitment_council_representative_active
    ON recruitment_council (representative_sender_id, active);

CREATE TABLE job_description_council (
    id BIGSERIAL PRIMARY KEY,
    job_description_id BIGINT NOT NULL REFERENCES job_description(id) ON DELETE CASCADE,
    council_id BIGINT NOT NULL REFERENCES recruitment_council(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (job_description_id, council_id)
);

CREATE INDEX idx_job_description_council_job
    ON job_description_council (job_description_id);

CREATE INDEX idx_job_description_council_council
    ON job_description_council (council_id);

CREATE TABLE council_hiring_request (
    id BIGSERIAL PRIMARY KEY,
    council_id BIGINT NOT NULL REFERENCES recruitment_council(id),
    council_code VARCHAR(50) NOT NULL,
    council_name VARCHAR(255) NOT NULL,
    requester_sender_id VARCHAR(100) NOT NULL,
    request_content TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_council_hiring_request_status_created
    ON council_hiring_request (status, created_at DESC);
