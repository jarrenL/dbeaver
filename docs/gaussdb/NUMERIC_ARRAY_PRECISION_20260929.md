# NUMERIC 数组解析精度验证（2026-09-29）

## 缺陷与修复

历史清单 11.1 要求高精度数值和数组类型正确展示。生产 `PostgreValueParser.convertStringToValue` 原先将 JDBC `Types.NUMERIC` 与 `Types.DOUBLE` 一起使用 `Double.parseDouble`。此方法由数组元素转换路径调用，不应将此问题描述为所有 JDBC 数值列都会丢失精度。

新增测试确认：`9007199254740993` 变成 `9.007199254740992E15`；高精度小数及保留尾零的值不能维持精确十进制表示，`1E+400` 被转成无穷值。七项有限值红测均失败。

修复仅将有限 `Types.NUMERIC` 转为 `BigDecimal`，保留十进制有效位及 scale。NaN、Infinity、+Infinity、-Infinity 保持原有 Double 表示；FLOAT/DOUBLE 分支不变；无法解析的输入保留原文本。没有改动 DECIMAL 或其他数据处理器。

## 测试范围

新增 `PostgreNumericPrecisionTest` 共 15 项：

- 七种有限值分别直接调用生产转换入口，再通过真实数组解析入口检查元素：正负 `2^53+1`、38 位精度小数、极小小数、`123.4500`、`0.0000`、`1E+400`。断言与期望 `BigDecimal` 完全相等（含 scale）。
- 四种非有限值的现有表示。
- 三种非法数字保留原文本。
- FLOAT/DOUBLE 仍保持 Float/Double 返回类型和值。

会话与数据类型元数据为 Mockito 替身；转换器和数组解析逻辑为实际生产源码。原有 `PostgreValueParserTest` 七项也重新编译并在真实 OSGi 下通过，覆盖已有数组/嵌套/复合值路径，不将这些旧测试算作新增用例。

## 结果

| 验证 | 结果 |
|---|---|
| 修复前共享专项 | 830 项，823 通过、7 失败、0 跳过 |
| 修复后共享专项（增加 +Infinity 对照） | 831/831，0 失败/跳过 |
| PostgreSQL OSGi 回归 | 7 类 120/120，0 失败/错误/跳过 |
| 完整回归必需类门控自测 | 7/7 |

日志：`/tmp/numeric-precision-red2-20260929.log`、`/tmp/numeric-precision-green-20260929.log`、`/tmp/numeric-osgi-20260929.log`。首轮编译因未包含既有测试依赖的 `TestPreferenceStore` 而失败，加入其源码后重新执行，未将该编译失败认定为产品缺陷。

共享专项运行命令 `node scripts/run-shared-focused.mjs`；其编译输出 `/tmp/shared-focused-dTJDT2` 用于 `run-existing-osgi.mjs --module=org.jkiss.dbeaver.ext.postgresql.test --all-module`。该类加入必需类门控。

## 尚未完成

Docker API 本轮仍拒绝访问，未执行 GaussDB 507 真库数组写入读回、结果网格编辑与重新保存；本次不是完整构建或整包验收。复合字段全部类型、非 1 数组下界、SQL NULL 的完整转换链路仍需补充。不得把本批结果与包含相同用例的旧批次累加。
