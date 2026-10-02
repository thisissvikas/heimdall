ALTER TABLE control.runs ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX runs_pending_dispatch ON control.runs(created_at) WHERE dispatch_state='pending';
CREATE INDEX runs_pending_cancel ON control.runs(created_at) WHERE cancel_requested AND status IN ('queued','running','waiting');
