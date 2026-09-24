# GaussDB 空 bytea 参数绑定修复

## 复现与原因范围

集中式 A 兼容测试库与分布式 507 均用同一连接创建临时表进行独立对照：

| 写入方式 | SQL IS NULL | octet_length | JDBC getBytes |
|---|---|---:|---|
| 服务端 substring(decode('00','hex') from 1 for 0) | false | 0 | 长度0 |
| 厂商驱动 setBytes(new byte[0]) | true | NULL | null |
| setObject("\\x", Types.OTHER) | false | 0 | 长度0 |

临时表随连接关闭清理。该现象证明空字节绑定路径会改变数据语义，但不能仅凭结果确定厂商驱动内部还是服务端协议处理的全部责任。

## DBeaver 修复

GaussDBValueHandlerProvider 仅为 bytea 选择新的 GaussDBBinaryValueHandler。它继承通用内容处理器，只将 JDBCContentBytes 的非NULL零长度值绑定为参数化的十六进制空bytea输入 `\x`，JDBC类型为 OTHER。该输入不是空字符串，因此避开空字符串转NULL的行为。没有拼接SQL。

非空二进制沿用 setBytes，SQL NULL 沿用 setNull，其他数据库provider不变。原始byte[]到JDBCContentBytes的通用转换路径未修改。

## 验证

新增5项单测：空值绑定和1-based参数编号、非空字节路径、SQL NULL区分、SQLException传播、大小写BYTEA的provider选择。首次完整回归run-ikG2Vl通过。

随后GaussDBNativeLiveTest不再用服务端表达式绕开写入问题：通过生产provider取得处理器，调用生产bindValueObject，经JDBC接口桥接到真实厂商PreparedStatement写入空bytea。备份前先断言非NULL、零长度，再执行普通SQL、自定义归档、结构/数据分离和错误后重试的恢复验证。该测试没有伪造数据库读回值。

完整回归run-CG6GMY：1875项，1859通过、16跳过、0失败错误，见 [结果](empty-bytea-binding-results-20260924.json)。独立SQL确认review_native为0、临时调试成员关系为0。随机测试角色及合成备份由运行器清理。最后仅补充新文件的许可证注释，不改变测试过的执行逻辑。

## 仍需覆盖

- GUI结果集编辑、导入空二进制后的保存/再读取。
- 其他兼容模式与目标金融版本。
- JDBCContentBLOB等其他内容容器；本修复直接处理缓存的JDBCContentBytes，不把所有LOB路径算作已验证。
- 分布式当前仅验证绑定协议对照，生产handler真库桥接回归在集中式执行。

禁止把没有使用GaussDB provider的任意JDBC应用也标为已修复；厂商驱动直接setBytes(empty)行为仍存在。
