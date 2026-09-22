# 代码审查记录：采纳、修复与验证

日期：2026-09-17。修改基线：`99e620c16dd8403f4730d6242abcab7d22f524f5`，分支 `feature/gaussdb-compatibility`。

后续已按用户要求执行真库验证，并额外修正包源码行偏移。详见 [真实数据库验证报告](GAUSSDB_LIVE_REVIEW_VALIDATION_20260917.md)。下文 640 项及“未连库”为首次静态修复轮的历史记录，不是后续真库轮的最终状态。

本记录汇总编号 001–009 的九轮静态审查结论；没有完整报告的轮次不计入统计。

静态审查报告不是验收报告。以下区分代码证据、已执行测试和仍需验证的事项；审查意见经过代码复核后采纳。

## 1. 本轮修改

| 审查编号 | 修改内容 | 验证/边界 |
|---|---|---|
| R004-02 | GaussDB 断点描述符持久化精确数据库名；controller 拒绝其他数据库及无数据库身份的旧 marker；M/未知模式及未保存对象不创建断点描述符 | 测试同 OID/行号跨库、大小写不同、缺数据库字段及描述符往返；测试 M/未知/未保存门控。旧断点需删除重建，不从当前库猜测归属 |
| R005-02 | 删除回调优先从 `IMarkerDelta` 读取旧属性；保留数据源、模型、数据库隔离；捕获当前 session，避免工作线程再次读取已清空字段；marker 为 null 时安全返回 | 回调单测证明无需访问已删除 marker 仍调用服务器断点删除接口。没有声称本轮验证了 Eclipse 实机删除时序 |
| R006-03 | 需要客户端去除角色密码时，即使本地目标也先写私有临时文件；进程成功且未取消后才脱敏、发布；失败/取消/截断不覆盖目标；finally 清理临时文件，清理失败记录日志 | 五个注入式用例：进程抛错、取消、返回失败、截断、成功脱敏。覆盖原文件保持、目标不存在、临时文件清理。未读取真实备份；进程/系统崩溃不属于 finally 可保证范围 |
| R006-04 | restore 和 backup-all 的 stdout 不作为文件内容使用，显式重定向到 DISCARD；stderr 保留给原日志读取器 | 验证重定向配置，并实际启动合成进程输出 2 MiB，10 秒内退出且 stderr 可读。没有改动使用 stdout 传输备份数据的普通 backup 流式路径；没有声称这是实际 gsql 恢复测试 |
| R002-03 | Value 单元格保存时比较显示值与编辑值，未变更则不重置 NULL/DEFAULT；真实修改仍转 VALUE | UI 模块编译通过，保存回调静态复核。本轮未启动 SWTBot；点击/Tab/Enter 的实机事件验证仍需补做。要使用同样显示文本作为显式值，可从 Mode 列直接选择 VALUE |
| R003-01 | ALTER 执行后读取诊断失败，单独报告“编译已执行，但诊断无法读取”，保留原因并中断批次；不生成源码第 1 行伪错误，也不算成功 | 42P01、42501、08006 注入测试：保留 SQLException cause、没有伪源码诊断；此前批次部分结果保留逻辑继续适用 |
| R003-02 | ALL 编译在没有 GS_ERRORS 定位证据时保留 ALL，不根据错误消息猜 SPEC/BODY；明确 SPEC/BODY 仍保持指定部件 | 测试错误文本提到其他 package body 时不误选标签；现有混合目录诊断测试继续验证 type/line。ALL 未知部件只打开编辑器，不猜定位 |
| R003-04 | 取消与失败并存时显式保留 debug 日志原因，任务仍返回取消状态；不把正常取消升级成错误对话框 | UI 模块编译及 AbstractJob/Log 静态链复核；保留原有取消摘要和部分诊断展示。此项没有新增 GUI 验收结果 |
| R001-04 | 默认参数重载校验传入实际启动 monitor，查询前后检查取消，目录查询设置 10 秒 timeout | 校验取消时不访问数据库、正常查询设置 timeout。未给 continue、目标执行或 COMMIT 设置任意短超时；驱动实际中断时效不由 mock 证明 |
| R007-03/04 | Windows 指南纠正 appstore profile 描述，明确 Native/兼容驱动类和 URL、jar 版本取证、旧断点升级处理及代码基线差异 | 文档与 build.cmd、plugin.xml 对照；不据默认 jar 版本号臆断不兼容；没有执行 Windows 验收 |

## 2. 测试执行

在 macOS 本机使用 JDK 25.0.2 构建，源码目标仍为 Java 21。沿用已建立的 71 模块 reactor；没有更改 JDK 目标或外部依赖。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
mvn -o verify -f product/aggregate/pom.xml \
  -pl "$(paste -sd, tools/gaussdb-review-reactor.txt)" \
  -Dskip-checkstyle=true -Dspotless.check.skip=true \
  -l /tmp/gaussdb-code-review-tests.log
```

最终执行于 18:07:35 完成，`BUILD SUCCESS`，71 模块全部成功：

| 测试模块 | 总数 | 失败/错误 | 跳过 |
|---|---:|---:|---:|
| platform | 448 | 0/0 | 3（原有） |
| GaussDB model | 80 | 0/0 | 0 |
| GaussDB debug | 67 | 0/0 | 0 |
| PostgreSQL | 45 | 0/0 | 0 |
| 合计 | 640 | 0/0 | 3 |

即 637 通过、3 跳过；较基线 627 项新增 13 项测试。Checkstyle/Spotless 在此命令中跳过，不能宣称已通过这两项检查；另执行 `git diff --check`，无空白错误。

新增测试文件：

- `test/org.jkiss.dbeaver.ext.gaussdb.test/src/org/jkiss/dbeaver/ext/gaussdb/model/GaussDBBackupPublicationTest.java`：五个备份发布/清理分支。
- `test/org.jkiss.dbeaver.ext.gaussdb.test/src/org/jkiss/dbeaver/ext/gaussdb/model/GaussDBNativeOutputTest.java`：重定向配置及合成进程。合成进程依赖 `/bin/sh` 和 `/usr/bin/head`，没有它们时跳过，不是 Windows 测试。
- `test/org.jkiss.dbeaver.ext.gaussdb.debug.test/src/org/jkiss/dbeaver/ext/gaussdb/debug/core/internal/GaussDBRemovedBreakpointTest.java`：已删除 marker 的 delta 回调。
- 现有 Protocol、Session、PackageCompiler 测试补充数据库隔离、门控、超时/取消、诊断来源及未知部件。

## 3. 未盲目采纳的意见

| 其他审查意见 | 处理决定 |
|---|---|
| R003-03：仅凭 gs_package 存在就开放包功能 | 撤回。部署类型未知时关闭包功能是合理的保守策略；历史分布式实例存在目录但不支持包 |
| R001-01/02/05：网络故障下 COMMIT/cleanup 等待与异常传播 | 保留风险，需要驱动超时/断网/关闭故障注入及结果未知策略。不能任意截断正常调试等待，也不能对未知结果 COMMIT 自动重试 |
| R002-02：相同参数数量但签名漂移 | 需先证明服务端 OID 与签名变更路径可达，不能直接引入错误的签名迁移规则 |
| R002-04：损坏配置模式列表不匹配回退 | 正常保存等长，core 执行层有参数计数校验，但 UI 迁移路径仍需独立配置兼容测试；不把历史缺模式配置一律判错 |
| R004-01/03/04：视图跨库模式、未知模式过程创建、语言资格加载线程 | 需实际生成器可达性、兼容边界及 UI 调用线程证据；本轮没有把同步方法等同于 UI 卡死，没有做数据库名大小写归一化 |
| R005-01/03/04：移动断点、PG 幂等、变量线程、PG 含头源码行偏移 | 保留为独立 PG/客户端验证项；不改未经锚定的服务端行号规则，不把同步 setValue 等同于 UI 线程，不用会吞异常的任务包装掩盖错误 |
| R006-01/02：Windows DLL 路径、取消退出码状态 | 需实际工具分发布局及外层任务状态测试；本轮没有 Windows 原生运行证据 |
| R007-01/05：公网 p2 更新、包的源码 SHA 追溯 | 属交付策略/打包项，需发布版本解析与重新制品化；检查更新不等于静默替换。此次不是产品发布，未更改默认上游更新渠道 |
| R001-03、R002-05、R003-05、R006-05：覆盖不足 | 针对已修问题增加上述测试；不宣称补齐了全部并发、GUI、驱动故障和真库覆盖 |
| R007-02：本地提交未推送、旧候选包不含修复 | 在 Windows 指南明示；本轮用户授权修改，没有执行 push 或更新客户安装包 |

## 4. 交付边界

本轮没有连接数据库、改部署、运行客户 Windows/麒麟机器或重打 Linux 客户包。单元测试与合成进程结果不替代服务端真实行为、SWT 操作、集中式包编译和最终产品验收。

本文保留当轮修复及验证结论。后续软件版本、发布清单和使用入口见 [客户文档导航](../../docs/gaussdb/README.md)，不要求用户访问维护环境的临时报告目录。上述日志输出位置为复现命令的示例路径。
