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

## Isolated review reruns (2026-09-17)

The same properties file may optionally set `review.endpoint` (host:port),
`review.databasePrefix` (replaces `dbeaver_fix_0905_`), and `review.driverClass`.
For the vendor `gsjdbc4.jar`, use `org.postgresql.Driver`; this is not the upstream
PostgreSQL jar. Defaults remain unchanged. The three suffixes are `ora`, `mysql`,
and `pg`. The centralized 507 test kernel accepts database compatibility `A`, `B`,
and `PG` respectively, rather than the ORA/MYSQL aliases accepted by other builds.

Opt-in reactor tests additionally use `GAUSSDB_REVIEW_CONNECTION` (private properties
file) and `GAUSSDB_REVIEW_JDBC` (vendor jar). The file must include `url` pointing to
the ORA database, and a `review.databasePrefix` matching `review_0917_[a-f0-9]{8}_`.
Without these environment variables the live tests are skipped. Tests create/drop
only their dedicated schemas; never point them at a business database.

- `GaussDBReviewLiveTest`: executes real package SQL and feeds real server result sets
  through the current compiler diagnostics code; asserts SPEC/BODY source-line text,
  ALL/SPEC/BODY SQL and a real 42P01 fault. Model identity/session factories are test
  bridges, not an actual DBeaver connection configuration or GUI.
- `GaussDBSessionLiveTest`: current breakpoint commands, deleted-marker delta callback
  and default-overload validator backed by real JDBC. Controller routing checks reject
  a foreign database marker; this is not a two-window SWT scenario.
- `GaussDBNativeLiveTest`: only enabled with `GAUSSDB_REVIEW_NATIVE=true`, and explicitly
  targets the existing local `gaussdb-507-ha-lab` test container. Uses ordinary-user
  TCP authentication and production pipeline-password writing for gs_dump/gsql/gs_restore.
  It round-trips the disposable schema in plain and custom formats, checks restored
  data via JDBC, and generates about 4 MiB stdout with production redirection.
  Process arguments and settings are fixture bridges, not a native-task GUI test.

Database users, databases, any temporary port forward/authentication rule, and
credential files must be cleaned up separately by the operator after all sessions exit.

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
# Additional failure-path tests (2026-09-17, second pass)

**Fixed and regression-tested on 2026-09-18:** the live breakpoint method creates a real workspace
project/marker, registers the real debug target, deletes the marker, and triggers POST_BUILD.
Eclipse passes a null removal delta after the marker is gone; the debug target now retains
the validated breakpoint identity and removes the server breakpoint. The original expected-zero
assertion is retained. See `GAUSSDB_THREE_FIXES_VALIDATION_20260918.md` for the fix and
`GAUSSDB_REMAINING_VALIDATION_20260917.md` for the historical failure.
Before deletion, four threads each execute ten add/disable/enable/remove cycles against the
same real debug session (160 operations), then verify a single converged breakpoint.

`GaussDBSessionLiveTest` now forwards a real JDBC connection through a loopback-only TCP proxy.
It forwards COMMIT but discards its server replies: an independent connection confirms the write,
and production `completeTransaction` must report unknown outcome and refuse another COMMIT.
The 3-second read timeout belongs to the **fixture**, not a new product default. A separate
regression sets `socketTimeout=0` and requires completion and concurrent `closeSession` to exit
without the fixture closing the proxy first. Production transaction completion now sets a
10-second network-read bound (or preserves an already shorter bound), restores it when possible,
and refuses retries after an unknown outcome. Normal debugger stepping keeps its existing timeout.

`GaussDBDumpAllLiveTest` additionally requires `GAUSSDB_REVIEW_EMPTY_CLUSTER=true` and a freshly
initialized, synthetic-credentials-only instance on container-local port 55462 in
`gaussdb-507-ha-lab`, with a data directory matching `/tmp/gauss-review-cluster-*/data`.
Never enable it against an existing or customer cluster: it runs real `gs_dumpall` and inspects
temporary role-password output without logging it. Initialize using the same generated password
as the private `GAUSSDB_REVIEW_CONNECTION` file; create `public.review_dump_lock(id integer)` in
postgres as the OS owner. The fixture locks that table, terminates only the backup backend for a
failure case, and sends SIGTERM to its recorded backup PID for cancellation. It verifies real
raw output contains a synthetic password, then calls production publication/cleanup logic.
Connection failure and cancellation must preserve the old destination and remove the private
dump; successful roles-only output must contain `PASSWORD DISABLE` and no quoted password.
This is not a click-through test of the native-task cancellation button.
