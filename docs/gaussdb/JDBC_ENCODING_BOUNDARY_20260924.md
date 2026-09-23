# JDBC 客户端编码边界与预编译回归

对应清单 1.1、11.1 及金融字符集场景。数据库存储编码与 JDBC 会话 client_encoding 是不同层次；不能因为数据库为 GB18030 就要求 JDBC 会话也切成 GB18030。

## 当前失败项的驱动证据

对本地测试所用 `gsjdbc200.jar` 做 javap 字节码检查，SHA-256：`d50c5c5e97b40135d834e3af762b839ec587157b0658a79ac4daeef2c84ee3c1`。

- `com.huawei.gauss200.jdbc.core.v3.SimpleParameterList.getV3Length` 将字符串经 `Utils.encodeUTF8` 编为扩展协议参数；`writeV3Value` 未缓存时也直接调用同一 UTF-8 编码函数。
- `QueryExecutorImpl` 处理 client_encoding 参数状态时，可调用 `Encoding.getDatabaseEncoding`、`PGStream.setEncoding` 更新流编码。
- 因此手动允许编码变化并 SET client_encoding=GB18030，不能保证扩展协议字符串绑定同步使用 GB18030。这与已有显式切换后的参数往返失败相符；这是对当前二进制的分析，不推断所有版本的厂商驱动相同。

没有修改或重新打包厂商 JDBC 二进制，没有自动降低所有连接的查询协议，也没有将已知失败标记为通过。

## 新增真实数据库测试

集中式/分布式 507 各执行 prepareThreshold=0/1/5，共 6 项，均明确 preferQueryMode=extended，保持连接默认 UTF8。

每项在独立随机 schema 建表，复用同一个 PreparedStatement 连续 INSERT 8 次，再复用参数查询 8 次，跨过 1/5 阈值。数据包含中文、扩展汉字𠀀、单引号/分号/反斜杠及 SQL NULL，逐值和 wasNull 断言。SHOW client_encoding 必须仍为 UTF8；独立连接按 id 核对全部 8 行、无多余行，最后清理 schema。

## 使用边界

当前推荐保持 JDBC 默认 UTF8，不在初始化 SQL 中设置非 UTF8 client_encoding。已有 simpleProtocolGb18030 测试仅覆盖显式 allowEncodingChanges=true/preferQueryMode=simple 的替代路径，不代表该协议组合适合所有性能、游标或批处理场景。

显式 GB18030 + 扩展协议的失败仍是未解决兼容限制，需要驱动修复或明确限制该配置；本轮 UTF8 回归不能替代它。GB18030 数据库存储场景有独立 opt-in 测试，未设置专用数据库环境变量的运行仍会跳过，不把历史证据合并成当次通过。GUI 驱动属性与客户金融版本也尚未由本轮覆盖。

## 本次结果

`run-CAtm7A` 新增 6 项全部通过；完整已配置回归 **1,379 项 / 1,355 通过 / 24 跳过 / 0 失败错误**，报告新鲜度已核验。[脱敏结果](test-results-20260924-encoding-thresholds.json)。随机 schema 由 finally 清理；本次回归临时 grantee 已删除。驱动文件未变更。
