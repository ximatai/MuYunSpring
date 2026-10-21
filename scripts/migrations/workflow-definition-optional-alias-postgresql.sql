-- Workflow identities are ordinary record IDs; aliases remain optional legacy integration keys.
-- Preserves all existing aliases and may be applied repeatedly.
BEGIN;
ALTER TABLE public.platform_workflow_definition ALTER COLUMN alias DROP NOT NULL;
COMMIT;
