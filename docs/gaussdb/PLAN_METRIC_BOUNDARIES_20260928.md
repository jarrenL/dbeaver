# 执行计划统计字段与缺失节点类型验证

## 旧文本格式路径补验（最新）

历史清单7计划解析的共享旧格式路径新增8项执行：五种无有效根节点响应（无结果行、NULL、空字符串、纯空白、仅缩进子节点）在同一计划实例中成功→失败→重试；三种NULL/空字符串/含制表符空白夹在合法根和子节点前中后，仍应保留父子关系与完整有效源码。

红测`/tmp/plan-legacy-response-red.log`为77项中72通过、5失败：五种无效响应未报告失败。修复过滤纯空白、拒绝空计划和缺失文本根节点；正常计划不因空白行丢失。新增正例测试首次使用Collection.getFirst导致测试编译错误，改为iterator().next后运行；不是产品缺陷。

专项脚本显式编译当前PostgrePlanNodeText与其余计划源码，最终`/tmp/plan-legacy-current-text-green.log`为80/80通过、0跳过、0失败。关闭结果集/语句、回滚分析事务、恢复自动提交、失败清空源码与节点、重试均有断言。不是80个新用例，也不是旧服务器实测或GUI验收；旧格式更多缩进/属性/多根节点变体、完整产品回归仍待，不将旧格式单测扩展为GaussDB全部版本已支持。

## 2026-09-28最新补验结论

计划专项现为72/72通过、0跳过、0失败（`/tmp/plan-cleanup-boundaries.log`）。包含既有61项、SQLXML释放错误3项及事务清理边界8项，不是新增72项，也不并入旧完整回归1882项。

### SQLXML生命周期

成功、坏XML、取源码失败等既有响应路径增加SQLXML.free恰好一次断言；新增正常解析/解析失败/读取失败三种释放异常。红测64项中55通过、9失败（`/tmp/plan-xml-release-red.log`），修复try-with-resources后64/64通过（`/tmp/plan-xml-release-green.log`）。释放失败显式报告，解析或读取的原始错误优先，释放错误附加为suppressed；随后仍关闭结果集、语句并清理分析事务。输入流单独所有权仍非本轮结论。

### 事务清理与不支持保存点

新增8项：手动事务rollback(savepoint)失败、releaseSavepoint失败、自动提交恢复失败，各分别覆盖计划成功和XML解析失败；另测保存点返回null和SQLFeatureNotSupportedException。验证错误身份与suppressed、无成功计划残留、不提交、不用全局回滚替代保存点、保存点回滚失败时不释放。全部组件测试通过，无需额外生产修复。

### 真库入口与本轮未验范围

新增两项参数化真库场景`realPlanAnalysisPreservesPendingUserWrites`：已提交基线行0，用户未提交行1；生产explain执行ANALYZE INSERT行2或真实错误查询，之后应仅见0/1且仍为手动事务；用户rollback后只见0，再验证自动提交分析INSERT行3无残留。JDBCSession/Statement/ResultSet适配器模拟，SQL、SQLXML与事务操作全部转发真实JDBC连接，不使用预取XML替代事务。finally仅清理本测试随机schema。此用例尚未获得通过结果。

找回本地原有隔离配置，允许通过GAUSSDB_HISTORY_DRIVER_CLASS补充其未包含的driverClass字段，密码不写入代码或文档。首次按驱动服务描述设置org.postgresql.Driver失败，检查jar实际类后修正为com.huawei.gauss200.jdbc.Driver。正确驱动下直接编译成功，但4项计划回归＋2项事务测试均在建立连接时被SocketException: Operation not permitted拒绝（`/tmp/live-plan-transaction-driver-20260928.log`）；0通过、6失败、0跳过，未执行测试DDL。Docker容器内只读gsql连接检查成功不能替代JDBC产品链路验收。

再次尝试本机辅助只读审查，因会话目录不可写及Operation not permitted初始化失败，未获得审查结论。完整构建、真实事务数据断言、Linux GUI和最新推送仍待，不以此专项结果宣称全量验收。

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

## 空响应、损坏XML和失败后重试

新增PostgrePlanResponseTest的6项：无结果行、SQLXML为null、损坏XML、缺Query、缺Plan、节点已解析后getString抛SQLException。每项先在同一个生产计划对象成功加载，再注入失败，最后重新成功加载。验证失败必须是DBCException、旧源码与节点清空、不保留半成品，结果集和语句关闭，并在自动提交场景恢复状态。

首批 `/tmp/plan-response-red.log` 为54项中49通过、5失败：空响应/缺结构不报错、null指针、损坏XML仍留旧源码。修复刷新前清空和XML必要结构校验。后半程SQLException测试首次缺少模拟执行上下文，属测试夹具错误；补齐后 `/tmp/plan-response-late-red2.log` 实际复现失败后保留已解析节点。补SQL异常清空后 `/tmp/plan-response-final-green.log` 为55/55通过、0跳过、0失败（新增6项，其他49项回归）。

当前源码PostgreExecutionPlan纳入直接编译。输入/会话/结果集/SQLXML为模拟对象，解析与状态转换为生产代码；不代表真实断连或服务器返回了这些坏数据。SQLXML.free、输入流所有权、已有手动事务隔离、回滚失败传播和GUI错误呈现仍需独立审计，不能由自动提交路径的资源断言推出已全部覆盖。

## 分析事务隔离的组件验证

调用方ExplainPlanViewer和SQLEditor均可从既有executionContext打开分析会话，不能假定这是独占的新事务。原explain无条件rollback整条连接；即使getAutoCommit失败，也在finally执行rollback；清理异常只记录，可能把回滚失败当作分析成功。

新增6项组件测试：手动事务成功/解析失败只回滚专属保存点；读取事务状态/建立保存点失败不执行计划、不回滚用户事务；自动提交下回滚失败（成功分析/解析失败两种）显式报告且不再切回自动提交。红测61项中55通过、6失败，日志 `/tmp/plan-transaction-red.log`。

修复使用PlanTransaction资源：自动提交时临时关闭自动提交，回滚成功后才恢复；已有手动事务时先建立保存点，结束只rollback(savepoint)及releaseSavepoint，不commit、不全局rollback、不改自动提交。无法建立保存点则阻止分析，不退化为全局回滚。try-with-resources保留原始解析错误，清理失败成为suppressed。

绿测 `/tmp/plan-transaction-green.log`：61/61通过、0跳过、0失败。既有真库XML解析适配器显式模拟自动提交（它不代理实际事务），真库专项脚本纳入当前PostgreExecutionPlan源码。上述事务验证全部是JDBC交互组件测试，尚未证明真实GaussDB/PG未提交业务行在分析后保留；保存点不支持版本、保存点释放失败、真实连接故障和GUI均待回归。此变更影响共享PostgreSQL计划路径，不能直接宣布交付验收完成。
