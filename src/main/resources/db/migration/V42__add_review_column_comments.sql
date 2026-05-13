-- Documents FK policy for client_id and master_id, mirroring the salon_id COMMENT ON COLUMN in V41.
-- ON DELETE NO ACTION is intentional: reviews are audit/legal records; user/master hard-delete is blocked when reviews exist.
-- GDPR erasure must anonymize (nullify or pseudonymize) these fields, not delete the review row.
COMMENT ON COLUMN reviews.client_id IS
    'FK to users(id). ON DELETE NO ACTION: hard-delete of a user is blocked while reviews reference them. '
    'GDPR erasure must anonymize or nullify this field, not delete the review row.';

COMMENT ON COLUMN reviews.master_id IS
    'FK to masters(id). ON DELETE NO ACTION: hard-delete of a master is blocked while reviews reference them. '
    'Review history must be preserved for audit purposes even after master deactivation.';
