# 日期时间显示的时区与夏令时边界

对应历史清单6.3时间工具、11.1日期类型显示及11.2客户端日期格式。扩充现有DateTimeDataFormatterLocaleTest，直接使用生产DateTimeDataFormatter；不重复计算既有数据库日期函数用例。

## 新增10项执行

- JDBC java.sql.Date和java.sql.Time分别在UTC、Asia/Shanghai、America/New_York配置下显示：DATE保持2024-02-29，TIME保持23:59:58。两种类型没有独立时间点语义，不应因显示时区转换导致日期或时分秒移动。
- 纽约2024春季跳时前后两个时刻及秋季重复小时两个时刻，共4项。每项同时测试Timestamp、UTC OffsetDateTime、上海ZonedDateTime显示为正确纽约时间、偏移及6位微秒；文本解析回OffsetDateTime后与原Instant一致。秋季相同01:30必须分别携带-04:00和-05:00，不能合并。

## 缺陷与结果

最初组合DATE/TIME测试3项首先在DATE抛异常；拆分为六项后，`/tmp/datetime-date-time-red.log`共36项，30通过、6失败，均为UnsupportedOperationException。原formatValue在有显式时区时对所有java.util.Date调用toInstant，而JDBC DATE/TIME不支持该操作。

修复在显式时区路径先识别JDBC DATE/TIME，转换为LocalDate/LocalTime格式化；Timestamp和普通Date继续使用瞬时时区转换。无显式时区的既有路径不变。

绿测`/tmp/datetime-dst-green.log`：36/36通过、0跳过、0失败，包含旧26项和新增10项。原locale、严格日期解析、微秒、引号字面量、偏移与NULL测试回归通过。夏令时4项红测阶段已通过，不声称其曾有产品缺陷。

## 验证范围

测试仓入口`scripts/run-datetime-focused.mjs`显式编译当前格式化生产类和测试。新增测试使用真实JDBC值对象及Java时间对象，不通过数据库获取值；未验证JDBC驱动时区映射、编辑器保存、TIME小数精度、DST无偏移歧义输入、Linux GUI格式设置或全部全球时区。完整OSGi回归及最终安装包仍待，不把本36项叠加为历史1882完整基线。
