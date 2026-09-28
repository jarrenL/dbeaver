# 网络配置共享基类回归

## 后续：损坏凭据记录保护

新增13项：10种非法记录（非对象根、错误handlers容器/成员、错误ID/用户名/密码类型、错误properties容器/嵌套值及截断JSON），3种合法空记录（无handlers、null、空数组）。非法记录测试还验证全部既有handler凭据不变、异常堆栈不含模拟私密标记、同实例读取修正记录后可重试。

红测日志`/tmp/shared-network-malformed-red-20260928.log`：431项中原421通过，新增10项失败，体现接受错误结构或异常类型不符合预期。生产DBWNetworkProfile现在先解析并验证所有handler字段，完成校验后才修改运行时凭据；解析异常转换为不携带原文和解析cause的DBException，凭据控制器本身的DBException保持原样。

绿测日志`/tmp/shared-network-malformed-green-20260928.log`：434/434通过，0跳过、0失败、0中止。该类现19项，其余415项复跑。合法空记录仍不改变既有凭据；未知handler不创建的原有测试继续通过。

保护针对当前JSON记录，不证明旧版逐键凭据加载的事务性，也不证明并发更新、重复JSON键或实际密钥库损坏恢复。不是SSH/TLS或GUI验收。

## 范围与方法

对应历史清单3.12连接配置、3.13凭据。验证DBPConfigurationProfile属性集合复制修复对DBWNetworkProfile的影响；使用真实网络配置及handler模型，模拟描述符和凭据控制器，不建立SSH/TLS连接。

| 场景 | 方法与断言 | 执行数 | 结果 |
| --- | --- | ---: | --- |
| 全局/指定主体隔离 | 校验凭据键、来源及全局标记；设置后清空输入集合，配置保留原值；编辑配置不修改输入 | 2 | 通过 |
| handler替换 | 同ID替换SSH项，SSL对象及列表顺序不变，旧SSH凭据不被修改，未知ID返回空 | 1 | 通过 |
| 多handler凭据往返 | 生产序列化保存SSH/SSL，再恢复到仅配置SSH的目标；中文用户名、密码和安全属性恢复，不凭空增加SSL | 1 | 通过 |
| 控制器读写失败 | 注入DBException，验证原异常身份、handler身份和凭据不变，不额外flush | 2 | 通过 |

## 执行证据

主仓基线b15ed6bf06，增加NetworkProfileContractsTest；测试仓run-shared-focused.mjs显式编译DBWNetworkProfile、DBWHandlerConfiguration及该测试。联合18类421项通过，0跳过、0失败、0中止。日志：`/tmp/shared-network-profile-20260928.log`。本轮新增6项，原415项复跑，不累加历次执行数。

正式回归门控要求该类实际出现且全部通过，缺失或跳过均不能算验收。

## 限制

未验证真实凭据文件、操作系统密钥库、SSH/SSL协议握手、连接配置界面及损坏网络凭据内容的处理。模拟控制器成功不能证明磁盘加密。网络配置的flush由调用方负责，本测试不推断调用方已完成持久化。此轮不修改生产逻辑，也不能代替完整Tycho/OSGi构建。
