-- non-destructive
-- Reason: the three re-added CHECKs are strictly looser than V246/V253's for every existing row and every previous-app write (they only add the SECURITY_PASSWORD_RESET literal and its shape branch); they are added NOT VALID here and validated in V257.
-- Phone-first hardening (AUTH-D26 a) — the security notice after a successful password reset: one feed row for the
-- account itself (+ push when enabled), no referent, no masked contact (NULL; in_app_notification_masked_contact_chk
-- from V249 already admits NULL for every type). Dedup key '<TYPE>:<userId>:<epoch seconds>' — the existing 3-segment
-- ELSE branch of the dedup-key CHECK admits it.
-- Fix-forward (applied migrations are immutable): type + shape CHECKs are DROPped and re-ADDed under their ORIGINAL
-- names copied verbatim from V246 (their latest form), the dedup-key CHECK copied verbatim from V253 (its latest form),
-- each plus the one new type. The TYPE literal set stays in sync BY HAND with InAppNotificationType (guarded by
-- InAppNotificationRepositoryIT).
--
-- Same NOT VALID / VALIDATE split as V246 + V247: no table scan under ACCESS EXCLUSIVE here; V257 validates under
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
        'REVIEW_ABOUT_YOU_REMOVED',
        'BOOKING_REMINDER',
        'SECURITY_PHONE_CHANGED','SECURITY_EMAIL_CHANGED',
        'SECURITY_PASSWORD_RESET')) NOT VALID,
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
            WHEN 'SECURITY_PHONE_CHANGED' THEN
                booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
            WHEN 'SECURITY_EMAIL_CHANGED' THEN
                booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
            WHEN 'SECURITY_PASSWORD_RESET' THEN
                booking_id IS NULL AND appointment_id IS NULL AND salon_id IS NULL
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
            'REVIEW_ABOUT_YOU_REMOVED',
            'BOOKING_REMINDER',
            'SECURITY_PHONE_CHANGED', 'SECURITY_EMAIL_CHANGED',
            'SECURITY_PASSWORD_RESET'])
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
