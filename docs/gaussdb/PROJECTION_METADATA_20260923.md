# SQL投影、别名及来源表测试

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
