# JDBC XML Reader 生命周期回归

日期：2026-09-28。历史场景：1.6 资源关闭及异常、11.1 XML 内容。

## 缺陷与修复

JDBCContentXML.getContents 将 JDBC Reader 交给不负责关闭它的 StringContentStorage，未显式关闭 Reader。读取失败时 XML 保留供重试，但 Reader 也未关闭；关闭错误完全无法被观察。

改为 try-with-resources，并将缓存赋值移至 Reader 成功关闭之后。读取/关闭失败均保留异常原因、不发布缓存、不提前 free SQLXML；读/关双重失败保留 suppressed。成功后关闭 Reader 再 free，后续读取复用缓存。

## 新增四项

1. 中文扩展汉字 XML 成功读取、Reader 在 free 前关闭、缓存复用及重复 release 不重复 free。
2. 读取失败，Reader 关闭、异常原因保留、同一对象重新取流后成功。
3. 关闭失败，不缓存空内容、不 free，下一次读取重试成功。
4. 读取与关闭同时失败，保留读取原因及关闭 suppressed，重试成功。

SQLXML 为 JDBC 替身，生产 JDBCContentXML 与 StringContentStorage 均显式重新编译。首次测试有接口调用编译错误；修正后成功路径仍受 StringReader spy 的 JDK 内部锁初始化影响，该项不是产品缺陷。三个错误路径已复现产品问题。改用真实可跟踪 Reader 后四项通过。保留日志 xml-reader-red、xml-reader-red-valid、xml-reader-green 与最终日志，不将夹具错误计入产品缺陷数。

## 验证结果

- 共享组件 746/746，0 跳过，退出 0：`/tmp/xml-reader-final-20260928.log`。
- 平台 OSGi 17 类 314/314，0 失败/错误/跳过，退出 0，逐类门控通过：`/tmp/xml-reader-osgi-20260928.log`。
- 必需测试类门控已增加 JDBCContentXMLReadTest，校验器 7/7。

两批测试重叠，不累计新增数。正式类位于平台测试模块，编译输出 `/tmp/shared-focused-3h6XyP`。

没有真实驱动故障、数据库连接、GUI 或整包验证；CLOB 降级分支的类似疑点仍待单独验证。本轮仅闭环 XML Reader 组件路径。
