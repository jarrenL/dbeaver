# 连接URL选择路径验证

对应历史清单1.2的驱动工厂与连接URL场景。本轮从仅验证formatHosts，补到实际GaussDBDataSourceProvider.getConnectionURL入口；认证、驱动与配置对象为Mockito替身，不进行网络连接。

## 新增7项及测试步骤

| 场景 | 输入与断言 | 结果 |
| --- | --- | --- |
| 原生驱动结构化URL | 原生驱动类、多CN/IPv6及混合显式端口，验证gaussdb协议和数据库名，不改配置URL | 通过 |
| PG驱动结构化URL | 同样输入，验证postgresql协议 | 通过 |
| 手工URL | URL模式保留完整多主机及sslmode参数；不得读取旧主机字段或驱动类重新构造 | 通过 |
| 认证模块优先及失败 | 非空认证URL直接返回；随后同一入口认证抛DBException，必须传播同一异常，不降级使用配置URL或主机 | 通过 |
| 认证返回null | 回退到手工URL，不重新构造 | 通过 |
| 认证返回空串 | 同上 | 通过 |
| 主机包含查询组件 | 结构化主机含?sslmode=disable，实际provider包装IllegalArgumentException为DBException，不改URL | 通过 |

## 结论与范围

新增7项通过，GaussDBDataSourceProviderTest共14/14，零跳过，加入必跑类清单。本轮未修改生产代码。

73模块诊断回归五个测试模块共2669项：2495通过、173跳过、0失败、1错误。错误为RestTest网络权限拒绝；独立门禁拒绝整体通过，门禁自测7/7通过。计数包含上游测试，不是7000条历史用例迁移比例。

不代表认证插件真实认证成功、驱动接受全部URL、CN故障切换或连接向导GUI验收。Zenith/OLAP等不同产品协议不从本轮URL测试推导为支持。真实驱动连接、目标金融版本与GUI仍按各自验收项处理。

日志：`/tmp/provider-routing-20260929.log`；[脱敏逐项证据](evidence/PROVIDER_URL_ROUTING_REACTOR_20260929.json)。
