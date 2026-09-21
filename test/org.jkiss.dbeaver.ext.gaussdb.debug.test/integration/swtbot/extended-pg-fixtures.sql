-- Dedicated PostgreSQL GUI regression schema. Requires pldbgapi and its preload configuration.
\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS pldbgapi;
CREATE SCHEMA ui_regression;
CREATE TABLE ui_regression.audit(value integer);
CREATE FUNCTION ui_regression.child(p integer) RETURNS integer LANGUAGE plpgsql AS $$
BEGIN
  RETURN p + 2;
END
$$;
CREATE FUNCTION ui_regression.parent(p integer) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE v integer := p;
BEGIN
  v := v + 1;
  v := ui_regression.child(v);
  INSERT INTO ui_regression.audit VALUES (v);
  RETURN v;
END
$$;
-- Debug parent(6); set v=20 at initial suspension, then step over/into and continue.
-- Expected audit value 23. A second unmodified run produces 9.
