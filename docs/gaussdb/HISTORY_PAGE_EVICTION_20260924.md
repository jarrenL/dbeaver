# 查询历史显示上限：控件映射回归

对应历史场景 3.11（历史列表/查询大小）及 9.7（执行事件更新）。

## 发现及修复

查询管理器 `updateItem` 将 `QMEvent` 保存到行数据。但超出 `entriesPerPage` 时，旧代码只在行数据属于 `QMMObject` 时移除对象到控件的映射，导致正常事件行被释放后仍被映射引用。后续相同对象的更新可能找到已释放的行并被忽略。

将原来的裁剪块提取为 `trimToPageSize`，改为读取 `QMEvent.getObject().getObjectId()` 清理映射。裁剪只限制显示，不能调用历史删除或删除数据库数据。

## 自动化证据及边界

`QueryHistoryPageTest.evictingPageRowsRemovesWidgetMappingsButDoesNotDeleteHistory` 调用生产裁剪方法，以两个模拟 SWT 行及一行显示上限验证：保留行映射不变、被裁剪行映射消失、按正确索引删除控件、未调用历史删除。

修复前明确失败：预期映射为空，实际仍返回被移除的 TableItem。修复后结果见 `test-results-history-page-20260924.json`。

这是生产逻辑的模拟控件测试，不是真实 SWT 分页 GUI 验收，不证明并发更新及所有分页边界已完成。此前单行删除 GUI 通过结果不自动扩大到本次裁剪修改。

## 边界矩阵补充

`pageBoundaryPreservesRetainedRowsAndUnderlyingEvents` 增加 7 组：行数/上限为 0/10、1/10、9/10、10/10、11/10、15/10、201/200。逐行验证映射保留/移除；未超过上限时不访问或删除行，超过时验证删除索引完整且无越界；所有历史对象均未被标记删除。

run-gT0Yb3 最新回归 1543 项，1520 通过、23 跳过、0 失败/错误。结果报告为 `test-results-history-page-boundaries-20260924.json`，不是原始历史用例覆盖率。此轮未改生产代码。
