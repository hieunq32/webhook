ALTER TABLE interview_conversation
ADD COLUMN reschedule_source_state VARCHAR(40);

ALTER TABLE hr_interview_notification
ADD COLUMN notification_kind VARCHAR(40);

UPDATE hr_interview_notification
SET notification_kind = 'BOOKING_CONFIRMATION'
WHERE notification_kind IS NULL;

ALTER TABLE hr_interview_notification
ALTER COLUMN notification_kind SET NOT NULL;
