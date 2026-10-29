-- non-destructive
-- Reason: the four re-added CHECKs are strictly looser than V196's for every existing row and every previous-app write (they only add literals and branches for the 4 new types, and the column this file adds is NULL on all of those rows); they are added NOT VALID here and validated in V220.
-- Phase 415 — in-app feed rows for moderation outcomes (ModerationService.resolve, curl and e-mail alike).
-- Fix-forward on V196 (applied migrations are immutable, §O-9): the type / shape / dedup-key CHECKs are DROPped
-- and re-ADDed under their ORIGINAL names. Every V196 literal and branch is copied verbatim; the 4 new types are
-- appended.
--
-- D3: the only new storage is moderated_target_type — the ReportTargetType literal of the resolved target, so the
-- client can pick the copy variant («Ваш відгук / фото ...»). No target id, no UGC, no names are stored.
--
-- New types:
--   REPORT_CONTENT_REMOVED  reporter, REMOVE on a review / client review / portfolio photo
--   REPORT_NO_VIOLATION     reporter, DISMISS or RESTORE
--   REPORT_REVIEWED         reporter, any other closure (profile ACTIONED, TARGET_GONE)
--   CONTENT_REMOVED         author of the removed review / client review / photo
--
-- Shape: the 4 types carry moderated_target_type and NO booking / appointment / salon / subject / request FK, so the
-- row cannot point at anything the recipient may no longer see (D9: tap target is NONE). CONTENT_REMOVED is limited to
-- the three content target types. Every other type must leave moderated_target_type NULL.
--
-- Dedup key: '<TYPE>:<batchReportId>' (the report id passed to resolve). Uniqueness is per (recipient_user_id,
-- dedup_key), so a reporter gets one row per resolution even when the key carries no recipient id; the key TYPE
-- must equal the row type for the 4 new types. The TYPE literal set stays in sync BY HAND with
-- in_app_notification_type_chk and InAppNotificationType (guarded by InAppNotificationRepositoryIT).

-- Audit-fix cycle 1 (PERF): NOT VALID / VALIDATE split (V212 + V214, V113 / V115 house style). Every ADD CONSTRAINT
-- below is added without a table scan, so this file holds ACCESS EXCLUSIVE only for catalog changes; new writes are
-- checked immediately, and V220 validates existing rows under SHARE UPDATE EXCLUSIVE (readers and writers proceed).
-- lock_timeout bounds the wait for the lock so a busy table fails this migration fast instead of queueing every
-- feed read behind it.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification ADD COLUMN moderated_target_type VARCHAR(32) NULL;

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
        'REPORT_CONTENT_REMOVED','REPORT_NO_VIOLATION','REPORT_REVIEWED','CONTENT_REMOVED')) NOT VALID,
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
            ELSE (booking_id IS NOT NULL OR appointment_id IS NOT NULL)
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
        END
        AND (moderated_target_type IS NULL
             OR type IN ('REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED'))
    ) NOT VALID,
    ADD CONSTRAINT chk_inapp_moderated_target_type CHECK (
        moderated_target_type IS NULL OR moderated_target_type IN (
            'REVIEW', 'CLIENT_REVIEW', 'PORTFOLIO_PHOTO', 'MASTER_PROFILE', 'SALON_PROFILE', 'CLIENT_PROFILE')
    ) NOT VALID,
    ADD CONSTRAINT in_app_notification_dedup_key_chk CHECK (
        split_part(dedup_key, ':', 1) = ANY (ARRAY[
            'BOOKING_CREATED', 'BOOKING_CANCELLED_BY_CLIENT', 'BOOKING_DECLINED',
            'BOOKING_NOT_COMPLETED', 'BOOKING_RESCHEDULED', 'REVIEW_REQUESTED',
            'BOOKING_CANCELLED_SALON_CLOSED', 'BOOKING_CANCELLED_MASTER_REMOVED',
            'REVIEW_RECEIVED', 'INVITE_ACCEPTED',
            'CATEGORY_REQUEST_APPROVED', 'CATEGORY_REQUEST_REJECTED',
            'SERVICE_TYPE_SUGGESTION_APPROVED', 'SERVICE_TYPE_SUGGESTION_REJECTED',
            'REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED'])
        AND CASE
                WHEN split_part(dedup_key, ':', 1) LIKE 'CATEGORY_REQUEST\_%' THEN
                    dedup_key ~ '^CATEGORY_REQUEST_(APPROVED|REJECTED):[0-9]{1,19}$'
                WHEN split_part(dedup_key, ':', 1) LIKE 'SERVICE_TYPE_SUGGESTION\_%' THEN
                    dedup_key ~ '^SERVICE_TYPE_SUGGESTION_(APPROVED|REJECTED):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                WHEN split_part(dedup_key, ':', 1) LIKE 'REPORT\_%' OR split_part(dedup_key, ':', 1) = 'CONTENT_REMOVED' THEN
                    dedup_key ~ '^(REPORT_CONTENT_REMOVED|REPORT_NO_VIOLATION|REPORT_REVIEWED|CONTENT_REMOVED):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
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
        AND (type NOT IN ('REPORT_CONTENT_REMOVED', 'REPORT_NO_VIOLATION', 'REPORT_REVIEWED', 'CONTENT_REMOVED')
             OR split_part(dedup_key, ':', 1) = type)
    ) NOT VALID;
