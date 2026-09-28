# 集合查询真实 JDBC 验证：待网络条件恢复

## 新增场景

正式调试测试模块 `GaussDBHistoricalJdbcLiveTest` 新增四项只读用例：UNION、UNION ALL、INTERSECT、EXCEPT。

每项先通过生产 SQLQuery 识别多层括号查询，验证 SELECT、非修改及不生成单表目标，再通过厂商 JDBC 执行。断言两列中文别名、INTEGER/VARCHAR 类型、NULL 与整数 0 区分、中文值、重复行保留或消除、集合运算结果和排序；随后在同一连接执行 SELECT 42。仅使用字面量查询，不创建表或 schema，不修改数据。

## 执行结果

- 首次专项运行器编译失败：旧临时 GUI 目录已缺少依赖。运行器改读当前已解析的 OSGi 依赖，缺失即失败，并显式编译当前 SQLQuery。
- 下一次失败：连接配置没有 driverClass，独立连接辅助方法缺少其他入口已支持的环境变量回退。补同等回退及明确非空检查；未修改生产驱动。
- 最终厂商 JDBC 实际尝试建连，四项均因 `SocketException: Operation not permitted` 失败：**0 通过、4 失败、0 跳过**。断言尚未到达结果集，不能宣称列元数据或结果验证通过。

最终专项日志 `/tmp/set-query-live-final-20260929.log`，进程退出 1。既有 gsql 语法对照通过不能替代本项 JDBC 验收。

## 复跑

在测试工具仓设置以下环境变量（使用既有受保护配置文件，不将密码写入命令行或仓库）：

- `GAUSSDB_HISTORY_CONNECTION`：连接属性文件绝对路径，包含 url/user/password，可选 driverClass。
- `GAUSSDB_HISTORY_JDBC`：厂商 JDBC jar 绝对路径。
- `GAUSSDB_HISTORY_DRIVER_CLASS`：配置未给出 driverClass 时使用，例如 `com.huawei.gauss200.jdbc.Driver`。

```sh
node scripts/run-live-plan-focused.mjs --set-queries
```

该选项仅选择四个只读场景，不要求 DDL 开关；默认执行计划测试仍保留原 DDL 授权要求。新增 JUnit XML 检查：失败/错误/跳过均拒绝，四项必须完整执行；本轮网络失败已导致脚本非零退出，但全部通过分支仍须在可连接环境验证。

## 常规回归范围

73 模块诊断共 2540 项：2366 通过、173 跳过、1 Rest 网络权限错误；新增四项未配置真库而跳过，不增加通过数。独立验收门禁仍拒绝。见 [逐项结果](evidence/SET_QUERY_JDBC_REACTOR_20260929.json)。不能将常规构建中的跳过与专项中的失败累加为新用例或通过。
