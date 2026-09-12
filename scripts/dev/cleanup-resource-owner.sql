-- Repairs a development database that ran the axis-1 changesets before they were deleted.
--
-- Resource ownership never shipped: no ownership commit is an ancestor of origin/master, so the changesets were
-- deleted rather than reversed and a fresh database never grows these columns. A database that already ran them
-- keeps orphan columns, orphan owned physical tables, and registry rows pointing at physical names that no longer
-- resolve. Run this once per affected schema.
--
-- Owned rows are dropped rather than merged into the shared table of the same base name: the two can have divergent
-- column sets, and no shipped data is at stake.
--
-- Idempotent, and safe on a schema that only ran PART of the axis-1 set (add_owner without owner_unique_index, or
-- vice versa): every statement is guarded, so a second run -- or a run against a partially-migrated schema, which
-- is the normal case on a dev Postgres shared across worktrees -- finds nothing left to do for whatever already
-- ran, rather than aborting on a column or constraint that is not there.
--
--   psql "$BYTECHEF_DEV_DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/dev/cleanup-resource-owner.sql

BEGIN;

-- Registry rows whose physical table is an owned one, and the tables themselves. Both pools: a shared table is
-- always `<pool>_<envId>_<baseName>` with exactly one numeric run before the base name, and a base name can never
-- start with a digit (DataTableRef's [a-z_][a-z0-9_]* validation), so a shared table can never match this pattern
-- regardless of pool.
DO $$
DECLARE
    owned_table_name text;
BEGIN
    FOR owned_table_name IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_type = 'BASE TABLE'
          AND table_name ~ '^(dt|edt)_[0-9]+_[0-9]+_[a-z]+_'
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I CASCADE', owned_table_name);
    END LOOP;
END $$;

-- Guarded on the column existing: a schema that ran only part of the axis-1 set (or has already been repaired by
-- an earlier run of this script) may not have owner_id at all, and referencing a missing column aborts the whole
-- transaction under ON_ERROR_STOP.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'data_table' AND column_name = 'owner_id'
    ) THEN
        DELETE FROM data_table WHERE owner_id IS NOT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'knowledge_base' AND column_name = 'owner_id'
    ) THEN
        DELETE FROM knowledge_base WHERE owner_id IS NOT NULL;
    END IF;
END $$;

-- The axis-1 registry key, and the columns behind it.
DROP INDEX IF EXISTS uk_data_table_name_platform_type_owner;
DROP INDEX IF EXISTS uk_data_table_name_platform_type_shared;
DROP INDEX IF EXISTS idx_data_table_owner;
ALTER TABLE data_table DROP COLUMN IF EXISTS owner_id;
ALTER TABLE data_table DROP COLUMN IF EXISTS owner_type;

DROP INDEX IF EXISTS uk_knowledge_base_name_platform_type_environment_owner;
DROP INDEX IF EXISTS uk_knowledge_base_name_platform_type_environment_shared;
DROP INDEX IF EXISTS idx_knowledge_base_owner;
ALTER TABLE knowledge_base DROP COLUMN IF EXISTS owner_id;
ALTER TABLE knowledge_base DROP COLUMN IF EXISTS owner_type;

-- The pre-ownership keys, which the deleted changesets had dropped.
ALTER TABLE data_table DROP CONSTRAINT IF EXISTS uk_data_table_name_platform_type;
ALTER TABLE data_table ADD CONSTRAINT uk_data_table_name_platform_type UNIQUE (name, platform_type);

ALTER TABLE knowledge_base DROP CONSTRAINT IF EXISTS uk_knowledge_base_name_platform_type_environment;
ALTER TABLE knowledge_base
    ADD CONSTRAINT uk_knowledge_base_name_platform_type_environment UNIQUE (name, platform_type, environment);

-- The deleted changesets' own bookkeeping, so Liquibase does not report them as unexpected.
DELETE FROM databasechangelog
WHERE filename LIKE '%_platform_data_table_add_owner.xml'
   OR filename LIKE '%_platform_data_table_owner_unique_index.xml'
   OR filename LIKE '%_platform_knowledge_base_add_owner.xml'
   OR filename LIKE '%_platform_knowledge_base_owner_unique_index.xml';

COMMIT;
