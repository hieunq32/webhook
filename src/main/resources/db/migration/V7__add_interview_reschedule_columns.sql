ALTER TABLE interview_slot
ADD COLUMN status_note TEXT;

ALTER TABLE interview_conversation
ADD COLUMN last_reschedule_reason TEXT;
