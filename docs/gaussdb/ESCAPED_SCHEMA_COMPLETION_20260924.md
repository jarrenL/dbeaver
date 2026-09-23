# 转义 schema 名称补全验证

映射历史清单1.4、6、8、9.5。前一轮已验证SQLUtils拆分及真库目录；本轮继续检查传统补全入口，复现出额外遗漏，不能由前一轮通过推导本轮已通过。

## 复现与修复

目录包含 `Database1` → `a".schema` → `Table1`，对 `SELECT * FROM Database1."a"".schema".Tab` 触发补全，预期Table1候选，实际为0。

存在两层原因：`SQLIdentifierDetector.removeQuotes` 只去掉外围引号、未还原双写结束引号；`SQLWordPartDetector` 向前扫描时又把名称内部双写引号当成起始边界。分别修复后候选恢复。

去引号仅作用于带起始引号的组件，原始名称末尾含引号时保持原样；未闭合组件末尾双写引号仍表示名称内容。测试额外覆盖双引号、反引号、方括号和开始/结束长度不同的引用符，防止原先使用起始引号长度删除结束引号的问题。

## 测试与范围

`SQLCompletionAnalyzerTest.testEscapedSchemaNameCompletion` 覆盖引号紧邻点号、位于名称中间、位于名称末尾三种schema，断言候选及实际offset/length应用后的完整SQL：原schema名称保持不变，仅补上`Table1 t`，保留`WHERE 1 = 1`。

`testIdentifierDecoderPreservesRawAndIncompleteEscapes` 验证底层组件解码。首轮修复回归run-n26f44为1528项/1505通过/23跳过/0失败；扩充位置组合后的结果另行记录。

本轮是生产补全入口的模型级测试，带SQL分区器但未操作GUI弹窗；真实目录搜索验证见ESCAPED_IDENTIFIER_VALIDATION_20260924.md，不能把两者合称GUI端到端验收。未覆盖项仍按台账保留。

最终回归run-8ZOpZV：1530项、1507通过、23跳过、0失败/错误，新鲜度校验通过；上述三个位置全部执行通过。报告为test-results-escaped-schema-completion-20260924.json。本轮开启两种部署的历史JDBC测试，未开启额外native/review/GB18030选项；Checkstyle/Spotless被跳过，不计为通过。临时授权角色已清理。
