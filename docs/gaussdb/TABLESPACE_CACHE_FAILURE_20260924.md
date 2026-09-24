# 表空间目录读取失败的传播

关联历史场景：1.1/1.5连接异常、3.9表空间加载与刷新。Linux GUI曾在元数据连接被终止（57P01）时显示空表空间树，SQL连接恢复后才重新显示两系统表空间，详见TABLESPACE_GUI_VALIDATION_20260924.md。

## 原因与修复

PostgreDatabase.TablespaceCache.handleCacheReadError原先对任意异常返回true。父类JDBCObjectCache据此继续mergeCache临时列表，可能发布空/不完整结果，且上层接收不到错误。此行为同时影响GaussDB和PostgreSQL等共用该缓存的提供者。

删除该专用吞异常覆盖，恢复父类默认返回false。读取失败交由上层错误处理，不能把错误解释为数据库没有表空间。权限不足、目录缺失等也将报告错误，不再静默隐藏。这是明确的共享行为变化，不只是对GaussDB做特判。

## 自动化检查

PostgreTablespaceCacheFailureTest直接调用生产缓存的异常策略；7个参数包括08003、08006、57P01、57014、42501、42P01、XX000，每项分别检查SQLException及DBException包装后都不应被忽略。

修复前run-4BfR4W：1582项/1552通过/23跳过/7失败/0错误，7个新参数全部失败。修复后run-qzhcT3：1582项/1559通过/23跳过/0失败错误，7个参数全部通过；安全报告test-results-tablespace-cache-failure-20260924.json。此处测试策略方法，不是模拟整个缓存加载或断网GUI流程。

## 剩余验证

## 麒麟GUI空闲连接断开后刷新

1053–1057正常退出并更新专用测试副本插件至2.1.258.202609240142，重新启动；1058–1062展开至表空间目录。

服务端pg_stat_activity先确认同一数据库/测试账号仅有两个空闲DBeaver会话：Main的pid为281469788207424，Metadata为281470032525632。只对后者按pid、库名、账号、完整application_name、idle状态联合筛选执行pg_terminate_backend，返回t。没有终止主连接或数据库服务。

1063–1064第一次展开仍显示两个节点，但服务端只剩主连接，说明命中已有缓存；这一观察不算重连通过。1065显式对表空间目录执行“刷新”。1066稳定状态显示pg_default、pg_global，无额外错误窗口；独立查询确认新Metadata pid为281471418743104，Main pid仍为281469788207424。日志01:48:02记录Invalidate datasource及Main/Metadata的BEFORE_INVALIDATE、INVALIDATE、AFTER_INVALIDATE阶段。

结论：新版客户端的“空闲元数据连接被服务端终止→目录刷新→连接恢复→目录重载”场景通过，主连接未被测试指令终止。未写入/删除数据库对象，无DDL清理需求。恢复可能在正式查询前的连接有效性检查阶段发生，因此此GUI案例不单独证明查询中途抛出异常的重试路径；7项策略单测提供异常传播的独立证据。

## 尚未覆盖

查询进行中的断连、真实网络分区、刷新取消和权限错误GUI仍待补，不保证所有调用者有相同的自动重试行为。
