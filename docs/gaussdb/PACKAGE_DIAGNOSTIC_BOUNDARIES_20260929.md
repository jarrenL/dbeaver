# 包编译诊断字段与异常行号

## 需求与新增8项

对应“编译包、定位编译错误”和历史清单的编译错误/元数据异常。

- 四种诊断列读取失败：type、src、definition、line。使用真实readCompilationDiagnostics入口，JDBCResultSet在对应getter抛SQLException；要求DBException保留原始原因，不能伪造源代码诊断，结果集和语句均关闭。核对包OID42、schema OID99的参数绑定。
- 四种错误文本定位：超出int范围的行号2147483648、行号0、无行号、正常行号23。无效位置回退第1行；正常位置保留23；完整原始错误文本及明确的BODY来源保持不变。

## 复现与修复

首次两次运行中的字段读取测试因替身未完整提供session/dataSource/container而在JDBC日志分支产生空指针。补齐后重新红测，明确排除这些测试准备错误。

有效红测：17项中5项断言失败、1项NumberFormatException。四列的读取异常被safeGet吞掉，调用者未收到诊断读取失败；行号0被原样保留，过大行号导致解析崩溃。日志：`/tmp/package-diagnostic-boundary-red3-20260929.log`。

修改：明确查询出来的诊断字段使用JDBC getter，让SQL异常进入已有“编译执行了，但诊断不可读取”的异常路径；SQL NULL仍按原逻辑处理，未把它当成getter异常。直接错误文本的行号解析增加边界保护，不能定位时保留完整诊断并回退第1行。

最终回归日志：`/tmp/package-diagnostic-boundary-green-20260929.log`。逐项结果见[测试证据](evidence/PACKAGE_DIAGNOSTIC_BOUNDARIES_REACTOR_20260929.json)。Compiler测试类加入必跑门禁。

最终Compiler类**17/17通过、零跳过**。73模块中五个测试模块共**2632项：2458通过、173跳过、0失败、1错误**。唯一错误是RestTest网络操作权限拒绝，独立门禁仍拒绝整体通过；门禁自测7/7通过。诊断构建允许失败后继续收集后续模块结果，但不把跳过或诊断构建SUCCESS算作验收通过。Docker访问被拒，没有新增真库/GUI结论。

## 未替代的验收

本轮使用模拟JDBC字段错误，不代表集中式ORA真库ALTER PACKAGE/GS_ERRORS验证通过。参数绑定检查也不等于跨schema同名包的数据库端到端验收。第1行是无法识别位置时的回退，不是准确错误位置；界面是否展示回退语义仍需GUI检查。
