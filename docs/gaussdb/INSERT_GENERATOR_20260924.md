# 结果集INSERT生成测试

对应历史清单9.4，新增GaussDBInsertFromDataTest七组用例。调用生产SQLGeneratorInsertFromData、真实GaussDBDialect、JDBCStringValueHandler及DBDAttributeBinding的限定列名逻辑；结果集数据和目录使用模拟对象。

输入覆盖SQL NULL、空字符串、字面字符串NULL、O'Reilly、中文、换行及含引号/分号/SQL关键字的文本。每项断言整个INSERT文本：带空格中文表名正确双引号引用，两个带空格列按可见顺序second/first生成，而非原属性顺序；对应值同步重排；'001'保留前导零；单引号加倍；NULL不加引号，字面NULL加引号。含SQL文本按字符串转义，未作为语句片段拼接。

测试接入过程保留记录：run-zE45vT因实现包未导出而发生7项NoClassDefFoundError，改为通过所属OSGi bundle加载实际类，不修改生产导出；run-O9sar1因mock的限定列名方法返回null导致7项断言失败，改调用真实方法，不改变SQL预期。这两轮是测试构造问题，未作为产品缺陷统计。

最终run-8VzP1S：1170项、1146通过、24跳过、0失败/错误；新增7项全部通过。报告见[脱敏结果](test-results-20260924-insert-generator.json)，临时grantee已删除。本轮无生产修改。

范围限制：未在本批把生成SQL执行到数据库，不把mock生成测试称作真库或GUI验收；空字符串在不同兼容模式的实际存储语义另验。多个选中行、自增列排除、隐藏/伪列、默认值、数值/日期/二进制、完整限定名、生成器界面仍需补充。
