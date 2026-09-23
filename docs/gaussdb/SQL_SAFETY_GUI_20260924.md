# SQL 执行安全确认：麒麟 GUI 验证

## 环境与范围

麒麟 V10 x86_64 隔离客户端，GaussDB 507 分布式 ORA 库 distributed_acceptance，客户端使用普通账号 distributed_lab。通过 SWTBot 操作实际菜单/按钮，另用独立 gsql 连接核对数据；没有直接调用确认框内部方法。

本次已加载 model 2.0.45.202609231613、model.sql 1.0.175.202609220932、SQL 编辑器 1.0.185.202609231537。因此不能用本次结果证明后续源码修复（包括 ONLY 回退及最近过滤/凭据改动）已经在 GUI 验证。

## 操作及结果

1. 创建独立 schema `dbv_safety_gui_20260924` 和表 guard_rows，id 为 1/2，label 均为 original，表归普通测试账号所有。首次 SET ROLE 被服务端拒绝，未继续执行建表；改由测试管理员创建表并明确变更 owner，未更改普通账号权限或密码。
2. 在真实 SQL 编辑器输入 `UPDATE dbv_safety_gui_20260924.guard_rows SET label='changed';`，点击“执行 SQL 语句”。出现中文“执行危险查询”对话框，说明没有 WHERE，SQL 预览只读，确定和取消可用（命令 629–631）。
3. 点击取消（632）。独立连接查询 count=2、original_rows=2，证明没有更新数据。
4. 再次从执行菜单发起相同语句（633–635），仍出现确认框；没有勾选“不再询问”。点击确定（636）。独立连接 count=2、changed_rows=2，证明确认后实际执行并提交。
5. 结束后编辑器改为只读 SELECT 文本，删除本次唯一测试表和 schema（没有 CASCADE），目录查询 remaining_schemas=0。删除的是可重建测试数据，不涉及用户对象。

![无 WHERE 更新确认框](images/sql-safety-20260924/update-confirm.png)

## 历史静态检查清单的区别

源码 `SQLQuery.isDeleteUpdateDangerous` 及 `SQLEditor` 执行确认提供的是 UPDATE/DELETE 外层 WHERE 存在性检查；`isDropDangerous` 是另一条 DROP 确认。`SQLRuleManager`、`LspSQLRuleManager` 的词法规则不等于 TPDSS 静态质量规则。

本轮没有找到以下规则在已检查 SQL 模型/编辑器路径中的独立质量诊断实现，仍记录为能力待确认/覆盖缺口，不算测试通过，也不擅自称为不适用：INSERT 显式列清单、ORDER BY 不用序号、LIKE 禁止前导通配符、NULL 禁止直接比较、二元两侧相同、SELECT 禁止星号、EXISTS 子查询要求 WHERE、CASE 重复 WHEN、NOT IN 子查询非 NULL。解析成功、生成 SQL 含列名、执行结果正确都不能替代对应告警验收。

DELETE/DROP 确认、多个危险语句、取消后的事务状态、ONLY 新修复以及持久化“不再询问”选项仍待 GUI 补验。JUnit 数量不因本次手动 GUI 场景增加。
