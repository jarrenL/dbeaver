# 包编译入口：执行后资源关闭失败

## 问题与修复

公共compile入口的SQLException处理原先没有区分执行阶段。ALTER PACKAGE已经正常返回、诊断查询无错误且对象状态已经刷新后，若PreparedStatement.close抛出42601，程序仍进入源码错误处理：再次打开元数据会话，可能将关闭异常作为源码第1行诊断返回。

新增测试通过真实DBUtils上下文解析与模拟JDBC对象复现该路径：42601场景修复前未抛出预期DBException，断言失败；08006对照场景原先即可传播。

生产实现现在记录execute正常返回的阶段。此后的SQLException以资源收尾失败上报，保留原始cause，不再次读取目录、不伪造源码诊断。execute自身抛错的既有处理不变，不自动重试DDL。

## 测试步骤与结果

两项参数化场景分别在语句close注入42601和08006：

1. 从包、schema、database解析执行上下文，创建模拟UTIL会话。
2. ALTER执行正常返回，诊断查询返回空结果，状态刷新完成。
3. close抛错；断言DBException保留同一个SQLException，编译错误列表为空。
4. 核对ALTER、诊断查询、上下文openSession、状态刷新及session.close各调用一次。

修复后2/2通过，GaussDBPackageCompilerTest合计23/23，零跳过。本测试未连接服务器，不证明真实驱动会在close抛出42601，也不证明实际数据库提交结果；其目的在于防止按异常码错误归类执行阶段。

## 回归范围

73模块诊断回归，五个测试模块合计2662项：2488通过、173跳过、0失败、1错误（RestTest网络权限拒绝）。独立门禁拒绝整体通过；门禁自身7/7通过。构建为继续收集模式，SUCCESS不是整体验收通过。

红测日志：`/tmp/package-cleanup-red-20260929.log`；修复后日志：`/tmp/package-cleanup-green-20260929.log`；[脱敏逐项证据](evidence/PACKAGE_CLEANUP_REACTOR_20260929.json)。未追加真库、GUI及安装包验收，未推送远程仓库。
