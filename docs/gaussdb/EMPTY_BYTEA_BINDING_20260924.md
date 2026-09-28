# GaussDB 空 bytea 参数绑定修复

## 2026-09-28：流式绑定降级的完整性

新增4项实际文件测试：256/65537字节×驱动读取0/7字节后抛SQLFeatureNotSupportedException。生产BLOB进入字节数组降级分支，验证setBytes收到完整原文件、参数索引及原文件保留。

首轮因进度监视器查询未初始化OSGi环境而失败，修正为模拟监视器，未作为产品缺陷。红测`/tmp/shared-binary-fallback-red2-20260928.log`为652项中650通过、2失败；消费7字节的两项复现降级使用剩余流、丢失前缀。共享JDBCContentBLOB在字节数组降级前关闭旧流并重新打开存储流，绿测`/tmp/shared-binary-fallback-green-20260928.log`为652/652通过、0跳过、0失败。该类现22项。

依然使用真实文件/生产绑定器和模拟驱动，不证明厂商驱动实际发生过这种部分读取行为。此修复覆盖有storage的字节数组降级，带长度流式重载之间的切换、服务器Blob分支、超过2GB、不可重复读取源和GUI另验。共享修改仍需全量构建和真库验收。

## 2026-09-28：非空文件与绑定失败重试补验

新增6项：1、256、65537字节的真实中文空格文件，各执行正常绑定和模拟PreparedStatement读完流后抛SQLException再重试。实际TemporaryContentStorage、JDBCContentBLOB、GaussDBBinaryValueHandler参与；驱动替身逐字节校验完整数据（含0和255），验证参数编号3、无额外NULL/空值绑定、原SQLException cause、旧流在重试时关闭、新流从头读取、release关闭末次流且原文件内容不变。

初轮3项失败来自测试夹具未配置JDBCSession执行上下文，补齐正确JDBCExecutionContext模拟后通过，未作为生产缺陷统计。本轮无生产修改。显式编译当前handler/provider及JDBCContentBLOB，既有12项亦复跑；联合`/tmp/shared-binary-file-final2-20260928.log`为648/648通过、0跳过、0失败，该类18项。

不是实际驱动/网络流中断、批量执行或GUI保存；65537字节测试不是大LOB性能结论。Docker API复查仍permission denied，空二进制值界面验收未恢复，未提交未验收的GUI辅助脚本。本节不改变下方历史真库结论及其范围。

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

### 三类内容容器真库回归

后续 `GaussDBNativeLiveTest` 通过生产provider/handler分别绑定 JDBCContentBytes、包装真实SerialBlob的JDBCContentBLOB、包装实际零字节文件TemporaryContentStorage的JDBCContentBLOB。JDBC接口桥接转发到真实厂商PreparedStatement，不使用伪造查询结果。三次UPDATE后分别立即查询同一测试行，断言 `IS NULL=false`、`octet_length=0`（且wasNull=false）和getBytes空数组。释放内容对象后，原导入文件仍存在；最终才由测试finally清理自己的临时文件。

随后沿用所有原生备份/恢复检查点，确认写入后的空bytea能被备份并正确恢复。run-ZwEbec：1882项、1866通过、16跳过、0失败错误，见 [Blob和文件真库结果](blob-file-live-results-20260924.json)。本轮扩充既有方法，不虚增方法计数。独立查询review_native与临时调试成员关系均0。

该证据闭环集中式507的三类空内容参数绑定与恢复，不代替文件选择对话框、结果集保存、导入向导或其他兼容模式。

### BLOB 与文件内容容器补充

处理器现同时识别 JDBCContentBytes 和 JDBCContentBLOB，但必须先确认 `isNull=false` 且 `getContentLength()==0` 才使用十六进制空bytea参数。未知长度(-1)、非空值继续委托原内容绑定；读取长度异常直接传播，不把失败当空值。检查零长度BLOB不打开内容流。

新增7项组件测试：空Blob不读取流、未知长度委托、非空Blob委托、长度失败不绑定、NULL Blob不查询长度、真实零字节临时文件绑定且文件仍存在、真实缺失文件失败且没有执行任何参数绑定。文件测试使用实际TemporaryContentStorage与JDBCContentBLOB，但JDBCStatement是替身，因此不是文件导入GUI或真库Blob端到端证据。

中间5项回归run-Htewgj通过；加入真实文件2项后run-Gketea：1882项、1866通过、16跳过、0失败错误，见 [文件绑定回归](empty-file-binding-results-20260924.json)。同时启用缓存bytea真库备份恢复回归仍通过，临时调试授权独立查询为0。

- GUI结果集编辑、导入空二进制后的保存/再读取。
- 其他兼容模式与目标金融版本。
- 三类空内容真库路径已验证；其他内容容器、非空BLOB大流及全部LOB路径仍不能一概推断通过。
- 分布式当前仅验证绑定协议对照，生产handler真库桥接回归在集中式执行。

禁止把没有使用GaussDB provider的任意JDBC应用也标为已修复；厂商驱动直接setBytes(empty)行为仍存在。
