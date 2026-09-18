# 未闭环项补测：2026-09-17 第二轮

> 后续进展：本文保留 9 月 17 日的失败证据。三个生产问题已在 9 月 18 日修复并复测，见 [修复与验证记录](GAUSSDB_THREE_FIXES_VALIDATION_20260918.md)。历史结果不代表当前修复后的状态。

## 结论：补上了验证，也复现了交付缺陷

这不是“全部验收通过”的报告。补测发现/确认三个问题：

1. **普通账号原生工具认证不通**：当前生产 handler 只设置 `PGPASSWORD`，这套原厂 507 工具的 `gs_dump`、`gsql` 在显式 TCP 下仍报账号/密码错误。同账号改用工具自带 `--pipeline` 从 stdin 输入密码后，明文及自定义格式备份/恢复均成功。工具可行路径已验证，DBeaver 生产适配尚未实现；没有把密码写进 argv。
2. **无限网络读等待下，取消/关闭被 COMMIT 阻塞**：`socketTimeout=0`、服务端已提交但回复被代理丢弃时，设置 monitor canceled 无法打断提交，`closeSession` 也等待 `transactionLock`。主动关闭专用测试 socket 后才退出。不能把“测试配了 3 秒读超时”当作产品修复。
3. **真实资源 marker 删除仍遗留服务端断点**：Eclipse 3.23.300 的 POST_BUILD 删除通知传 `delta=null`，此时 marker 已不存在。当前 `breakpointRemoved` 的 null 分支重新读取 marker，不能取得 datasource id，因而跳过删除。真实测试期待服务器断点数为 0，实际为 1。保留失败断言，没有改成“期望遗留 1 个”来刷绿。

本轮主要增加测试和证据，没有修复上述三个生产问题，也没有提交、推送或重制客户安装包。

## 实际执行矩阵

| 场景 | 方法 | 实测结果 | 证据边界 |
|---|---|---|---|
| 普通账号 + PGPASSWORD | 原厂 gs_dump、gsql；明确 `-h 127.0.0.1 -p 55452 -U 测试账号`；密码仅通过 docker exec 转发环境 | 两者均非零退出，认证失败 | 否定“只是用了 Unix socket”的解释；不是客户密码或既有账号 |
| 普通账号 + 管道密码 | 同一账号；`--pipeline`；密码通过 stdin；备份独立 schema，删除后恢复，独立 JDBC 查询 | gs_dump→gsql 明文、gs_dump→gs_restore 自定义格式均 count=3、sum=6 | 工具能力验证，不代表当前 DBeaver handler 已支持 |
| gs_dumpall 成功脱敏 | 全新空实例，仅合成角色凭据；原厂 roles-only 结果交给生产发布/清理实现 | 发布文件含 PASSWORD DISABLE、无 quoted password；私有临时文件删除 | settings、输出路径和进程启动是夹具桥接 |
| gs_dumpall 真实失败 | 空实例内锁住测试表，终止正在备份该表的专用 backend，得到原厂非零退出和含合成密码的部分 dump | 保留旧目标文件；删除私有 dump | 不是伪造 dump 文本；没有终止已有业务实例连接 |
| gs_dumpall 真实取消 | 锁住测试表后对记录的本次 gs_dumpall PID 发 SIGTERM；monitor 标记 canceled | 不发布含密码文件，旧目标保留，私有 dump 删除 | 真实工具取消 + 生产清理；不是点击任务窗口的取消按钮 |
| COMMIT 回执丢失，读超时 3 秒 | 专用 TCP 代理转发真实 COMMIT、丢弃后续服务端回复；另一连接查已提交数据 | 生产代码报告结果未确认，拒绝重复 COMMIT；代理仅见一次 COMMIT | 3 秒为测试连接参数，不是产品默认值；会话完成标志为夹具设置 |
| COMMIT 回执丢失，读超时 0 | 同上，不设读超时；设置取消并同时调用 closeSession | **取消和关闭均未在观察窗内完成**；主动断 socket 后才退出 | 缺陷特征测试通过不等于此功能验收通过；没有无限等到进程失控 |
| 断点并发增删启禁 | 真实 turn_on/attach 后，4 个线程各做 10 轮 add/disable/enable/remove，共 160 次生产会话操作 | 操作全部完成，最终重新 add 后服务端和客户端均仅 1 个断点 | 同一调试会话的并发，不等价于两个 GUI 窗口跨库操作 |
| Eclipse 真实 marker 删除 | 注册真实 DatabaseDebugTarget，建立临时 workspace project/marker，BreakpointManager 添加；marker.delete 后显式 workspace build | 收到删除通知、marker 不存在、delta 为 null；**服务端仍残留 1 个断点** | 真实资源/断点管理器 + 真库；不是手工构造 delta，也不是点 GUI 删除按钮 |
| SQL NULL / DEFAULT 参数格 | 麒麟 V10 ARM64 的真实 SWT 面板，分别按 Tab、Enter、切换到主窗口使其失焦 | 六种组合均保留原模式，编辑器已关闭 | 生产面板 + 一条合成参数行；不包含 routine 元数据和启动配置持久化 |
| 参数值真实修改 / 清空 | 同一 SWT 面板中 NULL 改成 42，再清空为 `""`，Enter 保存 | 自动变成 Value；空字符串仍是 Value，而非 NULL | 2 个 UI 行为检查；不等价于 SQL 调用结果验收 |

前轮已通过的包编译、实际错误行、默认参数重载检查和原厂大 stdout 恢复也在 reactor 中复跑。先前的 204 个独立协议断言属于前轮结果，本轮没有重复运行和重复计数。

## GUI 环境与失败尝试

- macOS 上先恢复测试用 DBeaver，当前生产参数面板可操作，但 SWTBot 的按键激活出现超时，未据此宣布键盘测试通过。
- 随后启动既有 `dbeaver-kylin-arm64`，系统为 Kylin Linux Advanced Server V10 (Lance)，使用 Xvfb + metacity 和已配置的 tester 账号。首次 root 启动停在 onboarding，改用 tester；未替用户接受许可协议。
- 仅把当前六个相关生产 bundle 和测试专用 SWTBot bundle 更新到 `/opt/test-dbeaver`，未替换 deliverables 下候选包。启动参数保留原有 VM 选项：`--launcher.appendVmargs`。
- `parameter-fixture` 使用生产 `GaussDBDebugPanelRoutine`，配置容器为空实现，参数行为通过真实 widget 事件执行。没有数据库连接，因此不会误操作旧客户/验收连接。
- 截图能看到真实参数面板；背景旧测试产品的 AI Chat 视图有加载错误，因此本轮也**不构成该测试产品完整启动/全功能认证**。没有把这个混合测试安装当成新交付制品。

## 仍未验收，不能外推

- 当前版本完整调试 GUI 的 F7/F8/Shift+F7/F9/F10、双击跨函数定位、提交/回滚对话框，以及两个 GUI 窗口跨数据库并发，本轮没有完成新的端到端脚本；断点资源事件与 160 次并发操作只补其底层部分。
- 客户麒麟实机、Windows、x86_64 本轮不在执行环境；此前用户允许暂不验证 Windows。
- 新产品打包、安装、升级、本机依赖、普通账号从 DBeaver 点击备份恢复，不会因工具单独跑通而自动通过。
- 原生任务 UI 取消后的状态文字、远程文件系统发布失败、真实服务器 SIGKILL/整机故障，没有在本轮扩大测试范围。没有停止运行中的 CN 集群。

## 复现资料

- 仓库测试：`GaussDBSessionLiveTest`、`GaussDBDumpAllLiveTest`；启用条件和安全约束见 integration/README.md。
- GUI 驱动新增 `parameter-fixture`；具体边界见 integration/swtbot/README.md。
- 本机临时证据目录：`/tmp/gaussdb-live-review-20260917.YM4ZVu/`。`AuthProbe.java`、`NativeRoundTrip.java` 为本轮认证/恢复探针；`kylin-queue/001…022.cmd.result` 为 UI 逐步记录；`kylin-parameters.png` 为实际截图。
- `blackhole-final.log`：增加网络故障用例后曾全绿，但其中一个用例是**已知阻塞行为的特征测试**，不能用 BUILD SUCCESS 宣称无缺陷。
- `marker-postbuild.log`：真实资源删除复现 `expected 0 but was 1`。之前 marker fixture 的 Long 行号类型、未触发 POST_BUILD 已修正，不把夹具错误当成产品缺陷。
- gs_dumpall 首次锁等待超时设置未能在测试期限内产生预期失败；改为终止空实例内专用备份 backend 后，失败/取消/成功三个场景通过。保留原失败日志，不覆盖历史。

## 最终测试结果

`remaining-final.log` 于 2026-09-17 19:48:00（上海时间）完成。使用 `-fae`，一个模块失败后仍执行后续 PostgreSQL 回归，未将未执行项算通过。

| 模块 | 总数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| platform | 448 | 0 | 0 | 3 |
| GaussDB model | 87 | 0 | 0 | 0 |
| GaussDB debug | 71 | 1 | 0 | 0 |
| PostgreSQL | 45 | 0 | 0 | 0 |
| 合计 | 651 | 1 | 0 | 3 |

**647 通过、1 失败、3 原有跳过，最终 BUILD FAILURE。** 唯一失败为真实 marker 删除后服务端仍有 1 个断点。网络无超时阻塞用例是有意记录现状的特征测试，计入 JUnit 通过但属于功能验收失败；认证探针和 8 个 SWT 行为检查在 JUnit 数字之外。

没有禁用失败用例、放宽断言或用前一轮全绿替代最终结果。Checkstyle/Spotless 仍按既有构建参数跳过，`git diff --check` 通过。

## 清理

本轮三个独立数据库及账号已删除，管理员查询残留均为 0；受限凭据文件已删除。全新空实例先经 gs_ctl 正常停止，再删除精确的本次临时目录 `/tmp/gauss-review-cluster-rHnwup`。其中只有合成数据，可由夹具重建，未删除原有实例数据。

恢复 HBA 前逐字比较，确认没有第三方改动；恢复后的 SHA-256 为 `e7bf4026e9e594463b92e6fc644b5aa311ff0bb2149c176e7dad8c26bc8a0060`，与本轮启动前一致。临时 55453 转发容器已移除。测试 GUI 已退出，麒麟测试容器恢复停止状态；集中式容器恢复原停止状态。运行中的 `gaussdb-507-cn-lab` 未重启、未改配置。

仅保留测试源码、报告、脱敏运行日志和独立测试客户端中的 bundle 更新。没有 commit/push，也没有替换客户候选制品。
