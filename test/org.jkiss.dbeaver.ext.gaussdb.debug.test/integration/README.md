# GaussDB 507 JDBC protocol regression

These standalone Java harnesses complement the mocked DBeaver session tests. They exercise the actual database protocol, not Eclipse UI actions. They use three **dedicated disposable databases** named `dbeaver_fix_0905_ora`, `dbeaver_fix_0905_mysql`, and `dbeaver_fix_0905_pg` at `127.0.0.1:5432`. Do not use databases containing application data: the harnesses replace routines and truncate their `fix.fix_audit` table.

## Setup

Using an administrator connection, create a disposable login, grant `gs_role_pldebugger`, and create the three databases with that login as owner and `DBCOMPATIBILITY` values `ORA`, `MYSQL`, and `PG`. The login needs to create its own `fix` schema. Keep credentials outside the repository in a permissions-0600 Java properties file:

```properties
user=<disposable-login>
password=<generated-password>
```

Compile with Java 21 or newer:

```sh
javac -d /tmp/gaussdb-regression-classes test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/*.java
```

Set `GAUSSDB_JDBC_JAR` to the Huawei JDBC jar and execute in this order:

```sh
java -cp "/tmp/gaussdb-regression-classes:$GAUSSDB_JDBC_JAR" LiveDebug /path/to/connection.properties
java -cp "/tmp/gaussdb-regression-classes:$GAUSSDB_JDBC_JAR" LiveNested /path/to/connection.properties
java -cp "/tmp/gaussdb-regression-classes:$GAUSSDB_JDBC_JAR" LiveFunctionAndError /path/to/connection.properties
```

`LiveDebug` also accepts `--postgresql` as its second argument when the classpath contains the PostgreSQL JDBC jar and the instance authentication method is compatible. GaussDB 507's authentication in the validation environment was rejected by upstream PostgreSQL JDBC 42.7.10 (`Invalid SCRAM client initialization`); server authentication was not changed.

## Assertions

- `LiveDebug`: 168 checks across three modes and commit/rollback/abort outcomes. Includes typed breakpoint binding, ID zero, duplicates, enable/disable/delete, source lines, stack, step/next/continue, invalid values, expressions, quoted strings, NULL, and constants.
- `LiveNested`: 21 checks for nested procedure calls, frame-local variables, current-frame-only writes, finish/step return, and completion.
- `LiveFunctionAndError`: 15 checks for SELECT function calls and runtime error handling. GaussDB 507 rejects controller commands, including `abort()`, after `[EXECUTION HAS ERROR OCCURRED!]`; closing the target connection releases the error wait and rolls back its earlier writes.

Target exceptions printed during intentional abort are expected. A successful run exits zero and prints its total check count. Credentials are never printed.

## Cleanup

After closing test processes, drop the three disposable databases, drop the disposable login, and delete the credentials file. These harnesses deliberately do not delete databases or manage login privileges themselves.

## Native PostgreSQL regression (2026-09-09)

`LivePostgreSQL.java` targets native PostgreSQL with `pldbgapi`, not GaussDB. Build and start
the isolated test server from the repository root:

```sh
docker build -t dbeaver-pg-debug-review:20260909 -f test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/Dockerfile.postgresql test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration
docker run -d --name dbeaver-pg-debug-review-20260909 --tmpfs /var/lib/postgresql/data -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1:55439:5432 dbeaver-pg-debug-review:20260909
javac -d /tmp/dbeaver-pg-review-classes test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/LivePostgreSQL.java
java -cp "/tmp/dbeaver-pg-review-classes:$POSTGRESQL_JDBC_JAR" LivePostgreSQL jdbc:postgresql://127.0.0.1:55439/postgres
docker stop dbeaver-pg-debug-review-20260909
```

Use Java 21+ and set POSTGRESQL_JDBC_JAR to the PostgreSQL driver jar. The test server trusts
local connections, binds only to loopback, and stores disposable data in tmpfs; do not use it
as a production instance. The harness creates `review_debug`, removes it after success,
and requires a fresh database if a run failed before cleanup.

Verified with PostgreSQL 16.15, JDBC 42.7.10 and the pinned EnterpriseDB/pldebugger commit in
the Dockerfile: 11 assertions passed (attach, source/stack, variable modification/readback,
breakpoint add/drop/re-add, nested step-over/step-into, caller breakpoint, actual return value).
The extension reports SQLSTATE 08006 / `select() failed waiting for target` on final continue;
the test accepts only that specific completion diagnostic together with a successful target
result of 23. This does not validate the DBeaver UI handling of that diagnostic.
PG Step Return remains unsupported (`canStepReturn=false`); returning to a caller breakpoint
in this harness must not be reported as PG Step Return support.
