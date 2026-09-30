# Object action diagnostics

`object-actions TREE_ITEM_ID` reads the actual wrapped database object, node and object
read-only flags, object manager, maker delete capability, navigator metadata gate and
production `canDelete` property tester on the UI thread. It does not execute deletion
or change permissions. Take a fresh dump before selecting its widget ID.

A programmatic tree selection does not prove that its containing workbench view is
active. Before testing a navigator context menu, activate the navigator by an actual
click, then select the intended objects. Inspect the confirmation dialog before
accepting a destructive command. `canDelete=true` alone is not menu or deletion proof.

On 2026-09-30 both isolated package nodes returned `canDelete=true`; a real click in
the navigator restored the Delete menu, and multi-selection displayed the two exact
fixture names. Confirmed GUI deletion was followed by independent package count 0.
This corrected the earlier automation finding; production permissions were not relaxed.
