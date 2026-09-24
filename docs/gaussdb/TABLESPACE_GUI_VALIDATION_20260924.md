# 表空间导航与DDL预览：Linux现场验证

环境：麒麟V10 x86_64测试客户端、DBeaver 26.1.5，连接GaussDB 507分布式实例distributed_acceptance，普通测试账号。只读系统表空间，没有修改或删除系统对象。使用专用工作区中的SWTBot操作真实控件。

## 操作和证据

1. 1030–1032：连接导航树展开“数据库 → distributed_acceptance → 存储 → 表空间”，初次为空。独立gsql查询pg_tablespace返回pg_default、pg_global。
2. 1033刷新；日志记录元数据连接被终止，SQLSTATE 57P01。生产TablespaceCache.handleCacheReadError会记录日志并忽略异常，所以不能将空树解释为库中无表空间。尚未对这一通用缓存策略做修改。
3. 1034–1035：SQL编辑器执行SELECT t.oid,t.spcname,pg_tablespace_location(t.oid) loc FROM pg_catalog.pg_tablespace t ORDER BY t.oid。执行日志成功、2行，读取控件显示2行已获取。不能把不含单元格文本的网格dump当作逐值校验；两个名称另由导航树及独立查询验证。
4. 1036：重新打开连接视图，表空间树出现pg_default和pg_global，连接恢复后加载成功。
5. 1037：双击pg_default，属性名称显示pg_default；DDL源码区出现ERROR WHILE READING SOURCE，错误为所有者对象为空导致DBSObject.getDataSource空指针。此场景为失败，不是验收通过。

## 修复与验证边界

PostgreTablespace生成DDL时先解析所有者，存在才输出可选OWNER子句，与现有PostgreSchema的可选所有者处理一致。数据库查询抛出的DBException仍继续向上传播，不被当作“没有所有者”吞掉。新增unresolvedOwnerOmitsOptionalClauseInsteadOfCrashingPreview回归测试。

OWNER省略时，执行创建SQL会使用执行账号作为所有者，因此这一降级只保证可预览，不保证原所有权等价复制。未知所有者的对象迁移仍须核实并补充OWNER，不能把空值处理修复称为完整导出还原验收。

run-came2H完整回归1575项：1552通过、23跳过、0失败/错误，新增空所有者测试通过。安全结果清单为test-results-tablespace-null-owner-20260924.json。

## 更新后的GUI复验

1038–1041正常退出客户端，确认原启动器/Java进程消失；只更新专用测试副本PostgreSQL模型插件到2.1.258.202609240113及bundles.info，然后用原专用工作区重新启动，没有替换客户发行包。1042–1048关闭每日提示、重新连接并逐层展开表空间，显示pg_default/pg_global。

- 1049：双击pg_default，名称字段可见，源码区可见内容为CREATE TABLESPACE pg_default，没有ERROR WHILE READING SOURCE。
- 1050–1051：回到导航，对表空间目录执行刷新，两项仍存在。
- 1052：双击pg_global，新对象名称字段及CREATE TABLESPACE pg_global源码可见；pg_default旧源码为不可见，避免将旧编辑器内容当新结果。

独立测试仓verify-tablespace-preview.mjs对1049、1052真实dump验证通过。验证器另有5项测试全部通过，包括拒绝隐藏旧源码、错误对象、源码错误与截断输出；这些5项是验证器测试，不加入JUnit1575统计。至此两系统对象的导航、目录刷新及空所有者预览崩溃修复GUI复验通过。

没有执行CREATE TABLESPACE pg_default/pg_global，这些是系统对象，不能拿预览SQL直接重建。未知OWNER的迁移限制仍存在。自定义表空间创建/删除GUI、目录权限以及目录内容发生变化后的刷新仍未由本记录覆盖。
