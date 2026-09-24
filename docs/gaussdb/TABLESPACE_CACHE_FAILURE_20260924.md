# 表空间目录读取失败的传播

关联历史场景：1.1/1.5连接异常、3.9表空间加载与刷新。Linux GUI曾在元数据连接被终止（57P01）时显示空表空间树，SQL连接恢复后才重新显示两系统表空间，详见TABLESPACE_GUI_VALIDATION_20260924.md。

## 原因与修复

PostgreDatabase.TablespaceCache.handleCacheReadError原先对任意异常返回true。父类JDBCObjectCache据此继续mergeCache临时列表，可能发布空/不完整结果，且上层接收不到错误。此行为同时影响GaussDB和PostgreSQL等共用该缓存的提供者。

删除该专用吞异常覆盖，恢复父类默认返回false。读取失败交由上层错误处理，不能把错误解释为数据库没有表空间。权限不足、目录缺失等也将报告错误，不再静默隐藏。这是明确的共享行为变化，不只是对GaussDB做特判。

## 自动化检查

PostgreTablespaceCacheFailureTest直接调用生产缓存的异常策略；7个参数包括08003、08006、57P01、57014、42501、42P01、XX000，每项分别检查SQLException及DBException包装后都不应被忽略。

修复前run-4BfR4W：1582项/1552通过/23跳过/7失败/0错误，7个新参数全部失败。修复后run-qzhcT3：1582项/1559通过/23跳过/0失败错误，7个参数全部通过；安全报告test-results-tablespace-cache-failure-20260924.json。此处测试策略方法，不是模拟整个缓存加载或断网GUI流程。

## 剩余验证

需将新插件部署测试副本，针对专用客户端连接断开/取消后重新刷新验证恢复流程与显示结果。未做真实网络故障注入，也不保证所有调用者有相同的自动重试行为。不得以本策略单测代替这一端到端检查。
