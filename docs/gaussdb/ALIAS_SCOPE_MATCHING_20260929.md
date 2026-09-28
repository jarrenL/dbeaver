# 查询列来源：别名大小写与歧义防护

## 新增覆盖

生产 SQLQuery/SQLSelectItem 新增12项：

- 五项 JOIN 来源边界：未限定列、找不到的限定符、两个物理表重复别名、派生表与物理表重复别名（两种顺序）。不能因为一个来源排在前面就推断列属于它。
- 七项 GaussDB 别名大小写/引号组合：未引用名称按方言折叠，引用名称保留大小写；正确组合定位 public.accounts，错误组合不关联。

红测7项失败：4个合法别名组合返回空，3个歧义限定符错误返回 public.accounts。日志 `/tmp/alias-scope-red-20260929.log`。重复别名是无效/歧义 SQL 的客户端防护测试，**不声称服务端允许其执行**。

## 修改

在 SQLQuery 中对当前 PlainSelect 的 FROM/JOIN 来源做唯一匹配，复用已有方言标识符归一化。派生表参与歧义检查，但不作为物理编辑目标；不进入子查询借用别名。SQLSelectItem 调用这个方法，继续执行原有 CTE 虚拟来源检查。

未修改通用 SQLSemanticProcessor 的其他调用者；未改变完整限定名的直接处理，不宣称已覆盖所有多级命名、LATERAL、USING/NATURAL JOIN 或结果集保存 UI。

修复影响共享 model 层，故执行包含 PostgreSQL 与数据编辑器模块的73模块诊断回归。已有物理JOIN、虚拟来源、嵌套别名及CTE场景同时复验。日志 `/tmp/alias-scope-green-20260929.log`。本轮没有真实 JDBC/GUI 验收结论。

最终投影类 **113/113 通过、零跳过**。全轮2558项：2384通过、173跳过、1 Rest网络权限错误，验收门禁仍拒绝。见[逐项结果](evidence/ALIAS_SCOPE_REACTOR_20260929.json)。
