# CLOB 首次读取取消边界

日期：2026-09-28。历史场景 1.6 取消/异常、11.1 CLOB。

JDBCContentCLOBReadTest 新增四项：内存分支开始前取消、文件分支开始前取消、substring 返回时取消、降级 Reader 关闭时取消。已有文件复制中取消不能替代这些边界。

红测四项全部失败：内存分支忽略取消；文件分支虽然最终拒绝，但仍访问了原 Clob。修复在首次未缓存读取前检查取消，小内容通过局部存储读取后再检查取消，取消时释放局部缓存而不发布，不 free 原 Clob。

每项验证原 Clob 未释放；开始前取消不访问 length/subString/characterStream；降级 Reader 已关闭，临时目录无遗留；同一对象解除取消并改为新内容后重试得到新内容，成功才 free，release 后目录为空。JDBC/平台/监视器为替身，文件分支使用真实 JUnit 临时目录。

## 执行证据

- `/tmp/clob-cancel-boundary-red-20260928.log`：772 项中 768 通过、4 失败。
- `/tmp/clob-cancel-boundary-green-20260928.log`：772/772，0 跳过，退出 0。
- `/tmp/clob-cancel-boundary-osgi-20260928.log`：平台 OSGi 20 类、340/340，0 失败/错误/跳过，退出 0，逐类门控通过。
- 结果校验器 7/7。编译输出 `/tmp/shared-focused-Qdu1Aw`。

批次重叠，不累计新增场景。取消检查为合作式，不保证强制中断 JDBC 阻塞，也未覆盖所有检查后竞态。已缓存内容读取、GUI 提示、真库、完整构建与安装包不是本轮结论范围。
