# 表空间DDL生成与失败传播

对应历史清单3.9。GaussDB数据源继承PostgreSQL父提供者，表空间模型/管理器为 `PostgreTablespace`、`PostgreTablespaceManager`，本轮直接测试这些生产入口，不新造替代SQL生成器。

## 场景

`GaussDBTablespaceContractTest` 共6次执行：普通路径、含单引号路径、中文空格路径；表空间名含双引号和所有者名含空格须按标识符引用；空位置/选项不输出空子句；管理器与对象定义保持一致；定义读取失败必须传播原异常而非静默返回空动作。

检查发现：LOCATION原先直接拼接字符串，路径包含单引号会破坏SQL字面量；管理器捕获定义异常只记录日志，调用者可能误判空动作成功。测试先复现，再修改生产代码。

首轮测试编译因误用本模块未提供的DBeaverUnitTest基类失败，已改为模块现有的普通JUnit5类；这属于测试代码问题，不计为产品缺陷证据。

run-iqEfwn红测：新增6项中3通过、3失败，失败为含单引号路径的模型/管理器两入口及定义异常传播。生产修复将LOCATION交给 `SQLUtils.quoteString` 按数据源方言引用，移除管理器吞异常的catch，保持原异常传递。此修改也影响共用该实现的PostgreSQL数据源，需要保留上游回归边界说明。

run-OBc5P1修复后：新增6项全部通过，完整1569项/1546通过/23跳过/0失败错误。结果见 `test-results-tablespace-ddl-20260924.json`。本轮没有创建真实表空间或操作数据库目录；不能以SQL字符串测试宣称真实表空间生命周期验收通过。

## 真库生命周期补验

2026-09-24，集中式 GaussDB 507.0.0（build d791c80a），专用容器 `gaussdb-507-ha-lab`、端口55452、postgres库，使用已有 `enable_absolute_tablespace=on` 配置，未改服务器配置。每次在容器 `/tmp` 创建随机专用目录及随机对象，结束后删除专用对象并用 rmdir 清理空目录，没有递归删除业务目录。

独立测试仓 `scripts/verify-tablespace-live.mjs` 自动执行9个检查并全部通过：

| 场景 | 检查及结果 |
| --- | --- |
| 单引号路径 | SQL字面量正确转义后，服务端仍拒绝，SQLSTATE 42602；这是预期负向通过，不代表支持该路径 |
| 中文/空格路径 | 服务端拒绝，SQLSTATE 42P17；该版本限制目录字符 |
| 失败创建 | 查询pg_tablespace，确认没有遗留对象 |
| 普通路径创建 | 创建成功，pg_tablespace_location与输入路径相同 |
| 数据往返 | 指定表空间建表，插入中文、单引号、NULL，按主键读取精确匹配 |
| 元数据关联 | pg_class关联pg_tablespace，返回目标表空间名 |
| 非空删除保护 | DROP报55000；再次查询两行数据仍在 |
| 改名 | ALTER TABLESPACE RENAME成功，新名可查且表数据保留 |
| 删除清理 | 删除专用schema/表空间，系统目录计数为0，专用目录全部rmdir成功 |

运行：在独立测试仓执行 `GAUSSDB_TABLESPACE_ALLOW_DDL=YES node scripts/verify-tablespace-live.mjs`。脚本固定针对上述本地测试容器，需Docker访问权限，不能直接用于客户生产环境。任何SQL错误、结果不符或清理失败都会返回非零状态。

本轮9项是gsql服务端合同验证，未加入JUnit1569项统计，也不是DBeaver生产管理器→JDBC→GUI完整端到端。SQL转义单测对特殊路径的通过仅说明生成合法SQL，不能替代服务端路径限制判断。

## 剩余范围

客户端GUI、非管理员权限、非空目录拒绝、HDFS/RESIZE及其他部署版本仍待验证。改名仅验证本版服务端SQL，不代表已提供客户端编辑入口。共享PostgreSQL代码仍需上游回归。
