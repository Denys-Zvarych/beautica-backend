package com.beautica.notification.entity;

public enum Platform {
    // These values are mirrored by the DB CHECK constraint
    // `chk_device_tokens_platform` (platform IN ('ANDROID','IOS')) created in
    // V29__create_device_tokens.sql. Adding or removing a value here requires a
    // follow-up Flyway migration to widen/narrow that constraint, otherwise
    // inserts of the new value fail at the DB with a 500 instead of being valid.
    ANDROID, IOS
}
