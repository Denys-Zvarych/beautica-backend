CREATE TABLE salons (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID         NOT NULL REFERENCES users(id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    address     VARCHAR(500),
    city        VARCHAR(100),
    phone       VARCHAR(20),
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_salons_owner_id ON salons(owner_id);
CREATE INDEX idx_salons_city     ON salons(city);

ALTER TABLE users
    ADD CONSTRAINT fk_users_salon_id
    FOREIGN KEY (salon_id) REFERENCES salons(id) ON DELETE SET NULL;
