# 带括号集合查询的分类回归

## 缺陷

生产 `SQLQuery` 已将顶层 `SetOperationList` 识别为 SELECT，但外层有括号时，解析器返回 `ParenthesedSelect`，原分类分支遗漏该包装，导致 UNKNOWN。

新增六项生产入口测试均在修复前失败：单层 UNION、双层 UNION ALL、INTERSECT、EXCEPT、外层 ORDER BY、外层 LIMIT。日志 `/tmp/parenthesized-set-red-20260929.log`，共同断言为期望 SELECT、实际 UNKNOWN，不是测试夹具编译错误。

## 修复

增加仅用于识别的括号解包辅助方法，供分类和 `isModifying` 共同使用。解包后的集合查询识别为 SELECT/非修改语句；不替换原 statement，保留外层排序和限制语法树；不为集合查询设置单一物理编辑目标。

测试断言：SELECT 分类、无解析错误、语法树存在、两种实体元数据查询均为空、不是 plain SELECT、不是 modifying/mutating、无通配列索引。该测试不直接驱动结果集 UI，也不证明所有服务端函数无副作用或任意嵌套 SQL 均安全。

## 507 服务端语法对照

在本机分布式测试库通过独立 gsql 执行以下无写入 SQL，命令退出 0，结果分别为 `[1,2]`、`[1,2]`、`[1]`、`[1]`、`[1,2]`、`[1,2]`：

```sql
(SELECT 1 UNION SELECT 2);
((SELECT 1 UNION ALL SELECT 2));
(SELECT 1 INTERSECT SELECT 1);
(SELECT 1 EXCEPT SELECT 2);
(SELECT 1 AS "编号" UNION ALL SELECT 2) ORDER BY 1;
(SELECT 1 UNION ALL SELECT 2) LIMIT 10;
```

这是六种括号语法的服务端对照，客户端单测使用虚拟表名，不代表这些表已在真库创建，也不等于 DBeaver JDBC/GUI 端到端运行。未修改数据库数据。

修复后诊断日志 `/tmp/parenthesized-set-green-20260929.log`。整体回归必须独立核对失败、错误及跳过，不将 Maven 诊断退出成功当作全部验收。

最终投影元数据测试 **95/95** 通过，零跳过。73 模块诊断 **2536 项：2366 通过、169 跳过、1 Rest 本地网络权限错误**，验收校验拒绝。见 [逐项结果](evidence/PARENTHESIZED_SET_REACTOR_20260929.json)。辅助审核初始化日志权限失败，退出 1，没有审核结论；本轮未发布完整产品包或推送远端。
