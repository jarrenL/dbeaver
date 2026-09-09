-- Run as administrator only in the disposable GaussDB 507 acceptance instance.
-- package_tester must already exist. Deliberately fails if fixtures already exist.
\set ON_ERROR_STOP on
CREATE DATABASE dbeaver_ext_0909_a OWNER package_tester DBCOMPATIBILITY='A';
CREATE DATABASE dbeaver_ext_0909_b OWNER package_tester DBCOMPATIBILITY='B';
CREATE DATABASE dbeaver_ext_0909_c OWNER package_tester DBCOMPATIBILITY='C';
CREATE DATABASE dbeaver_ext_0909_pg OWNER package_tester DBCOMPATIBILITY='PG';
CREATE DATABASE dbeaver_ext_0909_m OWNER package_tester DBCOMPATIBILITY='M';
SELECT datname,datcompatibility FROM pg_database WHERE datname LIKE 'dbeaver_ext_0909_%' ORDER BY datname;
