# 三项缺陷修复与真库验证（2026-09-18）

## 结论与范围

9 月 17 日补测确认的三个生产问题均已修复，本轮对应真库回归全部通过。
最终 71 模块 reactor 为 BUILD SUCCESS：658 项，654 通过、0 失败、0 错误、4 跳过。
这是当前工作区源码的编译、单测及指定真库场景结果，不等于新安装包或完整客户端 UI 验收。
本轮没有提交、推送，也没有替换客户候选安装包。

历史失败证据保留在 [9 月 17 日补测报告](GAUSSDB_REMAINING_VALIDATION_20260917.md)。

## 1. 普通账号原生工具认证

- 根因：这套 GaussDB 507 原厂工具在显式 TCP 连接下不接受原有 PGPASSWORD 传密方式；同一账号用 --pipeline 可认证。
- 实现：PostgreServerExtension 新增 usesNativePasswordPipe 能力，默认 false；PostgreServerGaussDB 返回 true。PostgreNativeToolHandler 给 GaussDB 工具添加 --pipeline，并通过进程 stdin 写入 UTF-8 密码及换行，同时去掉继承的 PGPASSWORD。普通 PostgreSQL 保留原环境变量认证方式。
- 安全边界：自动传入的密码不进入 argv；拒绝包含 CR/LF/NUL 的管道密码，写入失败终止进程。备份恢复写完后关闭 stdin；执行脚本保留 stdin，供后续 SQL 输入。
- 验证：GaussDBNativeLiveTest 使用临时普通账号，明确 TCP 127.0.0.1:55452，调用生产环境配置和密码写入方法。gs_dump→gsql 明文恢复、gs_dump→gs_restore 自定义格式恢复均检查 count=3、sum=6。额外产生约 4 MiB stdout，验证恢复进程不会因无人读取 stdout 而阻塞。方法耗时约 1.14 秒。
- 单测：GaussDBNativePasswordTest 的 5 项全部通过，覆盖管道字节及关闭、PG 行为不变、非法换行拒绝、仅 GaussDB 添加选项、脚本后续输入仍可写。
- 边界：测试启动参数和 settings 是夹具桥接，不是从 DBeaver UI 点击原生任务；gs_dumpall 的新认证路径未在本轮另做真库认证验收。

## 2. COMMIT 回执丢失时取消/关闭阻塞

- 根因：Statement 查询超时不约束 Connection.commit；原连接 socketTimeout=0 时，提交持有 transactionLock 无限等待读响应，关闭线程也等待该锁。
- 实现：GaussDBDebugSession 仅在事务完成期间设置 JDBC 网络读超时上限 10 秒，已有更短超时不放宽，完成后尽力恢复原值。COMMIT/ROLLBACK 与连接关闭仍串行；结果未知后禁止再次提交，也不再在关闭时补发回滚或 turn_off。关闭阶段 abort/turn_off 同样增加网络读等待约束，正常调试步进保持既有设置。
- 真库方法：专用 TCP 代理转发真实 COMMIT 后丢弃服务端回复；独立连接确认数据确实已经提交。设置 monitor canceled，同时调用 closeSession。测试没有提前断开代理来帮助生产代码退出。
- 结果：socketTimeout=0 场景约 10.08 秒结束，报告结果未确认，关闭完成，重复提交被拒绝，代理只看到一次 COMMIT。原有 3 秒连接超时场景约 3.35 秒通过。
- 单测：验证无限超时被临时改为 10000 ms 后恢复为 0；原有 1200 ms 超时不会被放宽。
- 边界：取消不是即时打断 COMMIT；它等待网络读超时返回。提交结果未知不代表数据库已回滚，用户必须用独立连接确认业务结果。该上限是 JDBC 网络读等待约束，并非所有驱动/网络流量形态的绝对总耗时保证。

## 3. 删除真实 Eclipse marker 后遗留服务端断点

- 根因：实际资源删除事件传入 delta=null，marker 此时已不存在，再读取 datasource/OID/行号会失败。
- 实现：DatabaseDebugTarget 在解析有效且属于当前数据源的断点时保存 descriptor；删除事件无 delta 时使用已保存身份，并移除缓存。存在 delta 时仍验证来源数据源。调试目标终止时清空缓存。
- 真库方法：在真实 Eclipse workspace 建临时 project/marker，注册真实 BreakpointManager/DebugTarget，marker.delete 后触发 build。断言收到删除通知、delta=null、marker 不存在，以及服务端断点数为 0。
- 结果：原先失败的 expected-zero 断言保持不变，本轮通过。同一测试在此前执行 4 线程 × 10 轮 × 增/禁/启/删，共 160 次生产调试会话操作，最终断点状态收敛；整个方法约 0.31 秒。
- 边界：这是实际资源事件与真库的回归，不是双窗口 GUI 操作录像。

## 构建及复跑

环境：macOS ARM64、JDK 25、Maven/Tycho；GaussDB 507 集中式测试实例 gaussdb-507-ha-lab，内部端口 55452，经仅本机可访问的 55453 转发。使用独立临时账号和 ORA/B/PG 三个测试数据库。

```sh
GAUSSDB_REVIEW_CONNECTION=/path/to/private/connection.properties \
GAUSSDB_REVIEW_JDBC=/path/to/gsjdbc4.jar \
GAUSSDB_REVIEW_NATIVE=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
mvn -o -fae verify -f product/aggregate/pom.xml \
  -pl "$(paste -sd, tools/gaussdb-review-reactor.txt)" \
  -Dskip-checkstyle=true -Dspotless.check.skip=true \
  -l /tmp/gaussdb-fixes-0918-verified.log
```

| 测试模块 | 总数 | 通过 | 跳过 |
|---|---:|---:|---:|
| test.platform | 448 | 445 | 3 |
| ext.gaussdb.test | 92 | 91 | 1 |
| ext.gaussdb.debug.test | 73 | 73 | 0 |
| ext.postgresql.test | 45 | 45 | 0 |
| 合计 | 658 | 654 | 4 |

跳过项：平台原有 SQL 补全 1 项、SVG 2 项；gs_dumpall 合成凭据空集群用例 1 项，本轮没有重建它要求的独立 55462 空实例。9 月 17 日的 gs_dumpall 结果不重复计入本轮通过数量。集中式包编译目标、真实错误行定位、诊断视图缺失降级、默认参数重载检查亦随 reactor 复跑通过。Checkstyle/Spotless 本轮显式跳过；git diff --check 通过。

首轮新增测试有 ByteArrayOutputStream Mockito spy 和嵌套 stubbing 的夹具错误，已修正；以上统计只取最后一次完整成功执行，不累加各轮次数。

## 清理与未外推事项

- 已删除本轮三个临时数据库、临时登录及本机私有凭据文件，独立查询确认数据库/角色残留数均为 0。这些是可重建的合成测试数据，不涉及客户数据。
- 临时访问规则已撤回，gs_hba.conf 恢复原始内容，SHA-256 为 e7bf4026e9e594463b92e6fc644b5aa311ff0bb2149c176e7dad8c26bc8a0060。
- 集中式测试实例与临时端口转发恢复停止状态；未停止既有 CN 集群。
- 本轮未重新执行 Windows、麒麟实机、完整调试快捷键/对话框、打包安装升级；不能把这三项源码修复宣称为全部交付环境验收通过。
