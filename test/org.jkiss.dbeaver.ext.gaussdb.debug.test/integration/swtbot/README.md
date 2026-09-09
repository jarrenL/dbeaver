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
All separators in actual command files are tabs, not spaces.

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
- Centralized ORA only: SPEC/BODY/ALL compilation, compiler error source location,
  multi-selection deletion and verification of removed test objects.

Record each scenario separately as passed, failed or blocked. Protocol-only tests
and unit tests cannot substitute for these client acceptance scenarios.
