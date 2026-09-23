# 日期格式区域设置验证

## 场景与问题

对应历史清单中的日期格式、字符显示及客户端个性化设置。生产组件 `DateTimeDataFormatter` 由日期、时间、时间戳格式器扩展共用；本轮直接调用该组件，不将 JDBC 返回文本测试当作客户端格式化验证。

旧实现把传入的 Locale 用于 `ExtendedDateFormat`，但构建 Java 时间格式器时未传 Locale。因此 LocalDateTime、OffsetDateTime、ZonedDateTime，以及指定显示时区后的 JDBC Timestamp 会使用 JVM 默认区域；用户选择的月份语言可能不生效，按该语言输入的月份也可能无法解析。

修复仅将传入 Locale 同样用于 `DateTimeFormatter.ofPattern`，不改变时区或精度策略，不修改 JVM 全局设置。

## 自动化场景

`DateTimeDataFormatterLocaleTest` 共 6 项：

| 场景 | 操作与断言 |
|---|---|
| LocalDateTime | 同一闰日时间分别用法语、英语格式化，断言 février/February |
| OffsetDateTime | UTC 时间按显式 UTC 时区显示，断言两种语言月份 |
| ZonedDateTime | 同上，覆盖带地区时区的类型处理路径 |
| JDBC Timestamp | 从明确 Instant 构造，显式 UTC 显示并核对两种语言月份 |
| 文本往返 | 无时区设置，法语月份文本按 LocalDateTime 类型解析后再格式化，与原值一致 |
| SQL 空值显示 | 指定语言和时区，NULL 保持 NULL，不变为文本 |

修复前 `run-LLTM6J`：本测试类 4 项断言失败、1 项解析错误、1 项通过。失败运行中的其他模块结果可能未刷新，不据此汇总整套结果。

## 验证边界

修复后 `run-5ENTGA` 6 项新增测试全部通过；整套新鲜结果为 1387 项、1363 通过、24 跳过、0 失败/错误。临时真库授权角色已删除。逐例记录见[脱敏测试报告](test-results-20260924-date-locale.json)。跳过不计作通过。

这是共享生产格式器的单元回归，不是 GUI 首选项操作验收，也不是菜单汉化修复。既有日期格式、具体时区策略、非本轮语言和客户安装包仍需分层验证。
