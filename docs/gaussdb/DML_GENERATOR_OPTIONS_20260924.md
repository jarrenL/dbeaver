# DML 生成选项与声明默认值

## 数值与布尔边界补充

新增十项，实际INSERT生成器配合真实JDBCNumberValueHandler/JDBCBooleanValueHandler：Long上下界、38位BigInteger、38位高精度BigDecimal、极小小数、负数末尾零、数值NULL及布尔true/false/NULL。逐字比较生成SQL，确保数值不经double损失精度、不变字符串，固定关闭科学记数格式时极小值输出完整小数，字符串键001仍加引号保留前导零。

run-TIGt38全1217项、1193通过、24跳过、零失败/错误；十项均通过。[类型脱敏报告](test-results-20260924-dml-types.json)。临时权限测试角色已清理，无生产修改。这里验证生成链路，不替代数值服务端范围验证、数据网格格式显示或科学记数格式开启后的验收。

run-Dg3jMg：1207项、1183通过、24跳过、零失败/错误；新增六项均通过。[脱敏报告](test-results-20260924-dml-options.json)。临时权限测试角色已清理，本批无生产修改。

对应历史清单9.4，使用实际结果集INSERT、UPDATE、DELETE生成器，补充以下模型断言。

1. 三种生成器在完整限定名选项开启、紧凑格式关闭时，保留实体提供的 `"业务 schema"."订单 表"`，整句核对换行、列和值以及WHERE条件。
2. 结果列缺少匹配绑定时，实体默认值 `42`、`'O''Reilly'`、`CURRENT_TIMESTAMP` 分别原样进入INSERT与UPDATE，不再添加字符串引号，不读取缺失列的单元格。

共六个参数化场景，默认值三项各断言两种生成器。使用模拟实体提供限定名和默认表达式，因此不宣称验证schema名称的生成算法、实际数据库默认值元数据读取或GUI选项操作；默认表达式类型匹配也不属于本批断言。真实SQL执行另见INSERT/UPDATE/DELETE真库报告。
