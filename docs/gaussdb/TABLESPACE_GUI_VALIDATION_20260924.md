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

当前GUI运行副本尚未部署此修复；需要重建插件、正常退出并更新测试副本后重新打开对象，验证源码区不再报错。新建/删除自定义表空间、目录权限及刷新变更仍未由本记录覆盖。
