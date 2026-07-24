-- V4 added this FK with ON DELETE CASCADE, which silently destroys pending invites
-- when a salon is deleted. ON DELETE SET NULL is correct: the invite token remains
-- queryable so the service layer can return a clear error to the invitee.
ALTER TABLE invite_tokens
    DROP CONSTRAINT fk_invite_tokens_salon_id;

ALTER TABLE invite_tokens
    ADD CONSTRAINT fk_invite_tokens_salon_id
    FOREIGN KEY (salon_id) REFERENCES salons(id) ON DELETE SET NULL;
