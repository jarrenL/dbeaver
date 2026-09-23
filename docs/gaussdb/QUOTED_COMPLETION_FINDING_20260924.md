# 引号名称补全复现记录（未修复）

对应历史清单8（别名/标识符解析）、9.5（名称搜索/自动补全）。本轮实际取消禁用并运行上游`SQLCompletionAnalyzerTest.testQuotedNamesCompletion`，不是仅阅读禁用标记。

首个输入`SELECT * FROM "Dat|"`，测试模型含Database1/Schema1/Table1。预期1个Database1补全，实际0个，断言失败；其后schema/table/column三个输入因首个断言失败未执行，不能声称它们都失败或都通过。

执行记录为run-lNnzwJ，Surefire失败位置SQLCompletionAnalyzerTest.java:399。该轮不是绿色回归，未导出为通过报告。

生产入口为`SQLCompletionAnalyzer`；它区分quoted partition和普通identifier路径，候选生成及替换范围还需进一步定位。相邻已启用的列补全测试不能代替在引号内部输入前缀的行为。当前复现采用模型补全测试，非麒麟GUI截图或GaussDB实时目录。

本轮保留原禁用状态并补充禁用原因，以免把未修复问题混入绿色回归；这不是修复，也不是验收通过。后续需要修复生产代码，重新启用此测试，并核对补全文本、替换偏移、引号与别名不重复。
