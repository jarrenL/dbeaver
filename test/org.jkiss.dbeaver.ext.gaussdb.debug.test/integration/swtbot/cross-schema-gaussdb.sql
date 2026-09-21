-- Dedicated dbeaver_ui_0909 acceptance database only; run as its acceptance user.
-- Requires ui_acceptance.ui_audit(value integer); replaces only the dedicated test parent.
\set ON_ERROR_STOP on
CREATE SCHEMA ui_cross_0909;
CREATE PROCEDURE ui_cross_0909.ui_child(p_in int)
AS
DECLARE v_child int;
BEGIN
  v_child := p_in + 102;
  INSERT INTO ui_acceptance.ui_audit VALUES(v_child);
END;
/
CREATE OR REPLACE PROCEDURE ui_acceptance.ui_parent(p_in int)
AS
DECLARE v_local int;
BEGIN
  v_local := p_in + 1;
  ui_cross_0909.ui_child(v_local);
  INSERT INTO ui_acceptance.ui_audit VALUES(v_local);
END;
/
