# GaussDB SWTBot 客户端验收（2026-09-09）

## 结论与范围

本轮已获用户授权使用 SWTBot。在 macOS ARM64 的真实 DBeaver 客户端、
原生 GaussDB JDBC 和本机 `gaussdb-507` 分布式 Docker 上完成下列界面操作。
这不是“七项全部验收通过”：集中式 ORA 包编译仍等待连接环境；Windows
只验证构建，尚未执行 Windows GUI 验收。兼容模式的客户版本矩阵也未在本轮重跑。

测试使用独立数据库/用户 `dbeaver_ui_0909`、`ui_acceptance` schema，未修改业务对象。
测试账号通过 `gs_role_pldebugger` 授权，不使用管理员身份代替普通用户验收。
测试客户端是构建产品的临时副本，附加 test-only SWTBot bundle，并在重启后
装载本轮编译的生产类；最后另行执行完整产品构建。SWTBot 不进入发布 feature。

## 客户端观察结果

| 场景 | 结果与实际观察 | 本机队列证据编号 |
| --- | --- | --- |
| 新建调试配置 | 连接、routine 选择、输入参数 10，普通授权用户成功暂停 | 首次配置流程及 141 |
| F8 | parent 第 4 行到第 5 行，v_local=11 | 141–144 |
| 修改变量、添加监视 | Change Value 将 11 改为 20；Expressions 显示变量名 watch 值 20 | 147–150 |
| F7 | 进入 child 第 4 行；两个不同函数栈帧和 child 源码 | 151–152 |
| 双击堆栈导航 | parent/child 源码随栈帧选择切换 | 153–156 |
| Shift+F7 | 从 child 返回 parent 第 6 行，剩一个栈帧 | 178–179 |
| F9 与提交 | 完成后出现事务选择；Commit 后独立查询得到 11、13 两行 | 180–183 |
| 回滚 | 再次执行后 Rollback，独立查询仍 count=2、sum=24 | 198、211、232 |
| 添加断点 | 第 6 行新增断点，F9 实际命中第 6 行 | 216–219 |
| 禁用断点 | 取消勾选后下一次执行跳过第 6 行，直接出现事务选择 | 229–231 |
| 重新启用 | 勾选后 F9 再次命中第 6 行 | 258–263 |
| 删除、重新添加 | Breakpoints 窗格删除后该项消失，同一行重加后 F9 命中第 6 行 | 276–283 |
| F10 | target terminated，无错误弹窗，独立查询 count=2、sum=24 | 264–265、284–285 |
| 首次调用栈 | 并发修复后首次暂停只有一个 parent 第 4 行栈帧，F8 正常 | 271–273 |

证据目录：`/tmp/gaussdb-swtbot/queue/`，编号对应 `NNN.cmd.result`。
这是本机临时证据，不是可长期依赖的仓库附件。命令文件中的 `OK` 仅表示动作完成，
以上结果依据后续 widget dump 和独立 JDBC 查询，不把 `OK` 当作验收断言。
驱动过程中个别旧 widget ID 导致测试命令失败，重新 dump 后更正；不计为产品缺陷。
macOS SWTBot 截图曾返回空白，未将空白截图用作通过证据。

## 本轮实测发现并修复

1. 普通用户的 `pg_roles` 看不到内置调试角色，旧查询误拒绝已授权用户。
   改为直接 `pg_has_role`，保留管理员、未授权及角色不存在的分支测试。
2. GaussDB backend PID 实际为 64 位；`getInt` 溢出。改用 long/getLong。
3. Watch 在会话结束后访问空 session，改为安全返回空变量列表。
4. 共享断点描述符丢失 routine OID；调试模型 ID 又被错误地与 marker 类型比较。
   保留完整描述符，按模型和数据源路由，初始化只安装启用的断点。
5. 用户 F10 导致的服务端 abort 被当作失败弹窗。关闭期间归为取消，真正执行异常仍报错。
6. 栈视图和 watch 并发读取时重复追加初始栈帧。同步快照读取，重建时替换旧快照。

上述修复均新增回归测试。共享 debug.core 的变更也影响 PostgreSQL，不能把本轮
GaussDB GUI 成功视作 PostgreSQL 客户端验收已完成。

测试 fixture 还修正了过程内部调用：该 GaussDB 507 环境的 `CALL ui_child(...)`
引发运行期 query has no destination 错误，改为过程体内 `ui_child(...)`。
这是测试 SQL 修正，不是静默忽略目标过程异常。

## 自动化与构建

- 71 模块离线 verify：590 项，587 通过、3 跳过、0 失败/错误。
  分项：platform 448（3 跳过）、GaussDB model 62、GaussDB debug 39、PostgreSQL 41。
  日志 `/tmp/gaussdb-swtbot/stack-fix-tests.log`，完成时间 12:15:45。
- 完整产品构建 BUILD SUCCESS，完成时间 12:19:17；包含 macOS/Windows 产品。
  日志 `/tmp/gaussdb-swtbot/stack-product.log`。构建成功不等于各 OS GUI 验收。
- SWTBot 驱动、安装说明与 fixture：
  `test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/`。

## 待验收项和环境输入

集中式 ORA：SPEC/BODY/ALL 编译菜单与实际命令、无效对象状态、GS_ERRORS 行列定位、
双击跳转、多选删除及数据库结果核对。分布式环境不支持 CREATE PACKAGE，不能替代。
用户表示可提供集中式环境，尚未提供连接信息。

请提供主机、端口、数据库、GaussDB 版本、测试用户名，并确认可创建/删除独立测试
schema；密码放本机受限配置文件后提供路径，不写入报告或聊天。测试用户需拥有测试包
及读取编译错误所需权限。另需按客户目标版本补兼容模式矩阵和 Windows GUI 验收。

当前测试对象保留以便复测，已提交的测试审计数据仅为 11、13；其他轮次回滚或终止。
