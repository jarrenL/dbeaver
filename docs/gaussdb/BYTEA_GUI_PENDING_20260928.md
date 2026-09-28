# 二进制文件导入 GUI 补验记录（未通过验收）

日期：2026-09-28。关联历史清单 9.8 表格编辑、11.1 二进制类型及空 bytea 写入。

## 后续进展（优先于下方早期状态）

### 生产加载方法组件回归

新增独立测试仓 `fixtures/java/BinaryEditorContentReloadTest.java`，通过 `node scripts/run-hex-content-focused.mjs` 运行。显式编译当前 `BinaryEditor` 和 `BinaryContent`，真实临时文件、模拟编辑器输入和 HexManager，通过反射调用生产私有加载方法，不调用数据库或启动 SWT 窗口。

4/4 通过，0 跳过、失败或中止：

- 检查二进制编辑器实现 `IRefreshablePart`。
- 分别以 0、2、65,537 字节文件替换先前 3 字节输入，每次安装不同 BinaryContent；逐字节核对加载结果和源文件未变。
- 上述三个参数场景中，切换到目录导致读文件失败时返回 false，不调用 manager 安装空或半成品内容；恢复有效文件后可再次加载。

捕获的 BinaryContent 在 finally 释放。错误日志包含预期注入的目录读取异常，不能据此判整组失败；JUnit 结果为 4 项通过，进程退出 0。日志 `/tmp/hex-content-test-20260928.log`。

测试不验证 `refreshPart` 的 UI 调度、已释放 manager 分支、屏幕内容/属性长度刷新，亦不验证旧 manager 内容的实际释放或数据库保存。独立脚本尚未纳入正式 Tycho 验收门控；没有把这 4 项累加到共享705项回归。GUI验收副本仍未部署本次修复。

再次获得容器访问后确认 `2103.cmd.done/result` 存在且动作返回 OK，但 LOB 页仍有未保存标记、仍显示旧字节，不能视为保存完成。使用当前观测到的菜单“保存”（2104）后 LOB 页关闭，payload 表编辑页保留未提交标记；随后点击表格保存（2105），出现 `08003: This connection has been closed`，仍有未保存标记。没有重复执行旧队列命令，也未确认数据库发生写入。下一步先核实工作区未保存状态和连接，再安排重连/重试及独立值查询。

已修改 `BinaryEditor` 实现 `IRefreshablePart`：内容刷新在 UI 线程重新加载当前输入文件，空文件也替换旧缓冲；未初始化/已释放 manager 返回 IGNORED，读文件失败返回 CANCELED，不返回虚假的 REFRESHED。原初始化和资源刷新路径继续使用同一个加载方法。

最初完成 Java 21 目标编译检查（运行 JDK 25，使用现有依赖），日志 `/tmp/hex-refresh-compile-20260928.log`，退出 0；之后完成上方加载方法组件测试。**尚未安装到运行中的验收副本，也没有通过刷新入口线程行为或 GUI 回归**。需要验证空/非空文件切换、读取失败保留旧视图、关闭后刷新，以及导入前存在未保存二进制编辑的行为；编译和组件通过不计为完整功能验收。

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
