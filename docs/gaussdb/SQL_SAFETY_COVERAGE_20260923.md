# SQL 静态检查迁移：执行安全检查

## 修复后结果

当前生产代码在完整解析失败时，使用同一解析器的词法token进行外层UPDATE/DELETE、WHERE识别。括号内子查询、注释和引用字符串不能代替外层WHERE；支持WITH外层DML。该回退不改写执行文本，也不证明SQL语法合法。词法错误或超时仍不能确定语句类型，不宣称这是完整的安全审计机制。

新增9个ONLY组合边界及9个回退/原文不变测试后，本类51项全通过。最终run-rAR5u0：1063总数、1039通过、24跳过、0失败/错误，见`test-results-20260923-sql-safety-green.json`。保留下面红灯证据，不能把修复前的失败记录删除成全程成功。

### GaussDB 507真实语法验证

使用独立gsql本地管理员会话，集中式和分布式各执行同一临时表脚本：BEGIN→建临时表→插入2行→UPDATE ONLY无WHERE，返回UPDATE 2且目标值计数2→DELETE FROM ONLY无WHERE，返回DELETE 2且剩余0→ROLLBACK。会话结束后两实例目录中该测试表均为0。证明漏报涉及实际可执行的全表修改语法，不是无效SQL。脚本在测试仓`fixtures/sql-safety-only.sql`。

第一次分布式准备使用ON COMMIT DROP被服务端拒绝，未执行DML；最终脚本移除该非必要选项，依靠显式回滚及会话临时表清理，两环境重新通过。此验证不是普通用户权限测试，也不是JDBC/UI确认链路；相关GUI验收仍待补。临时回归grantee角色已删除。

对应历史清单5.1。测试调用SQL编辑器实际使用的`SQLQuery.isDeleteUpdateDangerous()`和`isDropDangerous()`，不是在测试代码中另写一个检查器。

新增`GaussDBSQLSafetyTest`共33个参数化场景：12个缺失外层WHERE、12个存在外层WHERE或不属于该规则的语句、6个DROP、3个注释/字符串中出现DROP。

## 修复前结果：发现缺陷

run-y1bSnO中31个新增场景通过，2个失败：

```sql
UPDATE ONLY public.t SET amount=1;
DELETE FROM ONLY public.t;
```

两条语句都没有限制行范围，却得到`isDeleteUpdateDangerous()==false`。修复前`SQLQuery`解析失败时statement为null，危险检查直接返回false。保留原预期true，通过上述生产修复恢复检查，没有跳过或降低断言。

本轮完整结果以`test-results-20260923-sql-safety-red.json`为准；上一轮1012项无失败不代表本轮新增检查通过。这里只解析SQL，没有执行上述UPDATE/DELETE/DROP，真实数据库回归仍使用隔离测试对象，临时授权角色已清理。

## 已证明的范围与未证明的范围

- 外层WHERE和子查询WHERE不同；仅子查询含WHERE仍应触发确认。本次场景通过。
- 注释或字符串中的WHERE不能代替实际WHERE。本次场景通过。
- `WHERE 1=1`当前不会触发此规则：这是“是否有WHERE”检查，不是“是否安全”证明，不能宣传为全表操作检测完整覆盖。
- DROP识别与UPDATE/DELETE确认是不同规则，测试分别断言。
- GUI确认弹窗、用户取消后是否不执行、偏好设置开关尚需界面验证。
- 清单5.2的INSERT显式列名、ORDER BY列名、LIKE前导通配符、NULL直接比较、重复表达式、SELECT星号、EXISTS的WHERE、重复CASE条件、NOT IN可空子查询仍需逐项核对并补覆盖。不能把语法解析成功或本报告的执行确认当成这些规则已实现；也不能直接将它们标为不适用。
