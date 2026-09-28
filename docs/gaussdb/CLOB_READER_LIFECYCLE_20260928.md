# CLOB 内存降级读取回归

日期：2026-09-28。历史场景映射：1.6 资源与异常、11.1 CLOB 类型。

## 已确认问题

小 CLOB 首选 getSubString，驱动不支持时降级至 getCharacterStream。生产代码没有显式关闭此 Reader，并且在降级读取失败时错误地包装了第一次 substring 异常，而不是实际打开/读取流的异常。

修复采用自动关闭 Reader、关闭成功后才发布缓存，异常包装改用降级路径的实际原因。打开、读取、关闭失败时保留 CLOB 供同一对象重试；读/关双重失败保留 suppressed，成功后再 free。

## 新增六项

1. substring 正常中文扩展汉字读取、不打开 Reader、缓存复用及只 free 一次。
2. substring 不支持后降级读取完整文本，关闭 Reader 后 free。
3. 读取失败，实际 I/O 原因保留、Reader 关闭、同对象重试成功。
4. 关闭失败，不发布缓存或 free，重试成功。
5. 读取及关闭双重失败，实际原因及 suppressed 保留，重试成功。
6. 获取 Reader 失败，保留实际 SQL 异常，修正后重试成功。

测试使用正式 JDBCContentCLOB、StringContentStorage 与模拟 JDBC Clob。平台偏好配置为内存阈值 1024；测试临时替换 workbench 并在 AfterEach 恢复。非真实数据库连接。

## 结果

初始成功路径的关闭顺序断言可能被产品 free 的 Throwable 保护吞掉，增加方法返回后的明确 closed 断言；最终红测 6 项中 5 项失败，正常 substring 通过。日志 `/tmp/clob-reader-red-final-20260928.log`。修复后获取流失败用例的 Mockito 重设触发旧异常，改用 doReturn，此夹具错误不算产品问题。

- 共享组件 752/752，0 跳过，退出 0：`/tmp/clob-reader-final-20260928.log`。
- 平台 OSGi 18 类 320/320，0 失败/错误/跳过，退出 0，逐类门控通过：`/tmp/clob-reader-osgi-20260928.log`。
- 结果门控新增 JDBCContentCLOBReadTest，校验器 7/7。

编译输出 `/tmp/shared-focused-j9jAWI`。批次重叠，不累计新增数。本轮仅闭环小 CLOB 内存分支；大对象临时文件、真实厂商故障、连接事务警告、GUI、完整构建和安装包尚未由此验证。
