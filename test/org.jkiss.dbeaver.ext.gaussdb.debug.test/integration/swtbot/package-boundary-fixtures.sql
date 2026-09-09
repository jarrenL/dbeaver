\set ON_ERROR_STOP on
SET enable_force_create_obj=on;
SET plsql_show_all_error=off;
CREATE OR REPLACE PACKAGE ui_pkg.boundary_body AS
  FUNCTION value_of RETURN INTEGER;
END boundary_body;
/
CREATE OR REPLACE PACKAGE BODY ui_pkg.boundary_body AS
  FUNCTION value_of RETURN INTEGER AS
    missing ui_pkg.missing_table.id%TYPE;
  BEGIN
    RETURN 1;
  END;
END boundary_body;
/
CREATE OR REPLACE PACKAGE ui_pkg.boundary_spec AS
  missing ui_pkg.missing_table.id%TYPE;
  FUNCTION value_of RETURN INTEGER;
END boundary_spec;
/
SELECT name,type,line,src FROM dbe_pldeveloper.gs_errors WHERE name LIKE 'boundary_%' ORDER BY name,type,line;
