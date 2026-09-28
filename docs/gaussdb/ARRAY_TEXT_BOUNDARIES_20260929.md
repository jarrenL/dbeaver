# 数组文本边界验证（2026-09-29）

## 范围与缺陷

对应历史清单 11.1 数组数据类型及 9.8 数据编辑的序列化环节。`GaussDBValueHandlerProvider` 对普通类型调用父类，父类在 JDBC `Types.ARRAY` 时返回 `PostgreArrayValueHandler`。本次验证该真实生产处理器的显示序列化和 JDBC 参数绑定，不把模拟 JDBC 调用算成数据库写回成功。

生产处理器原先只对普通空格加引号，遗漏制表符、换行、回车、换页、垂直制表符。六个首尾空白用例红测失败，输出未引用的元素。按照 [PostgreSQL 数组输入输出规则](https://www.postgresql.org/docs/current/arrays.html#ARRAYS-IO)，元素首尾未引用的空白会被忽略，因此存在值改变风险。修复在既有引用判定中补齐这五类 ASCII 空白，不改变数值数组分支。

## 场景与断言

新增 `PostgreArrayTextBindingTest`，共 18 项：

| 场景 | 数量 | 断言 |
|---|---:|---|
| 首尾 tab、LF、CR、FF、VT 与中文 | 6 | NATIVE 输出含双引号；真实绑定方法向模拟 statement 的第 3 个参数传入完整字面量与 `Types.OTHER`，没有其他 statement 调用 |
| 空串、NULL/null 文本、逗号、花括号、空格、双引号、反斜杠 | 8 | 引用和转义保持正确 |
| SQL NULL、空串、NULL 文本、中文扩展汉字并列 | 1 | 四类值不混淆 |
| 空数组 | 1 | 输出 `{}` 而非 SQL NULL |
| 嵌套数组 | 1 | 递归引用保留空白及 NULL 边界 |
| 自定义分号分隔符 | 1 | 元素中的分号被引用、元素间使用分号 |

## 结果与复现

在测试工程执行 `node scripts/run-shared-focused.mjs`：修复前 814 项中 6 失败；修复后追加嵌套/分隔符对照，**816/816 通过、零跳过**。日志 `/tmp/array-whitespace-red-20260929.log`、`/tmp/array-whitespace-complete-20260929.log`。

使用上述编译目录 `/tmp/shared-focused-0vhyCv` 执行：

```sh
node scripts/run-existing-osgi.mjs /tmp/shared-focused-0vhyCv --module=org.jkiss.dbeaver.ext.postgresql.test --all-module
```

实际 OSGi 共 **5 类 98/98** 通过（含已有执行计划 80 项），零失败、错误、跳过；日志 `/tmp/array-osgi-20260929.log`。新测试类加入完整回归必需类门控，验证器自测 **7/7** 通过。各批有重叠，不相加。

## 未验收项

Docker API 当前被拒绝，因此没有 GaussDB 507 或原生 PostgreSQL 的实际写入/读回、结果网格编辑验收，也没有最新整包。复合类型、显式非 1 下界数组、精确数值数组读取仍需继续覆盖。本次未将整个数组场景组标为完成。
