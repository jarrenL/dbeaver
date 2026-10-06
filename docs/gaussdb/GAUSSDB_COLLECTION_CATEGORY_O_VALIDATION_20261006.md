# GaussDB 集合类型元数据兼容验证（2026-10-06）

## 问题

在麒麟 Linux GUI 中使用 GaussDB 原生 JDBC 驱动连接 GaussDB 507 分布式实例时，连接测试成功，但加载类型元数据会反复记录：

```text
Invalid type category [O] - No enum constant PostgreTypeCategory.O
```

原因是 GaussDB 的集合类型使用 `pg_type.typtype='o'`、`typcategory='O'`，而 PostgreSQL 公共模型没有声明 `O` 类别。异常会导致这些类型不能按集合元数据正常分类。

## 真库证据

在现有 GaussDB 507 分布式实例查询 `pg_catalog.pg_type`，确认下列内置类型均使用类别 `O`，且 `typelem` 指向有效元素类型：

| Schema | 类型 | `typtype` | `typcategory` |
| --- | --- | --- | --- |
| `dbe_sql` | `blob_table` | `o` | `O` |
| `dbe_sql` | `date_table` | `o` | `O` |
| `dbe_sql` | `desc_tab` | `o` | `O` |
| `dbe_sql` | `number_table` | `o` | `O` |
| `dbe_sql` | `varchar2_table` | `o` | `O` |

## 修复

- 在 `PostgreTypeCategory` 中加入 GaussDB 集合类别 `O`。
- `PostgreDataType` 对类别 `O` 使用与已有集合类别 `F` 相同的元素映射：存在元素 OID 且变长时映射为 JDBC `ARRAY`，否则映射为 `OTHER`。
- 保留类别 `F` 的既有行为，避免影响已有数据库兼容性。
- 新增 mock 元数据回归，并使用原生 JDBC 对 507 真库目录进行断言。

## 验证结果

| 验证层 | 结果 |
| --- | --- |
| `PostgreCollectionMetadataTest` | 1/1 通过，验证 `O`、`F` 及无元素类型分支 |
| `GaussDBHistoricalJdbcLiveTest` 定向真库执行 | 170 项，136 通过、34 环境跳过、0 失败；新增 `realGaussCollectionCategoryIsRecognized` 通过 |
| 完整回归第一轮 `run-KhbNpo` | 2776 项，2758 通过、18 跳过、0 失败、0 错误 |
| 完整回归第二轮 `run-CXfA4h` | 2776 项，2758 通过、18 跳过、0 失败、0 错误 |
| 重复性校验 | 2776 个测试身份和结果完全一致 |
| 清理检查 | 分布式、集中式实例中本轮生成的动态测试 Schema 均为 0 |

跳过项未计入通过。剩余 18 项分别受服务器明确不支持的语法、专用 TLS/mTLS/GB18030/空集群环境以及上游 SVG 测试禁用条件约束，详见对应回归结果中的跳过原因。
