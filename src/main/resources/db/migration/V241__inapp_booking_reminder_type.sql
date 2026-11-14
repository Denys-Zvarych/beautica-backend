-- non-destructive
-- Reason: both re-added CHECKs are strictly looser than V221's for every existing row and every previous-app write (they only add the BOOKING_REMINDER literal); they are added NOT VALID here and validated in V242.
-- Phase 495 (NTF-48) — BOOKING_REMINDER: the registered client's 24 h reminder feed item, booking-keyed, dedup key
-- 'BOOKING_REMINDER:<bookingId>:<startsAt epoch seconds>' — the existing 3-segment '<TYPE>:<uuid>:<digits>' branch
-- of dedup_key_chk already admits it, and in_app_notification_shape_chk's ELSE branch (booking_id or appointment_id
-- required) already covers it, so the shape CHECK is untouched. Fix-forward on V221 (applied migrations are
-- immutable): type / dedup-key CHECKs are DROPped and re-ADDed under their ORIGINAL names with every V221 literal and
-- branch copied verbatim plus the new type. The TYPE literal set stays in sync BY HAND with InAppNotificationType
-- (guarded by InAppNotificationRepositoryIT and V241InAppBookingReminderTypeMigrationTest).
--
-- Same NOT VALID / VALIDATE split as V221 + V222: no table scan under ACCESS EXCLUSIVE here; V242 validates under
-- SHARE UPDATE EXCLUSIVE. lock_timeout bounds the wait for the catalog lock.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification
    DROP CONSTRAINT in_app_notification_type_chk,
    DROP CONSTRAINT in_app_notification_dedup_key_chk,
    ADD CONSTRAINT in_app_notification_type_chk CHECK (type IN (
        'BOOKING_CREATED','BOOKING_CANCELLED_BY_CLIENT','BOOKING_DECLINED',
        'BOOKING_NOT_COMPLETED','BOOKING_RESCHEDULED','REVIEW_REQUESTED',
        'BOOKING_CANCELLED_SALON_CLOSED','BOOKING_CANCELLED_MASTER_REMOVED','REVIEW_RECEIVED',
        'INVITE_ACCEPTED',
        'CATEGORY_REQUEST_APPROVED','CATEGORY_REQUEST_REJECTED',
        'SERVICE_TYPE_SUGGESTION_APPROVED','SERVICE_TYPE_SUGGESTION_REJECTED',
        'REPORT_CONTENT_REMOVED','REPORT_NO_VIOLATION','REPORT_REVIEWED','CONTENT_REMOVED',
        'REVIEW_ABOUT_YOU_REMOVED',
        'BOOKING_REMINDER')) NOT VALID,
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
            'BOOKING_REMINDER'])
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
                        ELSE false
                    END
            END
        AND (type NOT IN ('REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED',
                          'REVIEW_ABOUT_YOU_REMOVED')
             OR split_part(dedup_key, ':', 1) = type)
    ) NOT VALID;
