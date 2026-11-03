-- V10 replay summaries on job checkpoints (issue #76).
--
-- A checkpoint already names the chain event at a 100-event boundary, so a
-- replay can verify the suffix from it. It could not compute the rest of the
-- answer without reading the prefix: the last cas.put artifact, the submit
-- input root, and (when the last commit is before the boundary) the terminal
-- outcome. These columns record that summary in the same transaction as the
-- checkpoint event, so replay reads the latest checkpoint and only the events
-- from its seq onward.
--
-- They are nullable for checkpoints written before V10, so replay falls back
-- to the full chain rather than guessing a prefix. The writer fills every
-- column from V10 on: artifact_sha is '' when no cas.put precedes the
-- boundary, input_sha is document_versions.sha256, and commit_seq /
-- commit_outcome are both set only when a commit precedes the boundary.
--
-- The checkpoint is the trusted prefix root. audit_events and job_checkpoints
-- remain append-only for every role, so a prefix event cannot be edited or
-- removed in place. A consistent prefix rewrite changes the event at the
-- boundary, whose hash must still equal job_checkpoints.sha: that is the
-- check ReplayService applies before it trusts the suffix. A rewrite that also
-- rewrites the checkpoint is the existing whole-chain rewrite residual
-- (record 0026), and replayFull remains the full-chain audit path.

ALTER TABLE job_checkpoints ADD COLUMN artifact_sha TEXT
    CHECK (artifact_sha IS NULL OR artifact_sha = '' OR artifact_sha ~ '^[0-9a-f]{64}$');
ALTER TABLE job_checkpoints ADD COLUMN input_sha TEXT
    CHECK (input_sha IS NULL OR input_sha ~ '^[0-9a-f]{64}$');
ALTER TABLE job_checkpoints ADD COLUMN commit_seq BIGINT
    CHECK (commit_seq IS NULL OR commit_seq >= 0);
ALTER TABLE job_checkpoints ADD COLUMN commit_outcome TEXT
    CHECK (commit_outcome IS NULL OR commit_outcome IN ('SUCCEEDED', 'FAILED', 'DEAD'));
ALTER TABLE job_checkpoints ADD CONSTRAINT job_checkpoints_commit_shape
    CHECK ((commit_seq IS NULL) = (commit_outcome IS NULL));

COMMENT ON COLUMN job_checkpoints.artifact_sha IS
    'Last cas.put artifact at or before this checkpoint; empty when none. NULL for pre-V10 rows: replay falls back to the full chain.';
COMMENT ON COLUMN job_checkpoints.input_sha IS
    'document_versions.sha256 at this checkpoint. NULL for pre-V10 rows.';
COMMENT ON COLUMN job_checkpoints.commit_seq IS
    'Last commit seq at or before this checkpoint, with commit_outcome. NULL when no commit had happened.';
