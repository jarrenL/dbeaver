# 二进制文件导入 GUI 补验记录（未通过验收）

日期：2026-09-28。关联历史清单 9.8 表格编辑、11.1 二进制类型及空 bytea 写入。

## 已观察事实

在既有麒麟 Linux x86_64 验收容器的运行中客户端执行，不重启或替换其工作区。客户端是历史验收副本，不是最新源码完整打包结果。

1. 新的控件快照确认当前为 `distributed_acceptance.payload.raw_bytes` 独立 LOB 编辑器，Binary 页显示 `01 02`，内容长度为 2。
2. 点击当前观测到的 `Load from File` 工具按钮，实际打开系统文件选择器。文件选择器截图及 `stat` 均确认 `/tmp/gui-empty-bytea.bin` 为零字节。
3. 在原生文件选择器通过路径输入选定该文件。选择器关闭后，新快照与屏幕仍显示 `01 02`，内容长度仍显示 2，编辑页出现未保存标记。

![零字节文件导入后 Binary 页仍显示原内容](images/bytea-empty-import-stale-20260928.png)

此观察不能证明数据库已被写入错误值：界面可能没有刷新，而底层值已变化。数据库最终值必须独立查询并重新读取界面后确认。

## 源码线索

- `ContentEditorContributor.FileImportAction` 调用 `ContentEditorInput.loadFromExternalFile`，更新控制器并标记编辑器已修改。
- `ContentEditorInput.refreshContentParts` 只刷新实现 `IRefreshablePart` 的页。
- `ContentPagePart` 包装器也只向实现该接口的已激活内部页转发，否则返回 IGNORED。
- `BinaryEditorPart` 继承的 `BinaryEditor` 当前没有实现 `IRefreshablePart`；二进制内容读取仅在初始化及工作区资源变更等路径触发。

上述代码与旧显示现象一致，需补文件切换/零长度/失效文件的刷新回归，并验证不覆盖用户未保存修改。尚未通过运行时调用跟踪完成根因确认，本记录不宣称修复完成。

## 未完成步骤与续验注意

已向验收队列提交 `20260928-2103.cmd`，内容为对当前二进制控件发送 Ctrl+S 后 dump。随后 Docker API 再次权限拒绝，无法取得该命令的 `.cmd.result`。不得假定保存成功、失败或重复提交。恢复访问后先检查该命令状态和最新界面；对测试 schema `dbv_bytea_gui_20260924` 的 payload 表核对 id=1 的 NULL 状态、长度及内容，确认实际变更，再决定后续动作。

前置快照队列：`20260928-2100`（初始）、`2101`（打开选择器）、`2102`（导入后）。队列位置：验收容器 `/opt/history-gui-20260923/queue`。文件选择通过已有、已检查的原生 X11 输入脚本执行，不修改数据模型。

本轮未新增 JUnit 执行数，不把动作返回 OK、出现未保存标记或窗口关闭当作空 bytea 写入通过。最终数据库值、取消/保存效果、重新打开后显示及最新产品包均待核验。
