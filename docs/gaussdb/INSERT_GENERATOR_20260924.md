# 结果集INSERT生成测试

## 507 真库往返补充

`GaussDBHistoricalJdbcLiveTest` 新增集中式、分布式两个测试，各执行七类值：SQL NULL、空串、字符串 NULL、单引号、中文、换行和含 DROP TABLE 文本。调用实际 INSERT 生成器、真实字符串处理器和方言，将生成的 SQL 不经修改直接执行；带空格中文表名及列名、列顺序同时接受服务端检验。

独立参数化 JDBC INSERT 写入参考表，逐行比较结果；除空字符串按兼容模式与参数化写入对照外，其余值还与原始输入严格比较。验证两表均为七行，SQL 形状文本未执行为命令。每次使用随机隔离 schema，finally 删除，不依赖客户业务对象。

run-5SrGpQ：1178 项、1154 通过、24 跳过、零失败/错误；两个新增真库测试均通过。[脱敏报告](test-results-20260924-insert-roundtrip.json)。临时权限测试角色已清理。这是生成器与服务端的集成验证，不替代“生成 SQL”菜单的 GUI 验收；以下早期批次的未验证范围以本节新增证据为补充。

## 多行与选项补充

新增6项整句断言：空选择生成空文本；两行按选中顺序分别生成INSERT，含NULL的行不与另一行混值；自增列排除时列名和值一起移除，包含时一起保留；只生成可见列，不带入未显示属性；非紧凑模式仅改变分行，不改变列和值。

run-AIwX5G完整1176项/1152通过/24跳过/零失败错误，本类13项全部通过。报告[选项回归](test-results-20260924-insert-options.json)。临时grantee清理，无生产修改。仍未验证全部列都被排除、伪/隐藏属性标记、默认值、数值日期二进制、完整限定名及GUI/真库往返，不以6项模型测试代替这些场景。

对应历史清单9.4，新增GaussDBInsertFromDataTest七组用例。调用生产SQLGeneratorInsertFromData、真实GaussDBDialect、JDBCStringValueHandler及DBDAttributeBinding的限定列名逻辑；结果集数据和目录使用模拟对象。

输入覆盖SQL NULL、空字符串、字面字符串NULL、O'Reilly、中文、换行及含引号/分号/SQL关键字的文本。每项断言整个INSERT文本：带空格中文表名正确双引号引用，两个带空格列按可见顺序second/first生成，而非原属性顺序；对应值同步重排；'001'保留前导零；单引号加倍；NULL不加引号，字面NULL加引号。含SQL文本按字符串转义，未作为语句片段拼接。

测试接入过程保留记录：run-zE45vT因实现包未导出而发生7项NoClassDefFoundError，改为通过所属OSGi bundle加载实际类，不修改生产导出；run-O9sar1因mock的限定列名方法返回null导致7项断言失败，改调用真实方法，不改变SQL预期。这两轮是测试构造问题，未作为产品缺陷统计。

最终run-8VzP1S：1170项、1146通过、24跳过、0失败/错误；新增7项全部通过。报告见[脱敏结果](test-results-20260924-insert-generator.json)，临时grantee已删除。本轮无生产修改。

范围限制：未在本批把生成SQL执行到数据库，不把mock生成测试称作真库或GUI验收；空字符串在不同兼容模式的实际存储语义另验。多个选中行、自增列排除、隐藏/伪列、默认值、数值/日期/二进制、完整限定名、生成器界面仍需补充。
