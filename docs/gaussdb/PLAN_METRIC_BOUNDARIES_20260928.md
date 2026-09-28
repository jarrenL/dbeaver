# 执行计划统计字段与缺失节点类型验证

对应历史清单7.1–7.2，使用GaussDB复用的PostgrePlanNodeXML/PostgrePlanNodeBase生产解析器。输入为独立构造的XML，不冒充服务器实测计划。

## 新增场景

1. 父节点与子节点各自保留Shared-Hit-Blocks、Shared-Read-Blocks、Local-Read-Blocks等BUFFERS属性，不串用父统计；0值、大于32位的计数4294967296和小数I-O-Read-Time原文可通过生产属性接口读取，缺失统计返回null。
2. Actual-Rows=0优先于Plan-Rows=500，Actual-Loops=0和耗时0保留；缺失cost和buffer字段不伪造为0。
3. 缺失Node-Type和空Node-Type两种输入应使用DEFAULT节点类别，不崩溃。
4. 默认桌面区域设为tr-TR时Index Scan和Nested Loop分类稳定，测试finally恢复原区域。

新增5项执行，加上原有父子结构、并行前缀和估算字段4项，共9项。

## 发现与修复

红测 `/tmp/plan-metrics-red.log` 为8项中7通过、1失败：缺失Node-Type在getNodeKind调用toLowerCase时空指针。修改为对空值使用空字符串、使用Locale.ROOT转换。区域设置测试在修复后加入，未将其标为独立红测复现。

绿测 `/tmp/plan-metrics-green.log`：9/9通过、0跳过、0失败。测试仓脚本 `scripts/run-plan-focused.mjs` 直接编译当前两个生产类和测试，使用已有只读依赖；不是完整Tycho构建，不与历史1882项相加。

## 边界

只证明标量统计属性和指定节点结构解析，不证明服务器实际生成BUFFERS、多DN统计聚合、嵌套Worker统计结构或GUI图表展示。缺失节点类型输入不会崩溃不代表残缺计划语义完整。共享PostgreSQL解析器受影响，完整PG/GaussDB和保存计划回归仍需执行。

## 节点类别矩阵与保存计划回归

新增17项XML节点类别输入：Hash/Merge Join、Nested Loop、Hash、HashAggregate/Aggregate、Seq/Parallel Seq/Foreign/Bitmap Heap Scan、Index/Index Only Scan、Insert/ModifyTable、Sort、Function Scan及未知节点。每项同时断言原始Node-Type标签不被分类逻辑重写。

红测 `/tmp/plan-categories-red.log`：26项中19通过、7失败。Hash Join和HashAggregate被较早的hash子串匹配误归为HASH；普通/并行顺序扫描、外部扫描、位图堆扫描未分类；Insert因允许列表拼写inset而落入DEFAULT。修复优先匹配join/loop/aggregate，补明确扫描词及foreign/insert拼写，移除重复merge条目。不将一次类型矩阵的7个失败统计为7个独立根因。

修复后XML专项26/26通过。脚本随后加入已有PostgreSavedPlanTest并编译当前PostgreQueryPlaner/PostgrePlanNodeExternal，联合 `/tmp/plan-categories-saved-regression.log`：49/49通过、0跳过、0失败（26项XML＋23项已有保存计划测试），不是新增49项。保存/恢复、层级和格式校验的既有断言继续通过。

类别矩阵使用人工XML，不声称服务器各版本都产生这些名称，也不代替实际GUI图标验收；PG/GaussDB完整产品回归仍待。后续保存计划专项已补，不再把“未运行任何保存计划回归”作为当前结论。

## 真库专项重跑入口与环境结果

独立测试仓新增 `scripts/run-live-plan-focused.mjs`，直接编译当前节点类、计划保存类和既有GaussDBHistoricalJdbcLiveTest，选择4个真实数据库方法：递归CTE、窗口排序、索引扫描、ANALYZE实际行数/耗时/循环。复用既有随机schema创建与finally精确清理，不新增固定业务对象。

2026-09-28首次直接编译通过，执行4项均因原临时连接配置文件不存在，在读取配置阶段失败；未打开数据库连接、未创建schema，日志 `/tmp/live-plan-distributed-20260928.log`。不算产品解析失败，更不算真库通过。

入口随后增加前置检查：必须显式设置GAUSSDB_HISTORY_ALLOW_DDL=YES，GAUSSDB_HISTORY_CONNECTION和GAUSSDB_HISTORY_JDBC必须指向实际文件；缺失则退出2，提示缺哪个配置键，不输出凭据、不启动测试。缺失配置路径复验得到退出2，Node语法检查通过。该脚本完整成功运行仍待恢复现有隔离测试连接配置后验证，不能以入口实现替代真库结果。
