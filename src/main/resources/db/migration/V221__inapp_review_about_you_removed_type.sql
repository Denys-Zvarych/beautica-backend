-- non-destructive
-- Reason: the three re-added CHECKs are strictly looser than V219's for every existing row and every previous-app write (they only add the REVIEW_ABOUT_YOU_REMOVED literal and its branch); they are added NOT VALID here and validated in V222.
-- Phase 415 follow-up — REVIEW_ABOUT_YOU_REMOVED: the SUBJECT of a removed review (the reviewed provider: an
-- independent master, or a salon's owner + active admins; the reviewed client for a removed client review) gets a
-- neutral feed row with no reporter identity and no review text. moderated_target_type is REVIEW or CLIENT_REVIEW.
-- Fix-forward on V219 (applied on local dev DBs, so a new version): type / shape / dedup-key CHECKs are DROPped and
-- re-ADDed under their ORIGINAL names with every V219 literal and branch copied verbatim plus the new type;
-- chk_inapp_moderated_target_type is unchanged. The TYPE literal set stays in sync BY HAND with
-- in_app_notification_type_chk and InAppNotificationType (guarded by InAppNotificationRepositoryIT).
--
-- Same NOT VALID / VALIDATE split as V219 + V220: no table scan under ACCESS EXCLUSIVE here; V222 validates under
-- SHARE UPDATE EXCLUSIVE. lock_timeout bounds the wait for the catalog lock.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification
    DROP CONSTRAINT in_app_notification_type_chk,
    DROP CONSTRAINT in_app_notification_shape_chk,
    DROP CONSTRAINT in_app_notification_dedup_key_chk,
    ADD CONSTRAINT in_app_notification_type_chk CHECK (type IN (
        'BOOKING_CREATED','BOOKING_CANCELLED_BY_CLIENT','BOOKING_DECLINED',
        'BOOKING_NOT_COMPLETED','BOOKING_RESCHEDULED','REVIEW_REQUESTED',
        'BOOKING_CANCELLED_SALON_CLOSED','BOOKING_CANCELLED_MASTER_REMOVED','REVIEW_RECEIVED',
        'INVITE_ACCEPTED',
        'CATEGORY_REQUEST_APPROVED','CATEGORY_REQUEST_REJECTED',
        'SERVICE_TYPE_SUGGESTION_APPROVED','SERVICE_TYPE_SUGGESTION_REJECTED',
        'REPORT_CONTENT_REMOVED','REPORT_NO_VIOLATION','REPORT_REVIEWED','CONTENT_REMOVED',
        'REVIEW_ABOUT_YOU_REMOVED')) NOT VALID,
    ADD CONSTRAINT in_app_notification_shape_chk CHECK (
        CASE type
            WHEN 'INVITE_ACCEPTED' THEN
                category_request_id IS NULL AND service_type_suggestion_id IS NULL
            WHEN 'CATEGORY_REQUEST_APPROVED' THEN
                category_request_id IS NOT NULL AND service_type_suggestion_id IS NULL
                AND booking_id IS NULL AND appointment_id IS NULL
            WHEN 'CATEGORY_REQUEST_REJECTED' THEN
                category_request_id IS NOT NULL AND service_type_suggestion_id IS NULL
                AND booking_id IS NULL AND appointment_id IS NULL
            WHEN 'SERVICE_TYPE_SUGGESTION_APPROVED' THEN
                service_type_suggestion_id IS NOT NULL AND category_request_id IS NULL
                AND booking_id IS NULL AND appointment_id IS NULL
            WHEN 'SERVICE_TYPE_SUGGESTION_REJECTED' THEN
                service_type_suggestion_id IS NOT NULL AND category_request_id IS NULL
                AND booking_id IS NULL AND appointment_id IS NULL
            WHEN 'REPORT_CONTENT_REMOVED' THEN
                moderated_target_type IS NOT NULL
                AND booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL AND subject_user_id IS NULL
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
            WHEN 'REPORT_NO_VIOLATION' THEN
                moderated_target_type IS NOT NULL
                AND booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL AND subject_user_id IS NULL
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
            WHEN 'REPORT_REVIEWED' THEN
                moderated_target_type IS NOT NULL
                AND booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL AND subject_user_id IS NULL
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
            WHEN 'CONTENT_REMOVED' THEN
                moderated_target_type IS NOT NULL
                AND moderated_target_type IN ('REVIEW', 'CLIENT_REVIEW', 'PORTFOLIO_PHOTO')
                AND booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL AND subject_user_id IS NULL
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
            WHEN 'REVIEW_ABOUT_YOU_REMOVED' THEN
                moderated_target_type IS NOT NULL
                AND moderated_target_type IN ('REVIEW', 'CLIENT_REVIEW')
                AND booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL AND subject_user_id IS NULL
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
            ELSE (booking_id IS NOT NULL OR appointment_id IS NOT NULL)
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
        END
        AND (moderated_target_type IS NULL
             OR type IN ('REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED',
                         'REVIEW_ABOUT_YOU_REMOVED'))
    ) NOT VALID,
    ADD CONSTRAINT in_app_notification_dedup_key_chk CHECK (
        split_part(dedup_key, ':', 1) = ANY (ARRAY[
            'BOOKING_CREATED', 'BOOKING_CANCELLED_BY_CLIENT', 'BOOKING_DECLINED',
            'BOOKING_NOT_COMPLETED', 'BOOKING_RESCHEDULED', 'REVIEW_REQUESTED',
            'BOOKING_CANCELLED_SALON_CLOSED', 'BOOKING_CANCELLED_MASTER_REMOVED',
            'REVIEW_RECEIVED', 'INVITE_ACCEPTED',
            'CATEGORY_REQUEST_APPROVED', 'CATEGORY_REQUEST_REJECTED',
            'SERVICE_TYPE_SUGGESTION_APPROVED', 'SERVICE_TYPE_SUGGESTION_REJECTED',
            'REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED',
            'REVIEW_ABOUT_YOU_REMOVED'])
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
