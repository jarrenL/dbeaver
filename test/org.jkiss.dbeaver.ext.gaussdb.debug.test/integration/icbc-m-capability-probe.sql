-- Run with the vendor gsql client against a NEW disposable M database only.
-- Do not set ON_ERROR_STOP: every DDL is isolated by its own rollback.
-- Errors here are server capability evidence, NOT successful debug acceptance.
\set VERBOSITY verbose
SELECT version();
SELECT current_database(), current_user;
SHOW sql_compatibility;
SELECT lanname FROM pg_language ORDER BY lanname;
SELECT p.proname, p.proargtypes
FROM pg_proc p JOIN pg_namespace n ON p.pronamespace=n.oid
WHERE n.nspname='dbe_pldebugger' ORDER BY p.proname;

BEGIN;
CREATE FUNCTION public.icbc_probe_f(i integer) RETURNS integer
LANGUAGE plpgsql AS $$ BEGIN RETURN i + 1; END; $$;
ROLLBACK;

BEGIN;
CREATE PROCEDURE public.icbc_probe_p() AS BEGIN NULL; END;
/
ROLLBACK;

BEGIN;
CREATE PACKAGE public.icbc_probe_pkg AS FUNCTION f(i integer) RETURN integer; END;
/
ROLLBACK;
