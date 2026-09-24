# SQL Unicode 源码定位修复

覆盖历史清单的 SQL 解析、字符编码及编辑器错误定位边界。

## 问题及修复

新增 6 项真实语义识别器测试，检查中文、单个/多个补充平面字符、块注释、CRLF 行注释、组合字符之后的 NULL 比较告警。断言起点等于 Java 原文 `indexOf`，并从原文截取范围，严格等于 `1 = NULL`。

首次回归 `run-Z87Dnj`：新增场景中 5 项失败。每个表情字符让定位偏移少算 1，例如期望 18 实际 17、期望 20 实际 18。

原因：`CharStreams` 按 Unicode 码点计数，Java String/Eclipse 文档按 UTF-16 单元计数。共享 STMSource 的字符串和 Reader 入口统一使用 UTF-16 `ANTLRInputStream`，使 token 和语法树范围与文档坐标一致。不修改原文，也不逐条给告警加偏移补丁。

此 API 在 ANTLR 中已废弃但当前依赖仍提供；代码明确说明选择它是为了保留 UTF-16 坐标契约。后续升级 ANTLR 必须保留该契约，或在所有源码范围消费者统一转换，不能仅替换回码点流。

## 回归结果

修复后 `run-b54PWn`：1,845 项执行，1,822 通过、23 跳过、0 失败、0 错误。上述 6 项全部通过。见 [脱敏结果](unicode-range-results-20260924.json)。

集中式及分布式 GaussDB 507 均以 gsql 执行只读对照：

```sql
SELECT '😀🧪' AS text_value,
       length('😀🧪') AS character_count,
       (1 = NULL) IS NULL AS unknown_result;
```

均得到 `😀🧪|2|t`。这验证服务端字符串与 SQL 语义，不代替客户端源码定位测试。

## 尚待验证

- 麒麟 GUI 验收副本还未更新此 LSM 修复，真实源码标记位置需复验。
- Reader 入口专项、补充平面字符标识符、跨 token 导航与其他语义规则的范围矩阵需继续补充。
- 该修复影响共享解析器，不能只凭 GaussDB 真库结果宣称全部方言通过。
