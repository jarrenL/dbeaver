# 复合类型数组字段往返验证（2026-09-29）

## 范围

对应历史清单 11.1 数组/复合类型及 9.8 编辑值。真实生产 `PostgreStructValueHandler.bindParameter` 对 `JDBCComposite` 调用 `PostgreValueParser.generateObjectString` 后通过 `Types.OTHER` 绑定。本批直接测试该生成方法，再经实际复合字段解析和数组解析检查内容，**未执行真实 JDBC 写回或 GUI 编辑**。

## 缺陷与修改

原数组分支使用 `Arrays.deepToString` 后全局替换方括号、删除普通空格，且未引用字符串元素。测试复现中文空格丢失、字符串方括号被改写、分隔符/空串/NULL 文本等不满足原值保留要求。首轮 13 项新增测试中 12 项失败；数值格式对照原本通过。

改为递归生成数组：空值输出 NULL，嵌套数组保留层级，数值/布尔保持未引用形式，文本引用并转义。中间复跑仍有引号与反斜杠两项失败：外层 CSVWriter 的默认双引号转义与读取时的反斜杠规则不一致。显式使用反斜杠转义后，再补普通标量复合字段四项对照。

## 新增 17 项

| 场景 | 数量 | 检查 |
|---|---:|---|
| 中文空格、逗号、花/方括号、引号、反斜杠、空串、NULL 文本、tab/LF、扩展汉字 | 10 | 复合字段解析后与明确的预期数组字面量一致，再解析数组后与原字符串相等 |
| SQL NULL/空串/NULL 文本混合 | 1 | 值及类型保持区分 |
| 二维文本数组 | 1 | 形状、逗号、空串、NULL 文本保持 |
| 高精度数值数组 | 1 | 原有未引用格式及所有十进制位不变 |
| 普通复合字段的反斜杠、引号、组合转义、中文换行 | 4 | 每项与 null、空串同列往返，原值相等 |

## 验证结果

共享专项 **870/870**，真实 OSGi PostgreSQL 模块 **9 类 159/159**，均零失败、错误、跳过。包括既有解析器七项、此前数组边界/精度及执行计划测试；重叠批次不相加。必需类门控加入新测试，门控自测 7/7。

运行入口 `node scripts/run-shared-focused.mjs`；编译目录 `/tmp/shared-focused-zXr3p6` 交给 `run-existing-osgi.mjs --module=org.jkiss.dbeaver.ext.postgresql.test --all-module`。日志 `/tmp/composite-array-red-20260929.log`、`/tmp/composite-array-green-20260929.log`（中间两项失败）、`/tmp/composite-array-pass-20260929.log`、`/tmp/composite-array-osgi-20260929.log`。

## 未验收边界

没有最新整包、GaussDB 507 真库写回或 GUI 通过结论。自定义数组分隔符、原始类型数组、数组中的全部 JDBCComposite/DBDCollection 类型与对象释放、复合类型属性元数据及编辑器端到端仍需覆盖；本批不将复合类型整体标为完成。
