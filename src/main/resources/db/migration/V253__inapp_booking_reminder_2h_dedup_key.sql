-- non-destructive
-- Reason: the re-added dedup-key CHECK is strictly looser than V246's for every existing row and every previous-app write (it only adds a 4-segment branch that admits 'BOOKING_REMINDER:<uuid>:<epoch>:H2'); it is added NOT VALID here and validated in V254.
-- Phase B522 (NTF-48) — the registered 2 h reminder now also writes a BOOKING_REMINDER feed row. Its dedup key carries
-- the window ('BOOKING_REMINDER:<bookingId>:<startsAt epoch s>:H2') so it never collides with the 24 h row of the same
-- booking and start ('BOOKING_REMINDER:<bookingId>:<startsAt epoch s>', unchanged).
-- Fix-forward (applied migrations are immutable): only in_app_notification_dedup_key_chk is DROPped and re-ADDed under
-- its ORIGINAL name, copied verbatim from V246 plus one 'WHEN 4' branch restricted to BOOKING_REMINDER with a literal
-- 'H2' 4th segment. Every other type's rule, the type CHECK and the shape CHECK are untouched.
--
-- Same NOT VALID / VALIDATE split as V246 + V247: no table scan under ACCESS EXCLUSIVE here; V254 validates under
-- SHARE UPDATE EXCLUSIVE. lock_timeout bounds the wait for the catalog lock.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification
    DROP CONSTRAINT in_app_notification_dedup_key_chk,
    ADD CONSTRAINT in_app_notification_dedup_key_chk CHECK (
        split_part(dedup_key, ':', 1) = ANY (ARRAY[
            'BOOKING_CREATED', 'BOOKING_CANCELLED_BY_CLIENT', 'BOOKING_DECLINED',
            'BOOKING_NOT_COMPLETED', 'BOOKING_RESCHEDULED', 'REVIEW_REQUESTED',
            'BOOKING_CANCELLED_SALON_CLOSED', 'BOOKING_CANCELLED_MASTER_REMOVED',
            'REVIEW_RECEIVED', 'INVITE_ACCEPTED',
            'CATEGORY_REQUEST_APPROVED', 'CATEGORY_REQUEST_REJECTED',
            'SERVICE_TYPE_SUGGESTION_APPROVED', 'SERVICE_TYPE_SUGGESTION_REJECTED',
            'REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED',
            'REVIEW_ABOUT_YOU_REMOVED',
            'BOOKING_REMINDER',
            'SECURITY_PHONE_CHANGED', 'SECURITY_EMAIL_CHANGED'])
        AND CASE
                WHEN split_part(dedup_key, ':', 1) LIKE 'CATEGORY_REQUEST\_%' THEN
                    dedup_key ~ '^CATEGORY_REQUEST_(APPROVED|REJECTED):[0-9]{1,19}$'
                WHEN split_part(dedup_key, ':', 1) LIKE 'SERVICE_TYPE_SUGGESTION\_%' THEN
                    dedup_key ~ '^SERVICE_TYPE_SUGGESTION_(APPROVED|REJECTED):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                WHEN split_part(dedup_key, ':', 1) LIKE 'REPORT\_%' OR split_part(dedup_key, ':', 1) = 'CONTENT_REMOVED'
                     OR split_part(dedup_key, ':', 1) = 'REVIEW_ABOUT_YOU_REMOVED' THEN
                    dedup_key ~ '^(REPORT_CONTENT_REMOVED|REPORT_NO_VIOLATION|REPORT_REVIEWED|CONTENT_REMOVED|REVIEW_ABOUT_YOU_REMOVED):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                ELSE
                    CASE array_length(string_to_array(dedup_key, ':'), 1)
                        WHEN 2 THEN
                            split_part(dedup_key, ':', 2) ~
                                '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                        WHEN 3 THEN
                            split_part(dedup_key, ':', 2) ~
                                '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                            AND split_part(dedup_key, ':', 3) ~
                                '^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9]{1,10})$'
                        WHEN 4 THEN
                            split_part(dedup_key, ':', 1) = 'BOOKING_REMINDER'
                            AND split_part(dedup_key, ':', 2) ~
                                '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                            AND split_part(dedup_key, ':', 3) ~ '^[0-9]{1,10}$'
                            AND split_part(dedup_key, ':', 4) = 'H2'
                        ELSE false
                    END
            END
        AND (type NOT IN ('REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED',
                          'REVIEW_ABOUT_YOU_REMOVED')
             OR split_part(dedup_key, ':', 1) = type)
    ) NOT VALID;
