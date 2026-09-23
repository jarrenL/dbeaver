# 授权和撤销：实际访问补验

对应历史清单 9.3（权限生成/授权撤销）与 3.10（角色权限）。

## 方法

扩展 `GaussDBHistoricalJdbcLiveTest.productionGrantCommandsPreservePerPrivilegeGrantabilityInDatabase`。原测试已经执行生产 `PostgreCommandGrantPrivilege` 生成的 SQL 并读取真实 ACL。本轮使用 `GAUSSDB_HISTORY_VERIFY_ROLE_ACCESS=YES` 额外启用实际角色访问，不能把未启用该选项的历史运行算作访问验证。

管理员临时创建专用 NOLOGIN 测试角色，并将其授予专用测试账号以允许 SET ROLE。没有修改业务角色或给予管理员权限。测试只创建随机隔离 schema 和一张两列表，向受限角色授予该 schema 的 USAGE。

507 首轮仅有成员关系时 SET ROLE 被拒绝，不能算实际访问已验。测试改为读取进程环境 `GAUSSDB_HISTORY_GRANTEE_PASSWORD`，使用 SET ROLE 的 PASSWORD 子句。临时口令由本地加密随机数生成，经管理员标准输入设置，再只传入本轮测试进程；不写入仓库或打印。不要发布原始 Maven/Surefire XML 或包含环境变量的日志。

第二轮切换角色成功，但分布式表读取被节点组 `dual_group` 的权限拒绝。管理员额外授予测试角色 `USAGE ON NODE GROUP dual_group` 后重跑，以确保测试针对表级权限而非节点组前置权限。节点组授权也是临时的，结束后撤销。

执行生产生成的授权 SQL 后，切换到受限角色，读取 id=7 的唯一一行；尝试修改未授权列应返回 SQLSTATE 42501。RESET ROLE 后继续执行生产撤销 SQL，再切换受限角色，SELECT 与 UPDATE 均应返回 42501。角色恢复放在 finally 中，schema 按原测试框架清理。

## 范围

这是实际 JDBC/数据库权限执行证据，不是权限编辑器 GUI 验收；NOLOGIN 角色通过 SET ROLE 测试，不证明该角色可登录。角色成员关系只用于专用测试账号，结束后撤销并删除测试角色。

## 结果

补齐上述前置权限后，run-oDOwdM 启用实际角色访问分支并通过：授权后读取唯一值 7；无 UPDATE 权限时修改被 42501 拒绝；撤销后 SELECT 也被 42501 拒绝。全量回归 1543 项，1520 通过、23 跳过、0 失败/错误，见 `test-results-role-access-20260924.json`。本轮扩展已有测试方法，方法总数未增加。

已撤销节点组 USAGE、撤销测试账号的角色成员关系并删除角色；管理员独立查询确认同名测试角色数量为 0。本次不修改生产实现，仅增强实际数据库权限验证。

## 列级访问扩展

同一测试方法的列授权阶段增加实际访问断言（仍需启用上述环境选项）：

初次更新 id 被“当前版本不能修改分布键”拒绝：默认分布键为 id，不能用这个失败判断列权限有误。实际访问测试表改用独立 shard_id（默认值 1）作为明确的 hash 分布键，id 为普通列；该分布键限制不宣称已修复或不受限制。

- 表级权限已撤销，只给 id 列 SELECT/UPDATE；受限角色能读到 7 并将该列更新为 8，影响一行。
- 读取 restricted_value 或 SELECT *、修改 restricted_value 均必须返回 42501。
- 撤销列权限后，读取 id、修改 id 也必须返回 42501。
- 每个阶段 RESET ROLE 后，表所有者检查唯一行仍为 id=8、restricted_value='restricted'，验证失败的读写没有改变数据。

该扩展不代表所有列类型、角色继承组合、集中式及权限 GUI 已验收。

run-JjrTL6 实际启用列级访问分支后通过：上述读写/拒绝/撤销及所有者数据检查全部满足。回归1543项，1520通过、23跳过、0失败/错误，报告 `test-results-column-access-20260924.json`。本轮临时成员关系、节点组使用权限已撤销，角色已删除并独立确认剩余0。
