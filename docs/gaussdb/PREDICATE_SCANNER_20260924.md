# SQL 片段扫描器列位置回归

最终run-ia05QF：1226项、1202通过、24跳过、零失败/错误；新增五项通过。[脱敏报告](test-results-20260924-predicate-scanner.json)。完整日志中StringScanner.getColumn异常栈行从首轮435处降为0处，此计数仅针对该路径，不代表全部日志无异常。临时权限测试角色已清理。

历史清单6.1/6.2涉及字符串与词法边界。检查此前完整回归日志发现：SQLTokenPredicateFactory内部StringScanner的getColumn固定抛出UnsupportedOperationException，PostgreSQL转义字符串规则需要该接口，异常被分类循环捕获并打印，绿色汇总不能证明该路径正常。

新增GaussDBPredicateScannerTest五项：CR、LF、CRLF列位置及unread；实际转义字符串规则识别带反斜杠文本；标识符后缀中的E不被误识别为转义字符串起点，位置不变。

首轮run-RvrQvn的三项列位置测试复现生产异常。另两项因PostgreSQL实现包未导出而出现NoClassDefFoundError，是测试加载问题，改通过所属bundle加载，不扩展生产导出。

生产修复从当前位置向前定位最近换行，返回列偏移，边界位置限制在字符串范围内。仅修改共享谓词分类扫描器的getColumn，不改变read/unread/EOF协议；不是整个字符串规则、完整脚本拆分或编辑器高亮验收。
