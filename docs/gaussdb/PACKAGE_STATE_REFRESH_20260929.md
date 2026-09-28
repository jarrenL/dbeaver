# 包对象状态刷新与失败恢复

## 追加：部分目录字段读取失败

新增4项，覆盖第二行BODY的object_type/valid读取抛出42703或08006，第一行SPEC已正常读完。红测4项均失败：safeGet吞掉字段读取异常，部分SPEC状态仍被发布，或连接异常没有传播；BODY类型丢失时还可能错误推断BODY不存在。

修改为对这两个明确查询字段直接调用getString。42703继续按已有可选目录策略降级为UNKNOWN；08006明确抛出DBCException并保留原始SQL异常。SPEC/BODY有效性保持UNKNOWN，BODY最后已知存在标志保留，不把未读全的目录当成成功刷新。验证结果集、语句、会话全部关闭。SQL NULL本身仍按原有readState处理，不等同于读取异常。

日志：`/tmp/package-state-field-red-20260929.log`、`/tmp/package-state-field-green-20260929.log`。证据见[字段读取回归](evidence/PACKAGE_STATE_FIELD_REACTOR_20260929.json)。Docker本轮访问被拒，只有方法级故障注入结果，没有真库/GUI新增验收。

以下保留此前6项的实现和历史结果，不与追加回归重复累加。

追加后的本类**10/10通过、零跳过**。73模块中五个测试模块共**2648项：2474通过、173跳过、0失败、1错误**。唯一错误仍为RestTest本地网络权限拒绝，独立门禁拒绝整体通过；诊断构建继续收集结果不代表验收通过。

## 新增6项

对应包编译状态跟踪和对象刷新。通过真实DBUtils会话解析、生产GaussDBPackage.refreshObjectState，连接到模拟的数据库上下文及JDBC会话，初始包SPEC/BODY均为有效：

- 08006连接错误、28P01认证错误：原SQL异常继续传播，SPEC/BODY及整体有效性为UNKNOWN，不保留旧NORMAL。
- 42501权限不足、42P01目录缺失：保留既有兼容降级，不抛出源码编译错误，有效性为UNKNOWN。
- 以上4项各自恢复后重新查询，仅返回有效SPEC：整体NORMAL，BODY不存在，包OID73正确绑定。
- 目录无记录：清除之前存在的BODY标志，有效性UNKNOWN；不推断对象仍有效，也不把空结果单独当作对象删除证明。
- BODY无效且行排在SPEC前面：仍正确合成整体INVALID，SPEC保持NORMAL。

## 复现与修改

首次测试尝试使用静态替身，当前Mockito不支持，未到达业务逻辑；改为真实DBUtils解析模拟上下文。第二轮两个断言复现生产缺陷：连接和认证错误后旧NORMAL仍保留。另外两个错误来自对已设为抛异常的mock重新使用when调用；恢复设置改为doReturn，不计作产品缺陷。

生产修改：持久化包在开始刷新时先将SPEC/BODY有效性设为UNKNOWN，再查询目录。成功后按真实读取结果更新；失败时不能沿用旧有效性。未知情况下不将BODY的最后已知存在性当成已确认不存在。

日志：`/tmp/package-state-refresh-red-20260929.log`、`/tmp/package-state-refresh-red2-20260929.log`、`/tmp/package-state-refresh-green-20260929.log`。逐项结果见[测试证据](evidence/PACKAGE_STATE_REFRESH_REACTOR_20260929.json)，本类加入必跑清单。

最终本类**6/6通过、零跳过**。73模块中五个测试模块合计**2644项：2470通过、173跳过、0失败、1错误**。唯一错误仍为RestTest本地网络权限拒绝，独立门禁拒绝整体通过；门禁自测7/7通过。诊断构建只为继续收集后续模块结果，跳过及构建SUCCESS均不作为整体验收通过。

## 范围

这不是实际认证失败/服务器断线或集中式包编译端到端测试。未验证导航树图标刷新时机、所有可选目录差异或真实对象删除。生产方法级有效性变化不能替代GUI验收；本轮不增加这些通过结论。
