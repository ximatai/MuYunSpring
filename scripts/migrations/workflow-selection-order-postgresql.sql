-- Run while the application is stopped, before starting the version using list order.
-- Preserve legacy priority within each standard tenant/module sort partition.
-- Tenant/global selection precedence is independent of numeric sort values.
-- Old equal-priority ambiguities become an explicit deterministic order (alias, id).
-- Dropping the retired column makes a successful migration idempotent.
BEGIN;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = 'platform_workflow_definition'
               AND column_name = 'match_priority') THEN
    LOCK TABLE public.platform_workflow_definition IN ACCESS EXCLUSIVE MODE;
    WITH ranked AS (
      SELECT id, row_number() OVER (
        PARTITION BY tenant_id, module_alias
        ORDER BY coalesce(match_priority, 0) DESC, sort_order ASC NULLS LAST, alias, id
      ) * 100 AS new_order
      FROM public.platform_workflow_definition
    )
    UPDATE public.platform_workflow_definition d
    SET sort_order = ranked.new_order, version = coalesce(d.version, 0) + 1
    FROM ranked WHERE d.id = ranked.id;
    ALTER TABLE public.platform_workflow_definition DROP COLUMN match_priority;
  END IF;
END $$;
COMMIT;
