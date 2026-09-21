-- Run as the dedicated acceptance user, in a disposable test database.
-- First create owned schema ui_acceptance and table ui_acceptance.ui_audit(value integer).
-- Those objects are prerequisites; this file only replaces the two test routines.

CREATE OR REPLACE PROCEDURE ui_acceptance.ui_child(p_in int)
AS
DECLARE v_child int;
BEGIN
  v_child := p_in + 2;
  INSERT INTO ui_acceptance.ui_audit VALUES(v_child);
END;
/

CREATE OR REPLACE PROCEDURE ui_acceptance.ui_parent(p_in int)
AS
DECLARE v_local int;
BEGIN
  v_local := p_in + 1;
  ui_acceptance.ui_child(v_local);
  INSERT INTO ui_acceptance.ui_audit VALUES(v_local);
END;
/
