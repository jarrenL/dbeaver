# 引号名称补全复现与测试环境校正

更正：下文最初的0候选失败发生在缺少SQL分区器的测试文档中，不能单凭它判定真实客户端存在同样缺陷。后续发现真实编辑器会安装SQLPartitionScanner/FastPartitioner，测试构造器原先没有。补齐相同分区器后，数据库和schema候选恢复；表候选按当前别名配置返回Table1 t，旧测试期待Table1。已通过完整替换文本而非仅数量验证其正确性。

对应历史清单8（别名/标识符解析）、9.5（名称搜索/自动补全）。本轮实际取消禁用并运行上游`SQLCompletionAnalyzerTest.testQuotedNamesCompletion`，不是仅阅读禁用标记。

首个输入`SELECT * FROM "Dat|"`，测试模型含Database1/Schema1/Table1。预期1个Database1补全，实际0个，断言失败；其后schema/table/column三个输入因首个断言失败未执行，不能声称它们都失败或都通过。

执行记录为run-lNnzwJ，Surefire失败位置SQLCompletionAnalyzerTest.java:399。该轮不是绿色回归，未导出为通过报告。

生产入口为`SQLCompletionAnalyzer`；它区分quoted partition和普通identifier路径，候选生成及替换范围还需进一步定位。相邻已启用的列补全测试不能代替在引号内部输入前缀的行为。当前复现采用模型补全测试，非麒麟GUI截图或GaussDB实时目录。

上一轮曾恢复禁用并记录待查。本轮校正测试环境和过时别名预期后移除禁用：四个输入全部执行通过，列返回Col1/Col2/Col3；表补全按真实offset/length拼接得到`SELECT * FROM "Database1"."Schema1".Table1 t`，没有残留引号或把别名包含在名称内。

run-bfD8g3确认分区器修正后仅旧Table1预期失败；run-nzVeX2回归1517项/1494通过/23跳过/0失败。此轮未启用前轮的额外review/native/GB18030环境变量，不能与上一轮16跳过直接相减。结果见`test-results-quoted-completion-20260924.json`。

无生产代码修改；修复的是测试构造器，并重新启用既有测试。尚未覆盖实际GUI弹窗、所有方言转义和带点的引号标识符替换；不宣称全部自动补全验收完成。
