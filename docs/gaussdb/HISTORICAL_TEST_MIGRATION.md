# 历史测试场景迁移记录

日期：2026-09-23。基线：DBeaver 26.1.5 GaussDB 适配版。

输入为历史用例迁移清单，不含原测试源码、完整断言与数据集。以下为按场景重新编写的 DBeaver 测试，不声称逐条等价迁移。清单中的文件数、方法数、内核 SQL 数量尚未对源仓库核实，不作为覆盖率分母。

## 第一批新增：12 个单元测试、2 个可选真库测试

| 清单来源 | 测试类 / 方法 | 断言 | 层级 |
|---|---|---|---|
| 4.2 参数调用、11.1 类型边界 | GaussDBDebugArgumentsTest.historicalNumericAndUnicodeBoundaryValuesRemainBoundData | 数字边界、中文、引号和疑似 SQL 注入文本保持绑定值，不拼入 SQL | 单元 |
| 4.2 参数调用 | noParametersProduceNoBindings | 无参数时 SQL 参数列表及绑定为空 | 单元 |
| 4.2 参数调用 | mixedNullAndValuePreservePositionAndType | NULL、字符串、数字的绑定位置及类型保持一致 | 单元 |
| 4.2 参数调用 | planIsAnImmutableSnapshotIncludingSqlNull | 参数计划不受外部列表修改影响，允许 SQL NULL | 单元 |
| 4.2 参数调用异常 | invalidModeCountsAndNullModesAreRejected | 模式数量不匹配及空模式被拒绝 | 单元 |
| 4.2 默认参数 | explicitNullCannotFollowAnOmittedDefault | 禁止省略中间默认参数后再传 NULL | 单元 |
| 3.1 包编译、9.6 批处理 | GaussDBPackageCompileBatchTest.emptyBatchDoesNotInvokeCompiler | 空批次不执行编译 | 单元 |
| 3.1 包编译 | allTargetsPreserveObjectOrderAndRequestedTarget | ALL/SPEC/BODY 目标及对象顺序传递正确 | 单元 |
| 9.6 批处理失败 | firstFailureStopsBeforeAnyLaterPackage | 首项失败后停止，保留失败对象与异常，不执行后续对象 | 单元 |
| 6.2 SQL 拆分 | GaussDBDialectTest.historicalQuotedLiteralsDoNotIntroduceStatements | 字符串中的分号、引号、注释符不拆成新语句 | 单元 |
| 6.1、8 SQL 表达式 | historicalAliasesAndNestedExpressionsKeepStatementBoundaries | CASE、JOIN、窗口函数、CTE 不破坏语句边界 | 单元 |
| 11 分布式及 DML SQL | historicalDmlAndDistributedDdlRemainIntact | 分布表、MERGE、重复键更新、EXECUTE DIRECT 保持语句完整 | 单元 |
| 4.2 OUT 参数 / 现场报错 | GaussDBHistoricalJdbcLiveTest.productionArgumentsForProcedureWithOutResolveAndReturnOutput | 使用当前生产参数生成器执行带 OUT 的过程，要求成功解析且返回预期值 | 真库、显式启用 |
| 11.1 类型映射 | numericUnicodeNullAndBigintRoundTripThroughVendorJdbc | 实际驱动写入后读取高精度 numeric、bigint 下界、中文、NULL | 真库、显式启用 |

数字绑定测试不等于数字显示测试；SQL 拆分测试不等于服务端接受这些语法，也不等于自动补全或别名语义正确。真库测试使用真实 JDBC，但不启动 DBE_PLDEBUGGER 会话，也不代表界面验收。

OUT 用例最初复现仅传输入参数的 CALL 契约，确实在真库失败。后续经独立占位试验验证，已修改为断言生产参数生成器的正确调用；原始失败证据及修复结果见本页末尾。不将捕获异常视为通过。

## 其余清单的归属和后续范围

| 清单章节 | 处理方式 | 当前状态 |
|---|---|---|
| 1 连接、驱动、执行器 | GaussDB 特有检测放扩展测试；连接关闭、commit/rollback 等通用行为放共享 JDBC 模块 | 有既有相关测试；本批未逐项补齐 |
| 2 关键字 | 按目标兼容模式和服务端版本核实保留字，不混入其他产品关键字 | 待梳理 |
| 3 对象管理 | 复用现有 Table/Schema/Procedure/Package/Partition 测试，新增缺失断言 | 本批扩充包编译；其他未全部迁移 |
| 4 调试 | 复用 DebugSession、BreakpointRouting、ThreadSnapshot、WatchLifecycle、TransactionCompletion 测试 | 本批补参数与 OUT 真库契约；历史 159 项未逐条对齐 |
| 5 静态检查 | 先确认产品有对应规则；没有规则不能仅靠添加测试宣称支持 | 未纳入本批，需单独功能范围 |
| 6、8 解析与别名 | 放实际 SQL parser / dialect 所属模块，复用生产解析器 | 本批验证拆分，不包含全部语义分析 |
| 7 执行计划 | 使用实际版本的 EXPLAIN 样本验证现有计划模型 | 待补，不根据节点名猜字段 |
| 9 表现层 | 导出、数据编辑、搜索等归属对应通用 UI 模块；真正的点击路径需 SWTBot | 未迁移 AutoIt 脚本 |
| 10 Windows | 文件权限、路径、外部程序须 Windows 原生环境验证 | 不用 Linux 或 mock 冒充通过 |
| 11 内核 SQL | 筛选客户端相关场景，按版本、模式、拓扑标记适用性 | 本批两项真库契约；不执行整个内核测试集 |
| 12 HA / 运维 | 仅客户端断线、错误报告、恢复和事务结果属于此次客户端回归 | 不执行 kill、删备份、关闭防火墙或集群升级 |
| DM_auto 平台用例 | Web 运维平台、告警、PITR、扩缩容不等价于 DBeaver 功能 | 范围外；不能映射为已有监控界面 |
| DS_auto 桌面用例 | 提取用户流程，用 DBeaver 实际控件重新实现 | 待逐步加入 SWTBot |

迁移不能只 mock 方法返回值再断言同一返回值；必须调用生产代码并断言 SQL、参数绑定、资源释放、异常传播或状态变化。fetchSize 也不能直接等同于 SQL 分页。Roach 物理备份与 gs_dump 逻辑导出不是同一功能。

## 执行方法

仓库根目录执行既有回归集（需本地依赖已准备）：

```sh
mvn -o -fae verify -f product/aggregate/pom.xml \
  -pl "$(paste -sd, tools/gaussdb-review-reactor.txt)" \
  -Dskip-checkstyle=true -Dspotless.check.skip=true
```

新真库测试默认跳过，启用前必须由操作者确认目标是允许建删对象的专用 O/Oracle 测试库。连接配置文件放仓库外，限制读取权限，不能提交密码：

```properties
url=jdbc:gaussdb://HOST:PORT/TEST_DATABASE
driverClass=com.huawei.gaussdb.jdbc.Driver
user=TEST_USER
password=TEST_PASSWORD
```

设置 `GAUSSDB_HISTORY_CONNECTION` 为配置文件绝对路径、`GAUSSDB_HISTORY_JDBC` 为 JDBC jar 路径，以及 `GAUSSDB_HISTORY_ALLOW_DDL=YES` 后运行同一回归命令。不配置连接时记为跳过；配置不完整或 SQL 不兼容时应失败。

每个真库测试创建随机 `dbv_hist_` schema，仅在成功创建后清理该 schema。不得指向生产环境。测试不修改角色、系统配置或现有 schema。连接异常时清理可能失败，应由管理员按报告核对残留测试 schema，不批量删除不明对象。

## 本轮执行记录

早期受限环境中 Docker API 不可访问，未执行新真库测试。Maven 首次运行因用户目录缓存锁写入失败而未进入测试；随后使用临时缓存副本完成构建。权限恢复后的结果见后续记录。

首次全量回归中，共享平台 RestTest 因环境禁止监听端口而报错；两项新增拆分断言误将合法保留的末尾分号视为失败，已按既有 CASE / dollar-quote 解析器契约修正，没有修改生产代码。

最终针对四个变更测试类执行：37 个测试，35 通过、2 个真库测试因未配置连接而跳过，零失败、零错误；71 模块构建成功。新增 12 个单元测试均通过。执行筛选为：

```sh
-Dtest=GaussDBDebugArgumentsTest,GaussDBPackageCompileBatchTest,GaussDBDialectTest,GaussDBHistoricalJdbcLiveTest \
-DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

这不是全量回归通过结论，也没有执行 Checkstyle/Spotless。全量结果及首次失败保留，不能把筛选后的成功替代全量结果。历史迁移仍是第一批，不声称整份清单已完成。

### 权限恢复后的真库复验与修复

本地服务器：GaussDB Kernel 507.0.0 build d791c80a，分布式 CN 入口，ORA 数据库，实际供应商 JDBC。不代表客户金融 503/505 或其他驱动版本已经通过。

1. 首轮 JDBC 真库执行：numeric/中文/NULL/bigint 往返通过；IN numeric + OUT varchar 的过程仅传 IN 时失败，服务端返回 `Function "p" with 1 parameters does not exist`。这复现了现场同类问题，不是未保存编辑器即可解释的问题。
2. 独立使用 `CALL schema.p(?::numeric,NULL::varchar)`，返回 `id=111`，验证 OUT 占位契约。
3. 增加生产 `GaussDBDebugArguments.buildProcedure`，过程调用按完整参数顺序添加类型明确的 OUT 占位；仅输入参数占用 JDBC 绑定位置。函数仍用原输入参数构建器。
4. 增加 6 个单元测试：OUT 位于首/中/尾、INOUT、纯 OUT、默认输入在 OUT 之前时拒绝错位、OUT 后尾部默认参数省略、未知参数方向拒绝。当前需默认值且后面有 OUT 的输入参数要求显式填写，不生成歧义位置调用。
5. 新真库用例改为使用生产参数构建器，同时保留独立占位契约测试；3 项真库测试通过。

最终本轮增量为 **18 个单元测试 + 3 个真库测试**。包含既有测试的 71 模块回归：694 项中 **683 通过、11 跳过、零失败/错误**；未执行 Checkstyle/Spotless。完整日志位于本机 `/tmp/gaussdb-history-out-fixed-20260923.log`，原失败日志为 `/tmp/gaussdb-history-live-20260923.log`。这次只验证到 JDBC 调用，不冒充完整调试会话、变量窗格或交付包验收；尚未重新打包发布。

### 扩展真库场景及独立测试仓库

原测试源码属于内网资料，不要求提供或上传。后续以已提供的场景摘要为依据独立编写适用于 DBeaver 的测试，记录场景、实际生产入口、断言、运行结果和不适用理由。没有原始断言，不能声称逐条等价迁移约七千条测试，也不能以参数组合数量替代覆盖率。

新增真库测试现扩充到 16 项：覆盖 IN/OUT/INOUT、OUT 首中尾、纯 OUT、SQL NULL、带引号对象名和安全文本绑定、函数调用、类型往返、独立连接观察提交/回滚、保存点、约束错误及连接复用、表元数据、视图和序列。

其中 15 项通过；1 项同名过程重载因本测试服务器拒绝创建（SQLSTATE `42723`）而明确跳过，未验证重载解析。没有为了通过测试修改服务器兼容配置。

最终执行 71 模块回归，共 **707 项：695 通过、12 跳过、零失败/错误**。本轮新增为 **18 个单元测试及 16 个真库测试**，总数包含既有测试。逐项结果见 [测试结果清单](test-results-20260923.json)。其他跳过项未因本轮执行而获得验收结论。未执行 Checkstyle/Spotless、GUI 或新安装包验收。

独立测试仓库 `gaussdb-dbeaver-tests` 管理执行脚本、场景台账和环境说明；依赖 DBeaver 内部类的 JUnit 测试仍随源码构建。执行脚本检查报告时间，拒绝旧报告，并将原始日志与连接文件排除在版本控制之外。本轮执行脚本已复跑验证得到上述结果。当前新测试及修复尚未发布到远端或安装包。

### 第二批：离线扩充与真库连接受限

继续新增 **25 个单元测试、7 个真库测试**。新增场景及实际断言：

| 来源场景 | 测试类 | 新增数 | 测试方法与结果 |
|---|---|---:|---|
| 6、8 SQL 拆分及别名 | GaussDBDialectTest | 4 | 调用实际 SQLScriptParser，验证带分号引号别名、UPDATE 子查询/DELETE USING、UNION 字符串、多段斜杠终止过程的精确语句边界；通过 |
| 3.2、3.6 表与分区元数据 | GaussDBTableTest | 5 | 从模拟目录行构造实际表模型，验证 LIST、HASH 子分区、INTERVAL、未知策略、目录列缺失；普通表不启动分区读取；通过 |
| 1 连接异常分类 | GaussDBMetadataErrorHandlerTest | 3 | 验证取消、死锁、序列化、资源不足和关闭错误不降级为空元数据；错误文本不冒充 SQLSTATE；多层包装保留状态；通过 |
| 4 变量监视 | DatabaseWatchLifecycleTest | 7 | 调用实际 watch delegate，验证无栈帧、中文变量及空格、表达式不执行、大小写、空/未知名称、诊断数组隔离、切换栈帧；每次回调恰好一次；通过 |
| 3.6 分区边界 | GaussDBTablePartitionTest | 6 | 调用实际目录数组解析及边界格式化，验证缺失值、数值指数、字符串 NULL/空串、转义、时间/中文、疑似 SQL 文本；通过 |
| 4.2 默认值及跨 schema | GaussDBHistoricalJdbcLiveTest | 2 | DEFAULT 使用服务端表达式及显式覆盖；同名过程使用限定 schema，不随 search_path 错选目标；已编译，待真库复验 |
| 1、9 事务及批处理 | GaussDBHistoricalJdbcLiveTest | 3 | 约束失败后保存点恢复并提交；批量更新计数及独立连接确认回滚；fetchSize=2 仍完整读取七行且无重复；已编译，待真库复验 |
| 3、11 类型及元数据 | GaussDBHistoricalJdbcLiveTest | 2 | bytea 逐字节与 timestamp 微秒往返；列重命名/删除后重新读取元数据；已编译，待真库复验 |

当前会话改为受限执行环境：Docker API 无权限；显式启用 JDBC 真库时，23 项在连接阶段均因 `java.net.SocketException: Operation not permitted` 报错，尚未执行场景 SQL。原始失败日志保留在本地 `gaussdb-history-next-20260923.log`，这些错误不是服务端功能失败，也不按通过处理。

随后不启用真库，仅对 `GaussDB*、Database*、Debug*、Postgre*、DBCompatibilityEnumTest` 运行离线回归：**289 项，258 通过、31 跳过、0 失败/错误**，71 模块构建成功。新增 25 项单元测试全部通过。跳过包含 23 项历史 JDBC 真库测试和其他未配置真库测试；不覆盖平台全部测试，不取代前一批全量真库结果。未运行 Checkstyle/Spotless。

逐项记录见 [第二批离线回归结果](test-results-20260923-offline-followup.json)，仅收录该次运行开始后更新的报告，避免混入前次结果。历史 707 项报告仍保留为此前运行快照。第二批未通过完整真库验收，因此尚未上传远端。

### 第二批权限恢复后复验

真库运行恢复后，不再存在网络权限阻断。首次复验暴露两处测试假设问题：

1. 分布式表首列可能是默认分布键，不能删除。元数据测试增加普通列 `removable` 用于删除，保留 `id`，并断言重命名后 `new_name` 的名称和长度、已删除列不再出现。
2. 当前供应商 JDBC 批量执行返回 `[4,0,0,0]`，不是每项 1。生产 `ExecuteBatchImpl` 对计数求和；测试改为断言总数 4、无失败计数、逐行核对 id 与中文字符串、独立连接确认未提交不可见及回滚后为空。不是仅放宽断言。

另外新增 `ExecuteBatchHistoricalTest` 的 5 项生产逻辑测试：聚合计数、零行匹配、失败传播并关闭、首次执行前取消、完成后不重复执行。无更新计数时 `DBCStatistics` 初始化值为 -1，测试遵循此实际契约。

最新全量 71 模块回归 **744 项：732 通过、12 跳过、零失败/错误**；历史 JDBC 类 **23 项：22 通过、1 重载能力跳过**。第二批新增 7 个真库场景全部通过。详见 [真库复验逐项结果](test-results-20260923-live-followup.json)。这些数字包含既有测试，不代表完整历史清单已完成；其余适用场景继续按独立测试仓库的覆盖计划实施。未运行新 GUI 验收或打包，也未上传最终版本。

### CSV 导出补充与修复

对应清单 9.1 数据导出，新增 6 项 `DataExporterCSVTest`，调用实际导出器核对完整输出：SQL NULL 与空串/文字 NULL 区分、自定义 NULL 标记转义、Windows CRLF 与字段内换行、换行替换和双引号共同出现、长 Unicode 不截断、前导零保留。

首次 CSV 回归 112 项中 1 项失败：输入 `甲"\n乙",丙` 且换行替换为 `<LF>` 时，导出器引号转义读取原始字符串，丢弃已完成的替换。修改 `DataExporterCSV.writeCellValue`，对 `preparedValue` 而不是原 `value` 进行双引号转义。原失败日志本地保留为 `gaussdb-csv-history-red-20260923.log`。

修复后 CSV 112 项全部通过；71 模块完整回归 **750 项：738 通过、12 跳过、零失败/错误**，包含前述真实 JDBC 回归。详见 [CSV 修复后逐项结果](test-results-20260923-csv-followup.json)。此验证不等于导入、Excel、文件编码或向导 UI 验收，其他场景继续补齐。共享导出器修复尚未重新打包发布。

### 执行计划真实 XML 与层次修复

对应清单 7.1/7.2，新增 3 项真库计划测试：聚合排序、连接、UNION ALL。实际执行生产 `PostgreExecutionPlan.getPlanQueryString()`，取得服务端 XML，用模拟会话适配器送入生产 `explain` 解析流程；节点不模拟，独立 DOM 遍历检查节点类型、总成本、估算行数、顺序、父引用和完整节点数量。此为真实计划内容与客户端模型联验，不代表真实 UI。

首次聚合计划发现 `PostgrePlanNodeXML` 创建子节点时传入空 parent，已修复为当前节点 `this`。增加 PostgreSQL 单元回归 3 项：兄弟顺序与父引用、三级层次、未 ANALYZE 时成本和估算行数。

分布式默认快速下推可只返回单节点计划，无法有效检验树。因此测试填充真实数据，并只在独立测试连接 `SET enable_fast_query_shipping = off`，要求 XML 至少包含两个节点。未改变全局或数据库兼容配置；不将该配置的结果推广为所有优化器模式。默认单节点计划此前可解析，但不算多层树通过。

最新完整回归 **756 项：744 通过、12 跳过、零失败/错误**。新增 6 项全部通过；历史 JDBC 类 26 项中 25 通过、1 重载能力跳过。详见 [执行计划修复后逐项结果](test-results-20260923-plan-followup.json)。向量、递归、缓冲统计及执行计划 UI 等剩余场景仍待补齐。

### 连接与执行器补充

针对清单 1.1、1.2、1.5、1.6，新增 5 项真库测试：1 秒查询超时须在睡眠正常结束前取消并返回 57014；主动 Statement.cancel 在超时兜底之前终止；关闭 Statement 同时关闭 ResultSet、不关闭连接；缺失对象返回 42P01；maxRows=2 只返回两行而实际表仍有五行。每个异常/取消场景后验证同一连接仍能完成 SELECT。定时取消只针对独立测试语句，不调用服务器进程终止操作。

另新增 1 项实际供应商驱动 URL 契约，以及 4 项 GaussDBDataSourceProvider 地址格式回归：空主机项、无默认端口、IPv6 端口不重复、主机字段拒绝 URL 查询/片段/用户信息/路径。

最新 71 模块回归 **766 项：754 通过、12 跳过、零失败/错误**。新增 10 项全部通过；历史 JDBC 类 32 项中 31 通过、1 重载能力跳过。详见 [连接与执行器逐项结果](test-results-20260923-connection-followup.json)。真实 JDBC 行为不等同于连接向导、SSL、安全存储或界面取消按钮验收，后者仍待补齐。

### 对象管理真实 JDBC 补充

新增 7 项，6 项通过：复合主键列顺序/名称及重复拒绝；索引元数据及删除后刷新；CHECK 约束 SQLSTATE 23514 和已有数据不变；视图重命名、元数据及结果；依赖对象阻止普通删除（2BP01）、CASCADE 清理依赖但保留无关表；序列初值和递增。

RESTART 在本 507 分布式服务端返回 0A000，提示 ALTER SEQUENCE 仅支持 MAXVALUE/OWNER；独立能力测试跳过，不算通过。保留首次失败，并将递增的已通过行为与重启的未验证行为分开。共享 `PostgreSequenceManager` 存在生成 RESTART 的路径，GaussDB 客户端能力门控仍待核实，尚未宣称修复或完成。

最新回归 **773 项：760 通过、13 跳过、零失败/错误**；历史 JDBC 类 39 项中 37 通过、2 能力跳过。详见 [对象管理逐项结果](test-results-20260923-objects-followup.json)。对象编辑器、批量删除 UI 和其他部署形态仍需单独验收。

### 序列 DDL 导出边界修正

进一步确认 GaussDB 使用共享 PostgreSQL 序列链路，RESTART 能力门控尚待处理。另发现 DDL 生成只保留正增量/非负起点，负边界被默认值替代；这会改变降序序列重建语义。修改 `PostgreSequence.getSequenceBody`，非零增量均输出，已加载元数据的实际起点和边界不因正负被丢弃，未加载值继续沿用原默认逻辑。

新增 `PostgreSequenceDdlTest` 4 项，覆盖降序增量、负边界/起点、零最大值、升序缓存和循环选项。首次测试编译的监视器 API 调用有误，修正后执行成功；该首次失败不是缺陷红测证据。

完整回归 **777 项：764 通过、13 跳过、零失败/错误**，新增 4 项全部通过。详见 [序列 DDL 逐项结果](test-results-20260923-sequence-ddl-followup.json)。此为生产 DDL 生成验证，不等于降序序列跨库重建或 GUI 验收；RESTART 仍单独列为未闭环。

### 类型及查询结果补充

新增 7 项真实 JDBC 场景全部通过：整数数组元素和 NULL 顺序、JSON 中文/引号/嵌套值、SQLXML 转义文本、布尔 false 与 NULL 的 wasNull 区别、闰日和 TIME 微秒、窗口排名和分组、聚合空值计数及十进制精度。

初始 ISO DATE 字面量受当前 ORA 日期格式影响被拒绝，测试改用明确格式 `to_date(...,'YYYY-MM-DD')` 验证日期值，不改变全局会话配置，不声称原 ISO 语法已经通过。

完整回归 **784 项：771 通过、13 跳过、零失败/错误**；历史 JDBC 类 46 项中 44 通过、2 能力跳过。详见 [类型及结果逐项记录](test-results-20260923-types-followup.json)。数据网格、JSON/XML 编辑器、GB18030 和时区场景仍需独立验收。

### DML、查询和函数结果补充

新增 6 项真实 507 ORA 分布式 JDBC 测试：MERGE 匹配更新/未匹配插入及回滚、重复键更新与无关行保留、关联 EXISTS/NOT EXISTS 与 NULL 子查询、LEFT JOIN/GROUP BY/HAVING/引号别名、中文字符串和字面量引号、精确数值函数。每项使用独立随机 schema，查询有超时且断言完整结果，结束只清理本项创建对象。

完整回归 **790 项：777 通过、13 跳过、零失败/错误**；历史 JDBC 类 52 项中 50 通过、2 能力跳过。详见 [SQL 场景逐项结果](test-results-20260923-sql-followup.json)。这是实际 SQL 结果验证，不等同于 SQL 编辑器语义补全或所有数据库模式通过。

另在麒麟 V10 x86_64 隔离 GUI 副本完成离线厂商 JDBC 配置、真实连接、对象刷新、调试参数必填校验、无角色拒绝启动，以及授权后的 IN/OUT 过程暂停、源码定位、F8 和 F9。提交前独立连接查不到未提交行；提交后为 `111/result-112` 一行；第二次调试回滚后仍是一行。临时测试角色授权已撤销。该 GUI 副本不是新的交付包，完整 GUI 和平台清单尚未验收完毕。

### 方言分类和转义补充

新增 5 项方言 API 测试，覆盖实际 configureDialect 关键字/函数/类型分类、类型集合隔离、标识符及字符串转义往返。首次 YEAR 分类断言失败；经源码核实，共享方言故意保留关键字优先，测试改为同时检查关键字分类和类型枚举，没有修改生产行为。

复跑 **795 项：782 通过、13 跳过、零失败/错误**，方言类 24 项全部通过。详见 [方言逐项结果](test-results-20260923-dialect-followup.json)。不据此推导编辑器高亮、静态检查或所有兼容模式通过。
