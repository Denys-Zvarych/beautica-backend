-- refresh_tokens: logout + token rotation by user
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- invite_tokens: findByEmailAndIsUsedFalse called on every invite dispatch
CREATE INDEX idx_invite_tokens_email_used ON invite_tokens(email, is_used);
