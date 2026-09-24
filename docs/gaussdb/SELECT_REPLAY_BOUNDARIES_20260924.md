# SELECT自动重放的副作用边界

SQL_REPLAY_SAFETY_20260924.md已记录INSERT断连后禁止内外层重放的GUI验收。本轮补SELECT表达式层面的风险，不将SELECT语法等同于无副作用。

## 新增规则

SQLQueryRecoveryPolicy遍历已解析的语法树：函数调用（包括schema限定、表达式和表函数）、序列取值、窗口函数、FOR UPDATE/SHARE加锁、WITH/INTO均不允许自动重放。显式补查默认TablesNamesFinder没有遍历的ORDER BY、GROUP BY、QUALIFY、LIMIT/OFFSET/FETCH、DISTINCT ON。具名WINDOW、LIMIT BY及PIVOT/UNPIVOT保守拒绝。解析或遍历失败也拒绝。

没有引入函数名“安全名单”：即使名称看似只读，也可能被schema或重载替换；本规则会让count/自定义纯函数等同样不再自动重试。普通字段查询、常量SELECT、常量分页和DISTINCT仍允许既有恢复。注释/字符串中的函数样式文本不算函数调用。

## 证据

新增首批18项在run-71IPa0全部复现旧策略允许重放，完整1622/1581通过/23跳过/18失败。实现后首次编译run-piNVBr因JSQLParser的Statement/Expression重载歧义失败，显式选择Statement入口后run-EGRBNV为1622/1599通过/23跳过/0失败错误。编译失败不是产品红测。

随后新增6项额外子句拒绝、2项普通分页/DISTINCT保留测试。测试断言是“拒绝重放”或“保留恢复”，不是数据库接受所有方言SQL的断言；不支持的语法也应走拒绝路径。

run-Oexw5t完整回归1630项，1607通过、23跳过、0失败错误；本轮净新增26项全部通过。安全结果清单test-results-select-replay-boundaries-20260924.json。

## 麒麟 GUI 复验

在独立麒麟 V10 x86_64 测试客户端安装模型插件 `2.0.45.202609240227`，正常退出并重新启动，连接 GaussDB 507 分布式验收库。仅操作专用测试账号的 SQL 编辑器会话。

1. 队列 1112 执行 `SELECT pg_sleep(120) /* dbv_gui_inflight_disconnect_20260924 */`。独立连接确认该查询处于 active，记录 backend_start 与 query_start，避免将复用的进程号误认为同一连接。
2. 按进程号、数据库、测试账号、SQL 编辑器 application_name、active 状态及唯一 SQL 标记联合筛选，终止这个测试会话；服务端返回 true。
3. 1113 显示 SQLSTATE `57P01` 和连接终止错误，独立查询确认对应标记的 active 会话数为 0，没有出现旧版本的自动重放。未执行手动取消。
4. 1114 在同一编辑器显式执行新的常量查询；等待异步完成后，1115 的实际结果文本显示 `恢复验证中文` 与 `42`，一行结果。说明禁止重放不会阻止用户后续发起新查询及连接恢复。

上述指定场景通过；没有创建或修改业务对象。它验证函数查询的断连处理，不等同于验证所有有副作用函数、提交响应丢失或网络分区。本次仅更新模型插件；SQL 编辑器插件沿用已验的 `1.0.185.202609240207`。

## 范围限制

仅客户端自动恢复策略，普通SQL执行功能不被禁止，用户仍可核实状态后手动执行。旧GUI的SELECT pg_sleep断连自动重试报告是修复前行为，已由上述新版复验取代。视图内部函数、数据库自定义运算符/类型转换及手动事务中的会话状态不能只靠表层AST证明安全，仍需后续验证，不宣称所有保留恢复的SELECT都满足恰好执行一次。
