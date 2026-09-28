# SQL别名及DML目标识别回归

## 后续：自动重试策略及既有SQL安全测试联合回归

新增6项在同一SQLQuery上先批准简单SELECT重试，再替换为UPDATE、有副作用风险函数、FOR UPDATE、集合查询、写入CTE、损坏SQL；均拒绝重试，reset恢复原简单查询后重新允许。调用实际SQLQueryRecoveryPolicy，不模拟判断结果。

联合脚本另接入原45项恢复策略测试及51项GaussDB SQL安全测试；显式编译当前SQLSemanticProcessor与SQLQueryRecoveryPolicy。重试类现51项，安全类51项，加之前528项，共630/630通过、0跳过、0失败。日志`/tmp/shared-replay-safety-20260928.log`。新增仅6项，96项既有测试首次纳入此联合脚本。两类加入正式门控，验证器自测7项通过。

覆盖了参数之外的函数、锁、写入、CTE、未知语法拒绝自动重试，以及注释/字符串伪WHERE、外层WHERE、ONLY、DROP等既有场景。无新增生产修复。不是实际断线重连或写入结果未知时的端到端验证，也没有操作确认对话框。不声称已运行全部上游SQL/补全测试或验证全部数据库方言。

## 后续：安全检查及修改语句分类影响

新增6项SELECT→UPDATE/DELETE（有无WHERE）、DROP、SELECT→reset的危险/修改判断；新增2项FOR UPDATE和SELECT INTO保持修改标记。原8项集合查询加强isModifying/isMutatingStatement断言。

影响回归发现此前SetOperationList→SELECT改动的一处遗漏：isModifying在非PlainSelect且非UNKNOWN时默认true。红测`/tmp/shared-query-safety-red-20260928.log`为526项中518通过、集合查询8失败；不是新发现的独立上游缺陷。现明确集合查询返回false，原有带锁/INTO SELECT及DML修改判断保留。最终`/tmp/shared-query-safety-final-20260928.log`为528/528通过、0跳过、0失败，投影类89项。

源码调用点QueryResultsContainer.getSupportedFeatures会依据isModifying添加FEATURE_DATA_MODIFIED_ON_REFRESH；修复保持集合查询不被误标为刷新会修改数据的语句。尚未运行实际刷新确认界面，不能将模型判断等同SQL无副作用（例如查询调用有副作用的函数）。

本轮Hermes只读审查请求仍在初始化时Operation not permitted退出，无审查报告，不能记为审核通过。

## 后续：替换文本与reset的解析缓存

新增7项：已解析SELECT切换到另一张表、DELETE、UNION、空文本和损坏SQL，再reset恢复；参数填充111→reset→222→reset；JOIN切换到单表并reset时导出名称不残留。

红测`/tmp/shared-query-state-red-20260928.log`：518项中513通过、5失败，setText未使已解析状态失效。修复setText清除parsed、类型、AST、错误、原始/去引号表元数据、投影与JOIN派生名称；reset改经setText恢复原文。不清除参数列表、执行数据标识、位置、长度、结果偏移/上限；参数测试逐项断言这些信息保留，重新解析的AST反映新参数值。

参数测试初次构造SQLSyntaxManager触发缺失OSGi应用的错误，属于夹具问题；改为模拟不参与本场景操作的syntax manager，继续调用实际SQLUtils.fillQueryParameters（该共享依赖使用已有构建产物）。最终`/tmp/shared-query-state-final2-20260928.log`：520/520通过，0跳过、0失败、0中止；投影类81项。

setText调用点包括参数填充、SQL翻译及执行统计对象构造。测试证明模型生命周期，不等同编辑器键入、翻译向导、参数弹窗或JDBC执行；GUI回归仍待。SQL标题及附加执行错误信息未改变，不宣称这些字段已覆盖。

## 后续：集合查询分类

新增8项覆盖UNION、UNION ALL、INTERSECT、EXCEPT、同表两分支、中文输出别名与ORDER BY、CTE及混合集合运算。红测`/tmp/shared-set-query-red-20260928.log`中505通过、8失败，均期望SELECT而得到UNKNOWN。

生产SQLQuery增加SetOperationList分类为SELECT，不创建单表编辑元数据，不标记为plain SELECT。每项同时断言原始/去引号目标均为空、isPlainSelect为false、没有虚构星号位置。绿测`/tmp/shared-set-query-green-20260928.log`为513/513通过、0跳过、0失败，该类现74项。

影响检查：DBUtils执行准备仍以SELECT且isPlainSelect判断简单查询，原SELECT或UNKNOWN的“可能查询”判断对集合查询均成立；SQLEditor统计页焦点选择依据SELECT分类，因此修复可能改变原先集合查询被切换到统计页的行为。此处是源码检查，不宣称GUI焦点验收完成。不扩展到VALUES、括号包裹整个语句或集合结果列来源推导，也不改变JDBC元数据提供的其他编辑判断。

对应历史清单8.2的UPDATE、DELETE、子查询别名以及9.4的DML对象识别。

## 新增场景

每项使用真实SQLQuery解析入口，断言语句类型、目标schema和table；不使用模拟AST。

| SQL结构 | 预期目标 | 结果 |
| --- | --- | --- |
| UPDATE SET中包含其他表的聚合子查询 | public.accounts，而非audit.other_table | 通过 |
| UPDATE FROM关联另一张表 | public.accounts | 通过 |
| DELETE WHERE EXISTS，内外层使用同名别名a | 外层public.accounts | 通过 |
| DELETE USING关联另一张表 | public.accounts | 通过 |
| INSERT INTO SELECT读取另一张表 | 写入目标public.accounts | 通过 |
| UPDATE中文schema/table和中文引用别名 | 去引号后的中文模式、中文表 | 通过 |

## 证据

主仓基线77d8c0296b，加上述6项测试，无生产代码修改。测试仓run-shared-focused.mjs明确编译当前SQLQuery、SQLSelectItem、GaussDBDialect及GaussDBProjectionMetadataTest。该类66项包含原60项投影/CTE/JOIN/表达式测试；与此前439项同一进程执行，505/505通过，0失败、0跳过、0中止。

日志：`/tmp/shared-projection-dml-20260928.log`。原60项并非新增测试，不与历史全量回归数相加。该类加入正式全量门控，要求实际出现且全部通过。

## 边界

这里只证明客户端语句分类与目标元数据提取，不证明GaussDB目标版本支持全部语法、不执行DML、不验证权限及事务、不保证结果网格可编辑。UNION来源、补全候选、复杂作用域及GUI仍需独立验收。依赖中其他模块仍来自已有构建产物，不是完整Tycho构建。
