# SQL 历史日志清理与异常路径验证

对应历史场景清单 3.11 的日志持久化/清理及错误处理。补充 `QueryHistoryFileTest`，调用生产 `QMLogFileWriter`；只使用 JUnit 隔离临时目录和注入 Writer，不清理用户工作区或实际历史日志。

## 新增场景

| 场景 | 方法与断言 |
| --- | --- |
| 清理范围 | 在临时目录中创建符合过期日志命名的空目录、符号链接及其普通目标文件。调用生产 purgeOldLogs 后，目录、链接、目标内容都必须保留；已有测试仍确认真正过期日志被删除、保留期边界和无关文件保留。 |
| flush 失败 | Writer.flush 抛 IOException；首次 write/flush/close 各一次，后续事件不再写入；dispose 不重复 close。 |
| close 失败 | Writer.close 抛 IOException；dispose 不向外抛异常，重复 dispose 不再 close，后续事件不写入。 |

## 发现与修复

`run-M8RJGQ` 清理范围测试失败：只按文件名筛选时，会删除名字符合过期日志格式的空目录。另两个异常测试通过。

修复共享日志组件：日期筛选前增加 `Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)`，只处理普通文件，排除目录和符号链接。不改变日志保留期的日期比较规则。此处防止误清理类型，不宣称解决恶意并发文件替换竞态。

本次测试不覆盖 SQL 历史 UI、跨重启恢复、固定/收藏或数据库持久化；这些仍需按实际产品能力独立核实。符号链接用例在当前 macOS 测试运行环境执行，不代表 Windows 创建符号链接权限验收。

## 回归结果

`run-r3O4Jp` 完整已配置回归 **1,330 项，1,306 通过，24 跳过，0 失败/错误**，新增三项通过。既有普通日志保留期边界测试仍通过。[脱敏报告](test-results-20260924-history-cleanup.json)。真库回归临时 grantee 已删除；文件测试由 JUnit 清理其独立临时目录，未删除实际用户日志。
