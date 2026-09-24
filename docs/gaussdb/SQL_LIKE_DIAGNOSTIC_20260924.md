# LIKE 前导通配符静态检查

对应历史清单5.2 `LikeShouldNotStartWithWildCardChars`。规则接入现有共享语义识别器与编辑器WARNING通道，英文/简体中文提示匹配模式可能影响前缀索引效率，建议检查执行计划；不认定SQL非法、不改写SQL，也不宣称所有索引一定失效。

## 判定范围

- 仅对语法树likePredicate中的普通单引号字符串字面量作确定判断，支持LIKE、ILIKE及NOT LIKE。
- 模式首字符是未转义的 `%` 或 `_` 时报告，位置为pattern节点。
- 显式ESCAPE为空表示未指定转义字符；为一个Unicode码点时识别该字符。若首字符本身就是ESCAPE字符，不按通配符报告。例如 `LIKE '%%tail' ESCAPE '%'` 中第一个百分号负责转义第二个。
- 双单引号按SQL字面量规则读取。注释、SQL文本字符串、尾部通配符、普通前缀和函数/列形式的动态模式不误报。
- 动态ESCAPE、非法多字符ESCAPE、复合/函数模式及扩展字符串字面量尚不做常量求值，不把未告警解释为查询安全或模式有效。

## 验证场景

`SQLQueryQualityDiagnosticTest` 新增26组实际解析/告警测试：12个正例含百分号、下划线、中文ILIKE、NOT LIKE、注释、内嵌单引号、空/自定义ESCAPE、无FROM和嵌套EXISTS；14个反例含尾部通配符、空串、被转义的百分号/下划线、动态模式及ESCAPE、拼接、函数、字符串和注释。沿用WARNING级别及源码区间断言。

集中式、分布式507分别只读执行：

```sql
SELECT 'abc' LIKE '%c',
       '%tail' LIKE '%%tail' ESCAPE '%',
       '_tail' LIKE '__tail' ESCAPE '_',
       'abc' ILIKE '%C';
```

两者均返回 `t|t|t|t`，证明上述转义与大小写不敏感语义。不测试索引计划或性能，不修改数据或服务端配置。

## 未完成边界

最终完整回归 `run-8MTZO9`：1768项、1745通过、23跳过、0失败/错误；新增26个执行全部通过。脱敏结果见 `sql-like-diagnostic-results-20260924.json`，不将跳过项或服务端只读结果算作GUI通过。

GUI告警出现/清除、提示语言和定位仍需在更新插件后验证；动态模式的常量求值、E/N/dollar-quoted字符串及更多方言转义另验。该项不是历史251条静态用例全部完成证明。
