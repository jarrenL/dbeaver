# 结果集 DELETE 生成回归

最终 run-3k7fre：1184 项、1160 通过、24 跳过、零失败/错误；新增三项全部通过。[脱敏报告](test-results-20260924-delete-generator.json)。临时权限测试角色已清理。该修复位于共享 SQL 生成器，非 GaussDB 专属分支；实际 PostgreSQL GUI 回归未因此视为完成。

对应历史清单 9.4 的 SQL 生成及无主键对象边界。测试调用实际 `SQLGeneratorDeleteFromData`、GaussDB 方言和字符串处理器；目录及结果单元格使用模拟对象。不是已执行删除的真库或 GUI 验收。

## 已复现问题

无默认行标识时，`SQLGeneratorResultSet.getKeyAttributes` 返回不可修改的空列表。DELETE 生成器试图向它添加可见属性，抛出 `UnsupportedOperationException`，无法生成 SQL。首轮 run-gBLYKJ 明确落在 `SQLGeneratorDeleteFromData.generateSQL` 的列表追加位置。

修复：无键回退分支创建局部 `ArrayList`，不修改原行标识持有的集合。未改变既有“以可见列作为条件”的产品语义。这种条件不保证唯一：重复行可能匹配多行，生成脚本必须核对条件和影响行数；本修复不作唯一删除保证。

## 新增断言

- 无键：按可见列顺序生成两个条件，包含单引号转义。
- 单键：只以键列为 WHERE 条件，普通值列不混入条件。
- 复合键：保留键顺序，NULL 使用 `IS NULL` 而非 `=NULL`。

三项均校验完整 SQL，表名和列名包含中文或空格。首轮另两项因测试未初始化生成器对象列表而失败，属于测试装配问题，已补正常 `initGenerator` 调用，与无键生产缺陷分开记录。

空键对象、无可用可见列、多行选择、隐藏/伪列回退及真实执行仍待追加，不以这三项宣称整个 DELETE 链路完成。
