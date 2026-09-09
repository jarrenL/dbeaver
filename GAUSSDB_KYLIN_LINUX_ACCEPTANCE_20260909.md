# 麒麟 V10 Linux 客户端交付验收（进行中）

客户目标：银河麒麟 V10 **服务器版**，CPU 未知，同时准备 x86_64 / ARM64。

## 已确认的环境与启动修复

- 两个独立 Docker 容器：`dbeaver-kylin-arm64`、`dbeaver-kylin-x86_64`。
- 用户空间为 Kylin Linux Advanced Server V10 (Lance)，glibc 2.28、GTK 3.24.21。
- 基础镜像为第三方 `hxsoong/kylin:v10-sp3`，摘要 `sha256:b49a72ecf3213e00c5316935a51594c11d43fbccb13ad0cecf939f0f1c1101a3`；软件包由镜像配置的麒麟更新仓库安装。这不是麒麟官方认证镜像。
- Docker 共用宿主虚拟机内核；x86_64 使用 Rosetta 仿真。不能把测试结果表述为客户物理机器、飞腾/鲲鹏硬件或完整麒麟内核的认证。
- 原始产品启动器要求 GLIBC_2.33/2.34，SWT 要求 GLIBC_2.34，在 glibc 2.28 上无法启动。
- 已在两个麒麟环境重新编译 SWT `v4973r12` 的 GTK3 原生库，保持与产品 Java bundle 的 JNI 版本一致。Kylin GCC 7.3 不识别 `-std=gnu17`，构建参数改为 `-std=gnu11`；保留 `-Werror`，两个架构均编译成功。未替换客户系统 glibc。
- 启动器使用 Eclipse Equinox `R4_37` 的源码重新编译，原生库版本 `11916`，与产品平台 fragment 中的版本相符；没有把 R4_40 的 `11921` 库混入最终测试包。
- 两种架构均捆绑各自 Temurin 21 JRE，并通过原生 `dbeaver` 启动器完成首次配置。SWTBot 已进入主窗口，能读取控件并执行新建连接操作。
- 首次配置须启用 **Procedure debugger**。测试实例关闭 AI integration、匿名统计和每日提示；客户交付说明需写明调试器启用方法。
- 产品构建日志 `/tmp/dbeaver-linux-delivery/product-build.log`：2026-09-09 17:48，BUILD SUCCESS。
- 两架构均通过客户端连接向导的 **Test Connection**：目标 `host.docker.internal:55452/package_lab`，普通用户 `package_tester`；返回 GaussDB Kernel 507.0.0 `d791c80a`。ARM64 201 ms（命令结果 `0014.cmd.result`），x86_64 419 ms（`0016.cmd.result`）。这是连接验证，不等同于调试或包编译通过。
- Native 驱动依照 UI 提示手动配置测试用 `/work/gaussdbjdbc.jar`（已有 506.0.0.b058 文件）。尚未确认再分发许可，因此干净交付候选包暂不内置此 JDBC 文件。
- 初始精简镜像缺少 WebKit，主窗口 Chat 面板报无底层浏览器；已安装麒麟仓库 `webkit2gtk3`，需重启复测。不可忽略面板加载错误。

## 当前产物（内部测试，尚未交付）

`/tmp/dbeaver-linux-delivery/{aarch64,x86_64}/dbeaver`：重建原生库及内置 JRE 的干净产品副本。

同目录 `test-dbeaver`：仅供验收，额外安装 SWTBot；**不可作为客户包交付**。

两容器测试应用 `/opt/test-dbeaver`，工作区 `/work/acceptance`，测试命令及结果 `/work/queue`，日志 `/work/test-dbeaver.log`。这些目录目前不代表完整验收证据。

## GUI 实测记录（不替代最终交付包验收）

### 追加实测：重启及包操作

- 两端正常退出，日志明确记录 shutdown completed；重新通过原生启动器启动并展开保存连接成功。测试启动须使用 `--launcher.appendVmargs`，避免命令行测试参数覆盖产品 `dbeaver.ini` 的模块打开参数。
- 安装 WebKit 后新启动日志不再出现缺少浏览器错误，但旧工作区恢复的 Chat 错误占位仍可见。最终干净工作区须检查，不以“日志无报错”代替 GUI 检查。
- ARM64/x86_64 均从导航树右键执行 `Compile Package`、`Compile Package Specification`、`Compile Package Body`，`ui_pkg.pkg_ui` 三次均报告成功（`0037/0039/0041.cmd.result`）。数据库执行 `ui_pkg.pkg_ui.plus_one(41)` 返回 42。
- 对 `ui_pkg.boundary_body` 执行主体编译，两端均显示 BODY 第 3 行错误（`0043.cmd.result`）；双击错误表格后，实际正文编辑器 `caretLine=3`，选中了缺失类型的源码行（`0045.cmd.result`）。
- 两端分别创建并批量删除两个专用临时包：`linux_arm_delete_a/b` 和 `linux_x86_delete_a/b`。GUI 确认框明确列出各自两个对象（`0053.cmd.result`），确认删除后数据库查询四个名称返回零行。创建脚本 `/tmp/dbeaver-linux-delivery/linux-delete-fixtures.sql` 可重建。没有删除原有包。
- ARM64 调试：配置名 `Kylin ARM64 GaussDB acceptance`，普通用户 `dbeaver_ui_0909`，原生 JDBC，入口 `ui_acceptance.ui_parent(6)`。会话实际暂停在第 4 行；F8 到第 5 行，变量窗格 `p_in=6`、`v_local=7`；修改后读回 `v_local=12`，Watch 窗格也显示 12（`0115/0119/0122/0124`）。
- ARM64 跨 schema：F7 进入 `ui_cross_0909.ui_child` 第 4 行，正文包含 `+102`，栈显示父子两层；双击父帧返回父第 5 行（`0126/0128`）。子第 5 行创建断点，F9 实际命中；断点窗格取消勾选/重新勾选分别得到 false/true（`0134/0136/0138/0140`）。
- ARM64 Shift+F7 回到父第 6 行（`0142`）；F9 完成后出现 Commit/Rollback 对话框（`0144`）。选择 Rollback 后独立数据库连接查询仍为 `count=2,sum=24`，与本轮开始前一致；调试树显示 terminated（`0146`）。提交、终止及 x86_64 调试尚需测试，不据此宣称已完成。
- 验收容器已安装并启动 `metacity` 窗口管理器。无窗口管理器时焦点事件不可靠；此后按键测试在真实窗口焦点下执行。控件快照里的未显示虚拟树子项可能残留旧文本，必须实际展开/显示后再操作，不把残留文本作为数据库对象归属证据。
- ARM64 删除断点后，断点窗格不再包含对应条目（`0150`）。重新执行 `ui_parent(6)`，无断点拦截，选择 Commit（`0154/0155`），独立查询为 `count=4,sum=140`：新增子过程 109 和父过程 7，符合预期。
- ARM64 再启动一轮，实际按 F10 终止；树显示 terminated（`0160`），独立查询仍 `count=4,sum=140`。原有基线及本轮提交测试数据均保留，没有为了制造通过结果而清空审计表。
- 可复现脚本：`product/community/kylin/build-natives.sh` 已在两容器完整执行成功；`assemble.sh` 已各组装一份新候选目录 `release-candidate`，剔除被重建破坏的 SWT 原 Eclipse 签名。新候选包仍须进行干净包验收和许可证整理。
- x86_64 已正常退出并更新为脚本产出的启动器和 unsigned SWT JNI 包，重新启动成功。测试准备复用 ARM64 的 `.launch` 配置并替换为本端真实数据源 ID；随后通过客户端配置窗口核对 `ui_acceptance.ui_parent(int4)`、参数 6、Debug 按钮启用，再点击 Debug。已真实暂停于父过程第 4 行（`0195/0197`）；x86_64 其余交互尚未据此计为通过。
- x86_64 后续实测：F8 到父第 5 行，`v_local=7`；修改为 13 后变量与 Watch 均读回 13（`0204/0207/0209`）。F7 进入跨 schema 子过程第 4 行，双击父帧定位父第 5 行（`0211/0213`）。子第 5 行断点创建并被 F9 命中（`0217/0218`），取消勾选/重新勾选分别 false/true（`0220/0222`），Shift+F7 返回父第 6 行（`0223`）。
- x86_64 结束回滚（`0224/0225`）后独立数据库查询仍 `count=4,sum=140`；断点删除后不再出现（`0229`）。下一轮无断点执行并提交（`0232/0233`），查询 `count=6,sum=256`，新增 109+7。第三轮 F10 实际终止（`0237`），数据仍为 `6/256`。
- x86_64 仿真中的 SWT `Display.post` 没有稳定送达按键；快捷键通过已验证主窗口 `4194318` 的 X11 系统级按键事件发送，实际暂停行/状态改变才计为通过。此区别是测试注入机制，不能将未送达的按键直接记为客户端功能失败或通过。

- 两架构 `0239.cmd.result`：在各自 Linux 客户端内，通过实际连接的 HA 实例分别读取 `dbeaver_ext_0909_a/b/c/pg/m` 五库模型。结果一致：A=ORACLE，B=MYSQL，C=TERADATA，PG=POSTGRES，M=M；仅 A 的 packageSupported=true；M 的 storedProcedureSupported=false。此条为连接到真实数据库的模型能力验证，不冒充五种模式全部 SQL 语法测试。
- ARM64 `0247`：客户端 Test Connection 连接 PostgreSQL 16.15，实际下载/加载官方 PostgreSQL JDBC 42.7.13，连接成功。PG 目标为专用回归容器 55439，并非 GaussDB 冒充 PG。
- ARM64 PG `0289/0291/0293`：父函数停于第 4 行，Step Over 到第 5 行，Step Into 进入 `ui_cross_0909.child(integer)` 第 3 行；实际编辑器为函数正文、包含 `PERFORM 1;`，栈为子第 3 行/父第 5 行。
- ARM64 PG `0294`：在子函数第一条语句（正文第 3 行）建立断点，日志记录发往服务端的断点行 `-1`。完成本轮后审计表从 `count=6,sum=468` 变为 `7/577`（新增 109）。重新启动到父第 4 行后直接 Continue，`0303` 实际命中子第 3 行；不是仅把 GUI 勾选状态当作命中。
- PG Step Return 仍保持禁用：该会话原实现 `canStepReturn=false`，`execStepReturn` 明确未实现；共享框架转发修复不代表 PG 协议新增单步退出能力。GaussDB 的 Shift+F7 已另行双架构验证，不能混淆。
- ARM64 PG `0307/0308`：变量窗格将子参数 p 从 7 修改为 8，重新读取值为 8；`0310` Watch 显示 8；继续完成后独立查询 `count=8,sum=687`（新增 110）。禁用第一行断点再运行，直接完成且没有命中子函数（`0316`），查询 `9/796`（新增 109）。
- ARM64 PG 重启回归：重新启用子第 3 行断点后正常退出，日志记录 shutdown completed；重启前替换为最终脚本组装的 unsigned SWT jar。恢复编辑器初始为完整 CREATE FUNCTION（`0321`），启动调试直接继续后仍命中子第 3 行，既有编辑器切换为函数正文、实际 `caretLine=3`（`0325`）。终止后树为 terminated（`0327`），独立数据库结果仍 `9/796`。恢复输入丢失调试属性的问题已在真实重启场景回归通过。
- x86_64 干净候选包已复制到独立 `/opt/delivery-candidate`，用全新 Linux 用户 `deliverycheck`、全新工作区 `/work/clean-product/workspace`、独立显示 `:100` 启动；没有 SWTBot 插件/启动参数。首次向导启用 debugger、关闭 AI/统计/提示，进入正常主窗口。`evidence/x86_64/clean-main.png` 中 Chat 不再报缺少底层浏览器；图形连接及正常退出/重启仍需继续验收。
- x86_64 干净包追加：通过 GUI 添加 `/work/gaussdbjdbc.jar`、配置 `package_lab` 普通用户，Test Connection 显示 Connected (446 ms)，Kernel 507（`evidence/x86_64/clean-connection.png`）；保存连接后正常退出，日志 shutdown completed、Java 进程已结束。
- **重启发现新的实际缺陷，尚未复测闭环**：关闭 AI 后 Chat 的扩展被过滤，但默认透视图无条件加入 Chat、旧工作区仍恢复它，出现“Could not create the view: com.dbeaver.ai.chat”占位。首次正常启动不等于重启全绿。已修改 `DBeaverPerspective`，首次布局遵循 AI 开关及视图注册状态；`ApplicationWorkbenchWindowAdvisor` 清理旧工作区中已不可用的 Chat 引用。71 模块验证成功，完整产品正在重建；旧 `candidate-*` 尚未包含此补丁，不能正式交付。
- 测试 harness 的 `modes` 指令构建了不在导航缓存中的临时数据库实例，关客户端时曾报告连接未收尾；已在 finally 中对这些临时实例调用 shutdown。只修改测试工具、不属于产品连接泄漏；测试工具已编译，重新加载后的运行/退出验证尚待执行。
- Chat 修复追加复测：完整产品 `202609091107` 构建成功（20.886 秒），71 模块验证成功（22.609 秒）；两架构已组装 `candidate-r2-*`。x86_64 从旧错误工作区用新包 `/opt/delivery-candidate-r2` 重启，错误 Chat 已消失，其他视图和已保存连接保留；展开保存连接能正常重连（`evidence/x86_64/clean-restart-fixed.png`、`clean-reconnected.png`）。首次新工作区禁用 AI 的路径和 ARM64 新包还需验证。
- 重连日志另暴露已有 GaussDB 类型目录兼容警告：`typtype=o` / `typcategory=F` 被通用 PG 枚举拒识并回退，连接仍成功。需核对实际类型语义与回退结果，再决定修复方式，不能将这些栈信息忽略成“日志全绿”。
- 已组装可持久保存的双架构候选目录：`/Users/lj/Documents/GaussDB/deliverables/kylin-v10-20260909/candidate-aarch64` 和 `candidate-x86_64`，仍未作为最终归档发布。新增 ELF 架构与 JRE release 架构检查；故意把 x86_64 原生输出作为 ARM64 输入时明确拒绝，并且没有创建输出目录。
- JRE 21.0.12.1+1 两架构及对应源码均与官方发布 SHA-256 一致；对应源码、SWT/Equinox 源码已收集到交付暂存区。中文运行说明与来源说明位于 `product/community/kylin/README.zh-CN.md`、`THIRD-PARTY-SOURCES.md`。

## 尚需完成（不得标记全绿）

追加收尾记录（19:26）：两架构 `candidate-r4`（产品时间戳 `202609091122`）均由普通用户、无测试插件启动。ARM64 GUI Test Connection 返回 Connected (252 ms)、Kernel 507，保存后正常退出；两架构升级候选并重启后保存连接可展开重连，主界面无 Chat 错误。`evidence/{arm64,x86_64}/clean-r4-connected.png` 与各自 `r4-launch.log` 证明本轮重连；日志不再出现类型枚举拒识。内置 JRE 两端均实际运行并返回 Temurin 21.0.12.1+1。启动器和七个 JNI 库 ldd 检查将内置 JRE 的 lib/lib/server 纳入解析路径，两端无缺失依赖（单独裸 ldd 的 libjawt 需由内置 JRE 提供，不能误记系统缺包）。

追加收尾记录（19:23）：ARM64 `candidate-r3` 全新用户/工作区首次配置关闭 AI 后，主窗口没有 Chat 错误占位，截图 `/tmp/dbeaver-linux-delivery/clean-arm-r3-main.png`。x86_64 旧工作区恢复的修复证据见上文。GaussDB 507 目录另查得 `s/H=anyset`、`b/L=rowid`、`b/M=uint1..uint8`、`u/W=undefined`，已补齐枚举识别，并保持既有 OTHER 映射，不声称新增内部类型编辑能力。新增测试覆盖这些目录组合和集合元素映射；71 模块 verify 成功（21.386 秒，`catalog-tests.log`），完整产品重建成功（`catalog-product.log`）。此补丁尚待干净客户端重新加载后检查日志。

1. 测试工具临时五模式连接清理已闭环：`0333` 五库读取成功，日志显示每库 Main/Metadata 成对关闭，正常退出完成；`evidence/arm64/modes-cleanup.log`。
2. 固定源码提交、最终归档、校验和，并再次检查归档内容与已测候选目录一致。
3. 完成逐项交付审计；不把已测五模式识别/能力模型扩大为五模式全部 SQL 或所有菜单组合认证。

## 原始七项证据索引（当前环境）

| 原始需求 | ARM64 / x86_64 证据 | 判定及边界 |
| --- | --- | --- |
| Oracle/B/MySQL 等兼容模式 | 两端 0239，ARM64 0333；五个真实数据库 | 模式识别与数据库级能力通过；M 关闭 PL 过程/包；非所有 SQL 语法认证 |
| F7/F8/Shift+F7/F9/F10 调试 | ARM64 0119/0126/0142/0136/0160；x86_64 0204/0211/0223/0218/0237 | 有真实暂停位置、父子栈和终止结果，不只检查快捷键注册 |
| 断点管理 | ARM64 0134–0140、0150；x86_64 0217–0222、0229 | 创建并实际命中、启禁与删除通过 |
| 变量查看/修改/监视 | ARM64 0122/0124；x86_64 0207/0209 | 修改值 12/13 并在 Watch 读回；仅变量名监视，不支持任意表达式 |
| 调用堆栈/双击导航 | ARM64 0126/0128；x86_64 0211/0213 | 跨 schema 子源码 +102、父调用行正确 |
| 完成后提交/回滚 | ARM64 0144–0146、0154–0155；x86_64 0224–0225、0232–0233 | 对话框选择与独立数据库查询联合验证；最终审计 6 行、合计 256 |
| 包编译/错误定位/批量删除 | 两端 0037/0039/0041、0043/0045、0053–0055 | 集中式 ORA Kernel 507，ALL/SPEC/BODY；BODY 第 3 行跳转；各自两个专用包删除成功 |

本轮最终自动回归 600 项：597 通过、3 跳过、0 失败/错误（platform 448，GaussDB model 67，GaussDB debug 40，PostgreSQL 45）。71 模块 verify 与完整产品构建日志在 evidence/catalog-tests.log、catalog-product.log。跳过项不计入通过。
