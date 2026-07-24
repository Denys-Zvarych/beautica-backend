ALTER TABLE users ADD COLUMN salon_id UUID;
CREATE INDEX idx_users_salon_id ON users (salon_id);
