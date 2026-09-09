CREATE OR REPLACE PACKAGE BODY ui_pkg.boundary_body AS
  FUNCTION value_of RETURN INTEGER AS
    missing INTEGER;
  BEGIN
    RETURN 42;
  END;
END boundary_body;
