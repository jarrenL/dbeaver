# 带括号 SELECT 与锁定分类

## 复现与修复

六项新增回归均在修复前得到 UNKNOWN 而不是 SELECT，覆盖单层/双层普通查询、排序 LIMIT，以及单层/双层 FOR UPDATE、FOR SHARE。`isModifying` 对 UNKNOWN 返回 false，因此锁定查询的标记也存在遗漏。红测日志 `/tmp/parenthesized-lock-red-20260929.log`。

生产 SQLQuery 将带括号且内层为 PlainSelect 的查询识别为 SELECT；分类辅助方法只读取内部语句，不替换原 AST。`isModifying` 读取内部 SELECT 的 FOR/INTO 标志，普通查询 false、锁定查询 true。

不把包装查询暴露为 plain SELECT，也不新增单表写入目标推断。这里的 modifying 是既有 API 对锁定/SELECT INTO 的分类，不表示测试执行了 INSERT/UPDATE/DELETE，更不能据此认定所有 SELECT 函数无副作用。

## 断言

- SELECT、无解析错误、原 AST 仍为 ParenthesedSelect，SQL 文本不变。
- 锁定与普通查询各自的 modifying 标记正确。
- plain SELECT 为 false，两种实体目标查询均为空。
- 同对象换成普通单表查询后能恢复表元数据；reset 返回原括号查询后，分类/锁定标记恢复，旧表元数据不残留。

## 服务端语法对照

本机507分布式测试库执行下列只读字面量查询，退出0，各返回1：

```sql
(SELECT 1);
((SELECT 1));
(SELECT 1 ORDER BY 1 LIMIT 5);
(SELECT 1 FOR UPDATE);
((SELECT 1 FOR UPDATE));
(SELECT 1 FOR SHARE);
```

该对照没有实际表行，不是锁等待、并发竞争或事务恢复验证。客户端元数据测试使用虚拟表名，不代表 GUI/JDBC 端到端已通过。未执行数据库写入。

复验日志 `/tmp/parenthesized-lock-green-20260929.log`；诊断构建的成功退出不能代替整体验收门禁。

最终投影元数据 **101/101 通过，零跳过**。73 模块共2546项：2372通过、173跳过、1 Rest网络权限错误；独立验收校验仍拒绝。见[逐项回归](evidence/PARENTHESIZED_LOCK_REACTOR_20260929.json)。之前新增四项真实JDBC集合查询依然未完成，不被本次组件测试替代。
