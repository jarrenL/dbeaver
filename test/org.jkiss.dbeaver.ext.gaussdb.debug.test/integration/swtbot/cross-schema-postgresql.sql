-- Disposable PostgreSQL UI regression database only; requires extended-pg-fixtures.sql.
\set ON_ERROR_STOP on
CREATE SCHEMA ui_cross_0909;
CREATE FUNCTION ui_cross_0909.child(p integer) RETURNS integer LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1;
  RETURN p + 102;
END
$$;
CREATE OR REPLACE FUNCTION ui_regression.parent(p integer) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE v integer := p;
BEGIN
  v := v + 1;
  v := ui_cross_0909.child(v);
  INSERT INTO ui_regression.audit VALUES(v);
  RETURN v;
END
$$;
