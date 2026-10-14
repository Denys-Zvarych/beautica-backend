-- Phase 372 — in-app notification when a platform admin approves/rejects a category request or a
-- service-type suggestion. Fix-forward on V181/V183 (applied migrations are immutable, §O-9): the
-- type / shape / dedup-key CHECKs are DROPped and re-ADDed under their ORIGINAL names.
--
-- Storage (D3): no payload column. Two nullable FKs link the feed row to the request it is about;
-- names and the reject reason are resolved at READ TIME. ON DELETE CASCADE, mirroring booking_id:
-- if the request row is gone the item is meaningless, and the cascade keeps the shape CHECK below
-- satisfiable for the row's whole life (the FK is never nulled).
--
-- Reject reason (D4): nullable decision_reason VARCHAR(300) on both request tables. NULL passes the
-- length bound; the cap is a backstop for the controller's 300-char validation.

ALTER TABLE platform_categories ADD COLUMN decision_reason VARCHAR(300) NULL;
ALTER TABLE service_type_suggestion ADD COLUMN decision_reason VARCHAR(300) NULL;

ALTER TABLE in_app_notification
    ADD COLUMN category_request_id      BIGINT NULL REFERENCES platform_categories(id)     ON DELETE CASCADE,
    ADD COLUMN service_type_suggestion_id UUID NULL REFERENCES service_type_suggestion(id) ON DELETE CASCADE;

-- Non-CONCURRENT index creation is deliberate: the new columns are empty and no production DB exists
-- yet, so the brief lock is free. If in_app_notification ever grows large before this runs elsewhere,
-- split into a separate non-transactional migration using CREATE INDEX CONCURRENTLY.
-- Partial FK-support indexes (§E-5/§O-6): the column is null on the vast majority of rows.
CREATE INDEX in_app_notification_category_request_idx
    ON in_app_notification (category_request_id) WHERE category_request_id IS NOT NULL;
CREATE INDEX in_app_notification_service_type_suggestion_idx
    ON in_app_notification (service_type_suggestion_id) WHERE service_type_suggestion_id IS NOT NULL;

-- ONE ALTER TABLE (single table scan to validate all three CHECKs). Constraints keep their ORIGINAL names.
--  * type: the 4 new literals.
--  * shape: each new type requires EXACTLY its own FK (and none of the others); booking types require
--    booking_id or appointment_id and NO request FK; INVITE_ACCEPTED stays exempt from the booking
--    requirement (see V181) but may carry no request FK.
--  * dedup key: V183's form with the 4 new TYPE literals; the suffix form is tied to the type:
--    CATEGORY_REQUEST_* -> exactly '<TYPE>:<bigint id>' (platform_categories.id is BIGINT, max 19 digits);
--    SERVICE_TYPE_SUGGESTION_* -> exactly 2 parts, UUID suffix. The TYPE literal set stays in sync BY HAND
--    with in_app_notification_type_chk and InAppNotificationType (guarded by InAppNotificationRepositoryIT).
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
        'SERVICE_TYPE_SUGGESTION_APPROVED','SERVICE_TYPE_SUGGESTION_REJECTED')),
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
            ELSE (booking_id IS NOT NULL OR appointment_id IS NOT NULL)
                AND category_request_id IS NULL AND service_type_suggestion_id IS NULL
        END
    ),
    ADD CONSTRAINT in_app_notification_dedup_key_chk CHECK (
        split_part(dedup_key, ':', 1) = ANY (ARRAY[
            'BOOKING_CREATED', 'BOOKING_CANCELLED_BY_CLIENT', 'BOOKING_DECLINED',
            'BOOKING_NOT_COMPLETED', 'BOOKING_RESCHEDULED', 'REVIEW_REQUESTED',
            'BOOKING_CANCELLED_SALON_CLOSED', 'BOOKING_CANCELLED_MASTER_REMOVED',
            'REVIEW_RECEIVED', 'INVITE_ACCEPTED',
            'CATEGORY_REQUEST_APPROVED', 'CATEGORY_REQUEST_REJECTED',
            'SERVICE_TYPE_SUGGESTION_APPROVED', 'SERVICE_TYPE_SUGGESTION_REJECTED'])
        AND CASE
                WHEN split_part(dedup_key, ':', 1) LIKE 'CATEGORY_REQUEST\_%' THEN
                    dedup_key ~ '^CATEGORY_REQUEST_(APPROVED|REJECTED):[0-9]{1,19}$'
                WHEN split_part(dedup_key, ':', 1) LIKE 'SERVICE_TYPE_SUGGESTION\_%' THEN
                    dedup_key ~ '^SERVICE_TYPE_SUGGESTION_(APPROVED|REJECTED):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
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
    );
