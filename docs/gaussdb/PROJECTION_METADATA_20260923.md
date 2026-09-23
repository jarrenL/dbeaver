# SQL投影、别名及来源表测试

## CTE及派生来源补充

新增9项：3个CTE（直接名、别名、中文引用名）、2个派生查询（简单子查询、UNION ALL子查询）不伪造基础表来源；4个正向场景验证无关CTE、内外同名别名、ORDER BY、IN子查询不遮蔽外层public.accounts。

修复前run-bX1fYl五个虚拟来源测试失败，分别返回q/中文等虚拟名称。修复后SQLQuery不为当前作用域CTE建立直接单表来源，SQLSelectItem未解析到基础表的派生别名返回未知来源，而非制造同名表；真实基础表路径保留。当前CTE名称检查采用解析标识符匹配，大小写折叠及引号组合仍需继续扩充，不能称作用域已全部支持。

run-ah8V9l完整1097项：1073通过、24跳过、0失败/错误，本类34项通过。见`test-results-20260923-virtual-relations.json`。临时回归角色已删除。未把未知来源等同整个查询不可编辑：驱动元数据及结果集界面仍有后续解析路径，需要另做端到端验证；GUI旧模型未更新。

对应历史清单6.1、8.1、8.2中的一部分解析场景。调用生产SQLQuery/SQLSelectItem，而不是只断言SQL拆分成功。

新增`GaussDBProjectionMetadataTest`共25项：

| 类别 | 数量 | 实际断言 |
|---|---:|---|
| 表达式别名 | 12 | 算术、聚合、CASE、CAST、窗口、子查询、字符串、函数等表达式保留result_value；非简单列且不伪造可写表来源 |
| JOIN别名来源 | 5 | JOIN/LEFT/RIGHT/FULL/INNER，a.id和b.id分别解析到public.accounts和audit.other_table，原始列名id保留用于来源映射 |
| 中文引用标识符 | 1 | 带引号中文表别名定位到原始schema/表，而非将别名当表名 |
| 真正通配列 | 2 | SELECT中第二项*或a.*，通配索引为1 |
| 非通配星号 | 5 | 乘法、字符串、count(*)、列别名、表达式别名中的星号不是通配展开 |

## 红绿结果及修复

旧代码run-PbZDCT中4个非通配场景失败，根因为getSelectItemAsteriskIndex使用名称contains("*")。改为SQLSelectItem根据实际AST表达式是否AllColumns/AllTableColumns判断，再由SQLQuery使用该结果。执行SQL文本未修改。

修复后run-YXma8k全部25项通过；完整1088项，1064通过、24跳过、0失败/错误。脱敏报告`test-results-20260923-projection-metadata.json`。临时回归授权角色已删除。

## 范围限制

SQLSelectItem的简单列getName刻意保留来源列名，不等同JDBC结果集显示标签；本次不据此声明显示别名错误。星号索引被DBExecUtils用于列来源映射，本次测试验证该索引及解析元数据，并未端到端验证结果集可编辑性/更新SQL。当前Linux客户端仍使用旧模型1529，尚未安装本修复。

UNION分支、CTE/派生表、相关子查询的别名作用域、DML别名、GROUP BY/HAVING/ORDER BY引用、补全候选及结果集编辑链路仍需继续覆盖，不将本批25项当作历史43项别名场景全部完成。
