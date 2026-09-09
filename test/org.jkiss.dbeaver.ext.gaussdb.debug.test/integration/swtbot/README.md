# Opt-in SWTBot desktop acceptance driver

This is a **test-only bundle**, intentionally outside all Maven modules, product
features and production source folders. It drives the real SWT widgets in a
disposable DBeaver product copy. It does not mock the debugger or JDBC connections.

## Local setup

1. Build the community product and copy its macOS ARM64 `DBeaver.app` to a
   disposable absolute directory (`TEST_ROOT/DBeaver.app`). Never install this
   bundle into a customer or daily-use installation.
2. Put these OSGi bundles in `TEST_ROOT/lib`: SWTBot SWT finder 4.3.0.202506021445,
   `org.hamcrest.core` and `org.hamcrest.library` 2.2.0.v20230809-1000 (from the
   official `https://download.eclipse.org/technology/swtbot/releases/4.3.0/`
   P2 site), `org.hamcrest` 3.0.0, and an OSGi JUnit 4 bundle exporting
   `junit.framework` and `org.junit` at version 4.13.2. The installed product
   already supplies SLF4J, SWT and the workbench.
3. Run `JAVA_HOME=/path/to/jdk bash install-local.sh /absolute/TEST_ROOT`.
4. Launch that copy with a dedicated test workspace and JVM property
   `-Dgaussdb.swtbot.queue=/absolute/TEST_ROOT/queue`. Use Java 21. Do not expose
   the queue to other users; its commands perform UI actions as the current user.
5. Configure a dedicated GaussDB connection and disposable test schema. Provide
   the test password via a mode-0600 local file, not a checked-in command file.

The startup extension does nothing unless the JVM property is supplied. It does
not listen on any network port. Stop the application to stop the queue worker.

## Commands and evidence

Submit a UTF-8 `NNN.cmd` file containing tab-separated commands. The worker
preserves it as `.cmd.done` and writes `.cmd.result`. On a crash, preserve pending
commands as `.aborted` before restarting: stale widget indexes must not be replayed.

`dump` records visible shells, widgets, enabled state, loaded tree nodes and table
cell values. Password-style text controls are redacted. Widget IDs are valid only
until the next dump, and must be re-observed after UI changes. Do not hard-code
indexes across runs or infer an action from an old snapshot.

Operations: `click ID`, `expand ID`, `double ID`, `check ID`, `uncheck ID`,
`text ID VALUE`, `secret ID /absolute/password-file`, `focus ID`,
`key ID F7` (or `SHIFT+F7`), `combo ID VALUE`, `cell TABLE_ID ROW COLUMN`,
`line STYLED_TEXT_ID ZERO_BASED_LINE`, `context TREE_ITEM_ID MENU_TEXT`, `close ID`.
`source STYLED_TEXT_ID /absolute/file.sql` replaces editor text through SWTBot.
`select TREE_ID ITEM_ID|ITEM_ID` selects observed nested tree items together.
StyledText dumps include the one-based caret line and selection offsets.
All separators in actual command files are tabs, not spaces.

`context-selection TREE_ID MENU_TEXT` preserves a multi-selection when opening its menu.
`dropdown TOOL_ITEM_ID MENU_TEXT` operates a toolbar drop-down; `view VIEW_ID` opens a workbench view.
`model TREE_ITEM_ID` reads the actual model and, for GaussDB routines, calls the production eligibility
and live capability checks. These are model/API assertions, not evidence of menu visibility.
`modes DATABASE_TREE_ITEM_ID` loads the five dedicated `dbeaver_ext_0909_*` fixture databases
through the selected GaussDB data source and reports their production capability flags. Create them
with `extended-mode-fixtures.sql` only in a disposable instance. `extended-pg-fixtures.sql` provides
the native PostgreSQL parent/child and audit fixtures for GUI regression.

`resolve-frame TREE_ITEM_ID OID EXPECTED_SCHEMA` asserts the actual GaussDB resolver returns that OID/schema
without mutating launch configuration. `navigation-race SHELL_ID stale-request|closed-editor|closed-dialog`
replays a captured real package navigation callback after the lifecycle transition. Use the actual package
results shell, a clean active package editor, and (for stale-request) two error rows. This test intentionally
closes editors without saving; never run it against a workspace containing unsaved user work. These tests
are deterministic callback interleavings, not long-running load tests. Cross-schema SQL fixtures are
`cross-schema-gaussdb.sql` and `cross-schema-postgresql.sql`, for disposable acceptance databases only.

`OK` in a result means the UI action completed, **not that a requirement passed**.
Acceptance must assert the resulting widget state and, where relevant, independent
database results. Shell screenshots obtained through SWTBot's desktop capture can
be blank on macOS; a blank capture is not visual evidence.

Custom combo labels require SWTBot mouse notifications instead of desktop-level
mouse posting. Keyboard layout is fixed to EN_US for repeatability. Native key
posting is marshalled to the UI thread: macOS 26's input-source API can trap when
SWTBot calls it from its worker thread. The debugger itself is not modified for
these test-driver accommodations.

## Required scenarios

- Fresh launch configuration; connection/routine selection and parameter editing.
- Ordinary authorized user starts; nonmember and incompatible M routine rejected.
- Suspend/source view; F7/F8/Shift+F7/F9/F10 reach the expected database state.
- Breakpoint add, disable, enable, delete and re-add through the breakpoint view.
- Variable read/edit and variable-name watch; failed edits retain old values.
- Nested stack and double-click source navigation.
- Commit and rollback dialogs with independent table-content verification.
- Package-capable ORA/A environment: SPEC/BODY/ALL compilation, compiler error source location,
  multi-selection deletion and verification of removed test objects.

Record each scenario separately as passed, failed or blocked. Protocol-only tests
and unit tests cannot substitute for these client acceptance scenarios.
