# DBeaver GaussDB 插件 — 适配分析与改动记录

## 一、项目概述

DBeaver Community Edition 的 GaussDB 插件，基于 PostgreSQL 插件继承 + GaussDB 特化覆写。

| 插件 | 路径 | 说明 |
|------|------|------|
| org.jkiss.dbeaver.ext.gaussdb | plugins/ | 模型层，继承 PostgreSQL 插件 |
| org.jkiss.dbeaver.ext.gaussdb.ui | plugins/ | UI层，连接页/编辑器/配置器 |

架构策略：`parent="postgresql"` 继承 PG 插件，通过 `serverType` 扩展点注册 GaussDB 特化实现。

---

## 二、适配前的问题分析

### P0 — 会导致功能错误的

1. **PostgreServerGaussDB 大量 supports* 方法未覆写**
   - 只覆写了 6 个方法，其余走 PostgreServerExtensionBase 默认逻辑
   - Base 里的版本判断用 PG 版本号（如 `isServerVersionAtLeast(9,3)`），但 GaussDB 版本号含义和 PG 完全不同（GaussDB 8.x ≠ PostgreSQL 8.x）
   - 导致分区表、物化视图、函数定义读取、事件触发器等功能判断错误

2. **GaussDBDataSource.isServerVersionAtLeast() 版本号映射缺失**
   - 直接调用 super，未做任何 GaussDB 版本映射

3. **GaussDBPackage.getParentObject() 返回 null — Bug**
   - 导致导航树、依赖关系、DDL 生成等多处功能异常

4. **GaussDBProcedure DDL 生成有隐患**
   - `body = getBody()` 在字段初始化时调用，对象未完全构造
   - `pg_get_functiondef()` 结果用硬编码偏移截取 `res.substring(4, res.length() - 2)`，不同版本格式可能不同
   - Oracle 兼容模式下 CREATE PROCEDURE 语法和 PG 不同（AS/IS、DECLARE/BEGIN），未考虑兼容模式

5. **GaussDBSchema.isSystem() OID 范围判断不准确**
   - `isUtility()` 和 `isUtilitySchema()` 都硬编码返回 false
   - GaussDB 有自己的系统 schema（dbe_perf、dbe_pldeveloper、mls）

### P1 — 功能缺失但不报错

6. **GaussDBDialect 几乎是空壳**
   - 缺少 GaussDB 特有 SQL 关键词（PACKAGE, DBCOMPATIBILITY, VECTOR, HLL 等）
   - 缺少不同兼容模式下的语法差异
   - PostgreServerGaussDB 未覆写 `configureDialect()`

7. **GaussDBConstants 内容太少**
   - 只有 1 个常量 `GAUSSDB_M_COMPATIBLE_MODE = "M"`

8. **DBCompatibilityEnum 枚举不完整/有误**
   - cValue/dValue 的对应关系可能混淆
   - 缺少 null 安全处理
   - 未处理旧版 M 兼容模式

### P2 — 代码质量/可维护性

9. **ProceduresCache/FunctionsCache 代码重复**
   - GaussDBSchema 和 GaussDBPackage 各有一份 ProceduresCache，SQL 查询完全一样

10. **SchemaCache 重复**
    - GaussDBDatabase 内部有一个 SchemaCache 内部类，同时又有独立的 GaussDBSchemaCache 类

11. **GaussDBFunction 完全空壳**
    - 只继承 GaussDBProcedure 什么都没加

12. **GaussDBProcedureManager 和 GaussDBFunctionManager 代码几乎完全一样**
    - 172 行重复代码

13. **杂项代码问题**
    - `unusedMnitor` 拼写错误
    - `@NotNull` 注解在 boolean 返回类型上
    - License header 年份不一致（2024/2025/2026 混用）

---

## 三、改动记录

分支：`feature/gaussdb-compatibility`
Commit：`1f43c8035e`
改动：12 个文件，+577/-329 行

### P0 修复（5个文件）

| 文件 | 改动内容 |
|------|----------|
| GaussDBProcedure.java | 删掉 `body = getBody()` 字段初始化隐患；`pg_get_functiondef()` 结果不再用 `substring(4, len-2)` 硬截取，直接用完整输出；新增 Oracle 兼容模式 DDL 生成（AS/IS + BEGIN/END 替代 AS \ \| \| ... \ \| \|） |
| GaussDBPackage.java | `getParentObject()` 从返回 null 改为返回 schema；修复 `unusedMnitor` 拼写 |
| GaussDBSchema.java | `isSystem()` 改为精确判断（public 非系统，dbe_perf/dbe_pldeveloper/mls 是系统）；`isUtility()/isUtilitySchema()` 实现 information_schema + dbe_perf 过滤；移除 boolean 上的 @NotNull |
| PostgreServerGaussDB.java | 覆写 25+ 个 supports* 方法（分区/物化视图/事件触发器/RLS/外服务器/EXPLAIN/COPY 等），不再依赖 PG 版本号判断；新增 `configureDialect()` 注入 GaussDB 关键词；实现 `supportsJobs()` |
| GaussDBDataSource.java | 重写 `isServerVersionAtLeast()` — GaussDB 版本号和 PG 不对齐，直接返回 true，让特性判断由 PostgreServerGaussDB 的 supports* 方法决定 |

### P1 增强（3个文件）

| 文件 | 改动内容 |
|------|----------|
| GaussDBDialect.java | 添加 GaussDB DDL 关键词（PACKAGE/BODY/DBCOMPATIBILITY/VECTOR/HLL 等）、数据类型、函数名 |
| GaussDBConstants.java | 从只有 1 个常量扩充到完整定义：4 种兼容模式、连接默认值、驱动类名、URL 前缀、系统 schema、SQL 关键词数组 |
| DBCompatibilityEnum.java | 修正注释说明集中式/分布式值映射；添加 `getDValueByText()` 方法；处理旧版 M 模式；添加 null 安全 |

### P2 代码质量（4个文件）

| 文件 | 改动内容 |
|------|----------|
| GaussDBSchema.java | 提取 `buildProceduresLookupStatement()` 共享方法，ProceduresCache 和 FunctionsCache 不再各写一份相同 SQL |
| GaussDBPackage.java | 删除重复的 ProceduresCache 内部类，改为复用 schema 的 cache |
| GaussDBDatabase.java | 删除冗余的内部 SchemaCache 类（PostgreServerGaussDB 已用 GaussDBSchemaCache） |
| GaussDBFunctionManager.java | 从 172 行重复代码缩减为继承 GaussDBProcedureManager，只覆写 cache 和 create 方法 |

### 杂项修复
- License header 年份统一到 2026
- 修复 `unusedMnitor` → `unusedMonitor` 拼写
- 移除 boolean 返回类型上的 `@NotNull` 注解
- 清理不再需要的 import

---

## 四、未完成 / 后续待做

以下在分析报告中识别但本次未实施的项目：

| # | 优先级 | 内容 |
|---|--------|------|
| 1 | P1 | 驱动配置 — plugin.xml 中使用 PG 驱动而非 GaussDB 原生驱动，驱动 JAR 版本过旧 |
| 2 | P1 | 缺少测试插件 `org.jkiss.dbeaver.ext.gaussdb.test` |
| 3 | P1 | GaussDB 特有数据类型未适配（VECTOR、HLL 等缺少 DataTypeProvider/ValueHandler） |
| 4 | P1 | 备份/恢复工具未支持（gs_dump/gs_restore） |
| 5 | P3 | 连接页缺少兼容模式选择器、GaussDB SSL 证书配置 |
| 6 | P3 | GaussDB 特有系统对象未展示（DBE_PERF、DBE_SCHEDULER、pgxc_class 等） |
| 7 | P3 | Explain Plan 适配（分布式执行计划的 Node 级别信息） |
