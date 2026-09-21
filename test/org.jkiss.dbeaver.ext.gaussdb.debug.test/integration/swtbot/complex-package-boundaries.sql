-- Disposable acceptance fixture: only run as a test user in a 507 ORA/A database.
-- Requires the existing test schema ui_pkg. Neither package name may already exist.
CREATE PACKAGE ui_pkg.icbc_syntax_0921 AS
  FUNCTION value_of RETURN INTEGER;
  FUNCTION text_of RETURN VARCHAR2;
END icbc_syntax_0921;
/
CREATE PACKAGE BODY ui_pkg.icbc_syntax_0921 AS
  FUNCTION value_of RETURN INTEGER AS
    v INTEGER := 0;
    FUNCTION seed RETURN INTEGER AS
    BEGIN
      RETURN 0;
    END seed;
  BEGIN
    v := seed();
    IF v = 0 THEN
      FOR i IN 1..3 LOOP
        v := v + i;
      END LOOP;
    ELSE
      v := -1;
    END IF;
    RETURN v;
  EXCEPTION WHEN OTHERS THEN
    RETURN -99;
  END value_of;
  FUNCTION text_of RETURN VARCHAR2 AS
  BEGIN
    /* END; / BEGIN are not delimiters in a comment. */
    RETURN 'END; it''s / text';
  END text_of;
BEGIN
  NULL;
END icbc_syntax_0921;
/
ALTER PACKAGE ui_pkg.icbc_syntax_0921 COMPILE;
ALTER PACKAGE ui_pkg.icbc_syntax_0921 COMPILE SPECIFICATION;
ALTER PACKAGE ui_pkg.icbc_syntax_0921 COMPILE BODY;
SELECT ui_pkg.icbc_syntax_0921.value_of() AS expected_6,
       ui_pkg.icbc_syntax_0921.text_of() AS expected_text,
       6 / 2 AS expected_3;
CREATE PACKAGE ui_pkg."icbc;quoted_0921" AS
  FUNCTION quoted_value RETURN INTEGER;
END "icbc;quoted_0921";
/
CREATE PACKAGE BODY ui_pkg."icbc;quoted_0921" AS
  FUNCTION quoted_value RETURN INTEGER AS
  BEGIN
    RETURN 7;
  END quoted_value;
END "icbc;quoted_0921";
/
SELECT ui_pkg."icbc;quoted_0921".quoted_value() AS expected_7;
