# DBeaver GaussDB 适配说明

> 2026-09-09 本地补验：包编译取消/断连恢复、原生 PostgreSQL GUI 回归、五模式能力、
> 权限变更及空白 macOS 工作区默认驱动流程见
> [GAUSSDB_EXTENDED_ACCEPTANCE_20260909.md](GAUSSDB_EXTENDED_ACCEPTANCE_20260909.md)，包含未覆盖边界。

> 后续功能范围、缺口和验收标准以
> [GAUSSDB_FEATURE_REQUIREMENTS_ANALYSIS.md](GAUSSDB_FEATURE_REQUIREMENTS_ANALYSIS.md) 为准；
> 本文记录的是兼容适配过程；调试与 Package 的当前实现/验收状态见需求分析报告，
> 不把源码构建成功等同于真实数据库和跨平台客户包验收完成。
> GaussDB 507 分布式真库结果见
> [GAUSSDB_REALDB_VALIDATION_20260902.md](GAUSSDB_REALDB_VALIDATION_20260902.md)。

> 分支：`feature/gaussdb-compatibility`
>
> 本轮状态：2026-09-02
>
> 范围：连接、元数据、对象管理、兼容模式、分区、安全、原生工具，以及补充的
> RLS/generated column/vector/HLL 支持

## 结论

GaussDB 插件仍采用“继承 PostgreSQL 插件 + GaussDB 特化”的结构，但不再把 GaussDB
产品版本直接当作 PostgreSQL 版本使用。能力由真实 catalog、函数和兼容模式探针决定，
GaussDB 不兼容的 PostgreSQL 功能会在 UI 中关闭或走专用实现。

本轮已完成的核心内容：

- GaussDB `DBE_PLDEBUGGER` 专用 Debug core/UI：F7/F8/Shift+F7/F9/F10、断点启停删、
  Variables/变量名 Watch、变量修改、调用堆栈，以及调试完成后的提交/回滚选择。
- Debug 入口按目标数据库兼容模式和 routine language 门控：M/未知模式、未保存对象和非
  PL/pgSQL/PLSQL routine 不显示或不可选择；启动前逐一校验 17 个 API 的参数签名、API
  EXECUTE 权限、目标 routine EXECUTE 权限，并要求 system administrator/superuser 或
  `gs_role_pldebugger` 成员身份。507 的 `add_breakpoint(oid,integer)` 和新版文档的
  `add_breakpoint(text,integer)` 均作为明确的兼容签名支持。
- Package ALL/SPECIFICATION/BODY 编译、spec/body 状态、`GS_ERRORS` 错误读取和行级定位，
  以及完整限定名的多选删除。
- M 模式过程门控、未知部署建库值和数据库级 `ON CONFLICT` 等兼容能力修正。

- 三套驱动配置：旧版兼容驱动、M 模式 `gsjdbc4.jar`、原生 `gaussdbjdbc.jar`。
- 识别真实 GaussDB 产品版本、部署形态、`sql_compatibility` 和关键 catalog 能力。
- ORA、MYSQL、TD、PG、M 五种兼容模式的数据库创建值映射、函数/过程分流、
  `REPLACE`、忽略冲突和 `ON CONFLICT` 能力门控。
- GaussDB 专用 Range/List/Hash 分区和子分区元数据模型，以及 `pg_get_tabledef`
  原生表 DDL 读取和通用回退。
- GaussDB `pg_rlspolicies` 行级安全策略读取/编辑，以及 `reloptions` 启用状态识别。
- 集中式 `pg_attrdef.adgencol` generated column 元数据和 DDL 复用。
- `floatvector`、`boolvector` 校验编辑器和 HLL/HLL_HASHVAL 不透明值处理器。
- 标准 SSL、证书认证、多主机 URL、IPv6、认证模型和驱动属性页。
- 低权限下 Package、可选 catalog 和源码元数据的安全降级；认证及网络错误不会被吞掉。
- `gsql`、`gs_dump`、`gs_restore` 本地客户端发现和 PostgreSQL 工具名映射。
- 原生任务自动把客户端 home 下的 `lib` 加入动态库搜索路径。
- PostgreSQL 共享 SQL 的兼容改写，同时保留 PostgreSQL 默认行为和 ABI。

## 驱动配置

| 驱动 ID | JDBC 类 / URL | 用途 | 状态 |
|---|---|---|---|
| `gaussdb-jdbc` | `org.postgresql.Driver` / `jdbc:postgresql:` | 保留已有用户和旧版驱动行为 | 兼容保留 |
| `gaussdb-jdbc-m` | `org.postgresql.Driver` / `jdbc:postgresql:` | 用户选择与服务端匹配的 `gsjdbc4.jar`，启用 M 参数 | 可用 |
| `gaussdb-jdbc-native` | `com.huawei.gaussdb.jdbc.Driver` / `jdbc:gaussdb:` | 用户选择与服务端匹配的 `gaussdbjdbc.jar` | 已用 507 驱动实测 |

驱动 JAR 没有硬编码为某个跨版本通用包。M 和原生配置要求用户在 Driver Manager
中选择与 GaussDB 服务端版本匹配的驱动，避免服务端与 JDBC 协议能力错配。

本机验证使用的官方 507/JDK 21 驱动来源归档为：

```text
/Users/lj/Downloads/baiduyunpan/GaussDB 507 docker安装本地部署/X86/DBS-GaussDB-driver_x86_64_V2.0-10.0.0_26.861.0.0.1109004653171008.tar.gz
```

归档内 `gaussdbjdbc.jar` 的驱动类为 `com.huawei.gaussdb.jdbc.Driver`。已验证单地址连接，
以及“第一个地址不可达、自动回退第二个地址”的多主机连接。驱动属于 GaussDB 安装介质，
未把该二进制文件提交进 DBeaver 源码仓库。

多主机示例：

```text
jdbc:gaussdb://host1:8000,host2:8000/database
jdbc:postgresql://host1:5432,host2:5432/database
```

主机字段支持逗号分隔、多主机独立端口和 IPv6；URL 组件注入字符会被拒绝。

## 能力门控

能力不再由 `507.0.0 >= PostgreSQL 10` 之类的错误比较决定。`GaussDBServerInfo`
读取 `version()`、`opengauss_version()`、`gs_deployment()`、`pg_settings`、`pg_class`
和 `pg_proc`，`PostgreServerGaussDB` 再据此决定 UI 和 SQL 路径。

GaussDB 507.0.0 分布式实例实测：

| 能力 | 实测结果 | 插件行为 |
|---|---|---|
| 函数、存储过程 | 支持 | 开启 |
| 物化视图 | 支持 | 开启 |
| 触发器 | 支持 | 开启 |
| 自定义聚合 | 支持 | 开启 |
| Range/List/Hash 分区、子分区及 `pg_partition` | 支持 | 使用 GaussDB 专用模型 |
| RLS、`pg_rlspolicies` | 五种兼容模式均支持 | 使用 GaussDB catalog 映射，隐藏不支持的 INSERT/WITH CHECK 项 |
| `ON CONFLICT` | 仅 PG 模式支持 | 按兼容模式显示 PostgreSQL 生成器 |
| 忽略唯一键冲突 | 五种模式支持 `ON DUPLICATE KEY UPDATE NOTHING` | 使用统一 GaussDB 插入方法 |
| `REPLACE` | 五种模式支持 | 使用 GaussDB Replace 方法 |
| `CREATE RULE` | 五种模式均拒绝 | 全部关闭 |
| 传统 PostgreSQL FDW UI | 未形成兼容闭环 | M 模式关闭 |
| generated column | 本机分布式部署拒绝；当前集中式 catalog 使用 `adgencol` | 仅探测到 `pg_attrdef.adgencol` 时开启 |
| `floatvector` / `boolvector` / HLL | 类型存在 | 专用 ValueHandler |

`INSERT ON CONFLICT` 的门控位于 PostgreSQL 扩展点，但默认实现仍返回 true，因此普通
PostgreSQL 的菜单和功能不变；只有明确返回 false 的兼容服务器会隐藏该生成器。

## Catalog、DDL 和数据类型

- PostgreSQL catalog 版本分支固定在兼容基线，GaussDB 特性由专用能力开关开启。
- `GaussDBTable` / `GaussDBTablePartition` 从 `pg_class.parttype` 和 `pg_partition`
  读取分区策略、分区键、边界和子分区。
- 分区边界按 PostgreSQL 数组文本解析，正确区分 catalog 的 `NULL`（MAXVALUE/DEFAULT）
  与字符串 `'NULL'`，并处理逗号、空格、引号和转义。
- 表 DDL 优先调用 `pg_catalog.pg_get_tabledef`，可保留 GaussDB 的分区、分布等原生
  子句；函数不存在、无权限或调用失败时回退到 DBeaver 通用 DDL。
- M 模式补充整数、无符号整数、year/datetime、binary/varbinary、blob/clob/nclob、
  tinytext/mediumtext/longtext、enum/set 等 JDBC 类型映射。
- 原生 JDBC 驱动使用自己的 `PGobject`、`PgArray`、异常和 COPY 包名时，通过服务器
  扩展 hook 选择；PostgreSQL 默认包名不变。
- RLS 仍复用 PostgreSQL 通用对象编辑器，但 catalog 查询、WITH CHECK/INSERT 能力和
  表启用状态由服务器扩展 hook 决定，因此 PostgreSQL 默认行为不变。

共享 PostgreSQL SQL 只修改了 GaussDB 507 解析器不接受、而 PostgreSQL 也能等价执行
的写法：域类型 CASE、索引列别名、锁冲突矩阵、字符串拼接、`EXCEPT` 和保留字别名。
这些查询已在 PostgreSQL 16 和 GaussDB 507 上分别执行。

## SSL、国密 TLS 和认证

GaussDB 数据源继承 PostgreSQL 的 SSL handler，标准 SSL 已支持：

- `ssl`、`sslmode`
- `sslrootcert`
- `sslcert`、`sslkey`
- `sslfactory`、`sslpasswordcallback`

原生 `gaussdbjdbc.jar` 的国密 TLS 可在连接的 Driver properties 中追加：

| 场景 | 追加属性 |
|---|---|
| 单向认证 | `sslgmcipher=ECC_SM4_SM3` |
| 双向认证 | `sslgmcipher=ECC_SM4_SM3` 或 `ECDHE_SM4_SM3`，以及 `sslenccert`、`sslenckey` |

CA、签名证书和签名私钥仍在 SSL 页配置为 `sslrootcert`、`sslcert`、`sslkey`；国密双向
认证的加密证书与加密私钥通过 `sslenccert`、`sslenckey` 配置。私钥需按服务端和驱动
要求转换成 PKCS#8 DER。详细参数以华为云官方的
[连接参数参考](https://support.huaweicloud.com/intl/zh-cn/distributed-devg-v8-gaussdb/gaussdb-12-1820.html)
和[国密 TLS 连接说明](https://support.huaweicloud.com/centralized-devg-v10-gaussdb/gaussdb-42-0086.html)为准。

当前测试环境没有开启 SSL/国密服务端，也没有服务器证书，因此本轮验证了标准/国密
属性传递路径和官方参数一致性，没有把“参数已支持”写成“真实国密握手已通过”。

## 原生备份、恢复和脚本

- `psql` → `gsql`
- `pg_dump` → `gs_dump`
- `pg_restore` → `gs_restore`
- `pg_dumpall` → `gs_dumpall`

GaussDB 507 实机验证了版本探测、help 参数、schema-only dump，以及本地 custom-format
dump/restore archive。GaussDB archive 工具必须使用本地路径；当 DBeaver 目标是工作区、
远程文件系统或其他虚拟文件系统时，任务会自动使用本地临时文件中转，成功后上传或在
恢复前下载，目录格式会递归复制，临时文件最后清理。

`gs_dumpall` 没有 PostgreSQL 的 `--no-role-passwords` 和 `--exclude-database`：

- 用户不导出角色密码时，导出文件在上传前逐行脱敏为 `PASSWORD DISABLE`。
- 工具无法表达任意数据库子集，因此只允许“全部数据库”或“仅全局对象”，对不完整子集
  在执行前明确报错，不静默扩大范围。
- 普通 PostgreSQL 仍使用原生参数和原有流式路径。

## 低权限行为

- Package source 和可选 catalog 遇到 `42501`、`42P01`、`42703`、`42883` 等可选
  元数据错误时返回空集合或通用 DDL，并避免反复查询。
- SQLState 为空时不再把所有异常都当成“可选元数据缺失”。
- 认证失败、网络失败和连接中断继续向上抛出，避免把真正的连接故障伪装成空对象。

## 验证结果

| 验证项 | 结果 |
|---|---|
| Java 21 编译：PostgreSQL、GaussDB model、GaussDB UI | 成功 |
| GaussDB 测试 bundle | 15 个测试类、50 个测试方法、0 失败；覆盖兼容模式、RLS/generated 能力、vector/HLL、复杂分区边界和全库备份口令脱敏 |
| XML 与 diff whitespace 检查 | 成功 |
| GaussDB 507.0.0 M：能力 DDL、分区 catalog、锁图、原生 DDL、dump/restore | 成功 |
| PostgreSQL 16：共享 metadata SQL、真实阻塞锁图 | 成功 |
| 全产品 199 模块打包 | 成功 |

全产品构建首次在下载 `org.eclipse.egit.ui:7.7.0.202606012155-r` 时等待较久；依赖缓存完成后，
使用 JDK 25 运行 Tycho 5.0.3，最终 199 个模块全部打包成功。项目 Java 编译目标仍为 21。

## 能力边界与待环境认证项

- 本机是 507 分布式部署；generated column 和 Package 编译被服务端明确拒绝。因此插件
  对 generated column 使用 catalog 能力门控，对 Package 限定为集中式 ORA 模式，代码
  路径已实现但仍需对应部署做端到端认证。
- 分布式节点拓扑、资源池、调度作业和 `dbe_perf` 仍可作为普通系统表/视图浏览；本轮
  没有另造一套与普通 schema 重复的专属管理控制台。
- 标准 SSL、国密 TLS 需要有证书并启用相应服务端协议的环境做真实握手测试。
- GaussDB 特有的所有高级 ALTER 变体和分布列图形化修改没有统一、安全的跨版本语法，
  当前通过 `pg_get_tabledef` 保真展示，并允许用户在 SQL 编辑器执行原生 DDL。

源码绝对路径：`/Users/lj/Documents/GaussDB/dbeaver`
