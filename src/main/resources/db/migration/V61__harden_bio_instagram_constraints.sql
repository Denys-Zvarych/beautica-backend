ALTER TABLE users
    ADD CONSTRAINT chk_users_instagram
        CHECK (instagram IS NULL
            OR instagram ~ '^@?[A-Za-z0-9._]{1,30}$'
            OR instagram LIKE 'https://instagram.com/%'
            OR instagram LIKE 'https://www.instagram.com/%');
