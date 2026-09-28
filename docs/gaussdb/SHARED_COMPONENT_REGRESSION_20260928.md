# 近期共享组件修复的联合回归

## 最新扩大回归：未通过

主仓a61c57c26e，执行`node scripts/run-shared-focused.mjs --gauss-core`，同次编译全部34个非Live GaussDB核心测试类，并与原共享专项联合执行。结果931项：818通过、113失败、0跳过，进程退出1。日志`/tmp/shared-gauss-core-expanded-20260928.log`。

逐项汇总失败：GaussDBDialectTest 27项因未初始化OSGi应用、SQLSyntaxManager访问workbench失败；GaussDBInsertFromDataTest 52项及GaussDBPredicateScannerTest 34项因Platform.getBundle返回null、无法loadClass。共113项，不排除后重算全绿，也不把环境失败记为跳过或通过。没有据此修改生产逻辑或删减断言。

该模式明确未执行GaussDBReviewLiveTest、GaussDBDumpAllLiveTest、GaussDBNativeLiveTest三个真库类，启动时打印清单；这些没有被JUnit发现，不包含在“0跳过”中。不是全部核心模块验收。其他依赖仍使用已有构建产物，不能替代全量Tycho/OSGi构建。

下方654/654是范围较小的专项结果，不是扩大回归的成功结果；两组包含重叠测试，不相加。下一步需在实际OSGi运行环境中复验这三个失败类及其他测试，而非用模型替身替换其环境。

## 执行范围

生产代码基线：主仓576b12cb3c，另含本轮二进制流重载修复和2项新增测试。测试仓`scripts/run-shared-focused.mjs`同一次javac与同一次JUnit进程运行22个类，避免仅凭各专项独立进程的历史成功结果推断组合运行也成功。显式编译过滤器相关类及DBAAuthProfile、DBPConfigurationProfile、DBWNetworkProfile、DBWHandlerConfiguration，以及SQLQuery、SQLSelectItem、GaussDBDialect、SQLQueryRecoveryPolicy、SQLSemanticProcessor、GaussDBBinaryValueHandler、GaussDBValueHandlerProvider和JDBCContentBLOB。

显式编译当前执行计划解析/保存、日期时间格式、版本选择、三类数据库异常、三类进度监视器、XLSX/CSV导出器、流式消费器和DataTransferJob源码，以及对应测试。其他依赖仍使用已有target/classes和只读依赖jar，不是完整Tycho/OSGi重新构建。

## 最新结果

`/tmp/shared-binary-overload-green-20260928.log`：654项全部通过，0跳过、0失败、0中止。下方分组表已更新为本次结果。历史增量记录仅供追溯，不应累加。

### 历史增量（非当前总数）

后续日志I/O回归：`/tmp/shared-log-io-green-20260928.log`342/342通过、0跳过、0失败。基于0a568a1a30另含异步读取错误传播及三项故障流测试；下方339项均复跑，备份生命周期分组增至28。完整构建run-WXzqkw仍在Tycho缓存锁超时，不能用专项结果替代完整验收。

后续迟到日志隔离回归：`/tmp/shared-late-retry-green-20260928.log`339/339通过、0跳过、0失败。基于ed144f9ef8另含每轮日志独立诊断修复及一项取消立即重试测试；两项末行/普通重试用例升级为真实异步读取。下方338全数复跑，备份生命周期分组增至25，不叠加统计。

后续实际异步日志回归：`/tmp/shared-async-cancel-green-20260928.log`338/338通过、0跳过、0失败。基于20433e4679另含等待日志完成修复及stdout/stderr/等待取消三项；下方335项均复跑，备份生命周期分组增至24。原337中间结果不叠加。取消后的日志线程强制回收与立即重试隔离未由本轮证明，见NATIVE_COMMAND_CONTRACTS_20260928.md。

后续日志重试回归：`/tmp/shared-retry-green-20260928.log`335/335通过、0跳过、0失败。基于e59165fcd5另含错误状态重置/无换行末行修复及两项实际日志读取测试；下方333项全数复跑，备份生命周期分组增至21。日志同步读取的证据不能代替异步读取时序验收，详见NATIVE_COMMAND_CONTRACTS_20260928.md。

后续启动前取消回归：`/tmp/shared-prestart-green-20260928.log`333/333通过、0跳过、0失败。基于f5262aa0a4另含共享原生基类三个准备时点取消检查及对应三项测试；下方330项全部重跑，备份生命周期分组增至19，其他不变。不叠加历次执行数，具体证据及并发边界见NATIVE_COMMAND_CONTRACTS_20260928.md。

后续目录备份保护回归：`/tmp/shared-directory-green-20260928.log`330/330通过、0跳过、0失败。基于1d951d4f85另含非空目录保护生产修复及三项新增测试；下方327项全部重跑，备份生命周期分组从13增至16，其他不变。详见NATIVE_COMMAND_CONTRACTS_20260928.md。不将330与327叠加。

`/tmp/shared-native-final-20260928.log`：327项，327通过、0跳过、0失败、0错误中止。新增显式编译共享AbstractNativeToolHandler及四个PostgreSQL原生工具处理器。

## 最新分组统计

| 分组 | 同次执行数 |
| --- | ---: |
| 执行计划四类测试 | 80 |
| 驱动版本选择 | 18 |
| 异常封装 | 10 |
| 进度监视器 | 8 |
| 日期时间格式 | 43 |
| XLSX导出 | 74 |
| CSV字面替换 | 10 |
| 流式导出/任务错误处理 | 49 |
| 原生命令认证/参数/文件暂存 | 20 |
| 备份发布/任务循环/进程与日志生命周期 | 33 |
| 恢复输出重定向及实际2MiB输出不阻塞 | 2 |
| 连接过滤器序列化与输入校验 | 36 |
| 用户/默认过滤器导出往返 | 8 |
| 认证配置凭据记录与复制隔离 | 24 |
| 网络配置作用域、替换、凭据往返、损坏保护及旧版迁移 | 24 |
| SQL投影、别名来源、DML目标、集合分类、文本替换及安全判断 | 89 |
| SQL自动重试策略与文本切换 | 51 |
| GaussDB危险SQL检测 | 51 |
| 二进制NULL/空值/文件绑定、重试及降级 | 24 |

上述为最新本次不重叠分组计数，合计654；本轮新增2项文件流重载，其余652项复跑。历次159至652项结果均被本次覆盖，不叠加，也不能与历史1882完整回归累计。

脚本使用独立临时输出目录，JUnit XML报告写入该目录reports中；报告可能包含运行环境元数据，不直接发布原始XML。完整回归入口继续通过脱敏导出器仅输出测试身份和结果，并要求20个关键类确实运行通过，不能以缺失/跳过代替通过。依赖Unix可执行文件的BackupPublish测试未作为Windows必需门控，但本次确实执行且无跳过。

## 未覆盖边界

联合组件测试没有访问GaussDB、没有启动Linux GUI、没有验证驱动下载，也不是全部客户端历史清单验收。完整构建最近仍在run-M0GyL5因Tycho缓存锁失败，真库Java连接复验仍因权限失败；Hermes初始化权限问题也没有被本次本地编译解决。共享修改仍需完整回归、实际环境验收后再作为已验收版本推送。
