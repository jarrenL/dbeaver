# SQL 历史筛选回归

对应历史清单 3.11 的查询历史筛选场景。测试实际 `QMRegistryImpl.DefaultEventBrowser`，注入独立内存事件；不访问用户历史，也不把模型测试等同于界面验收。

## 输入、步骤及预期

| 自动化方法 | 输入与生产调用 | 预期 |
| --- | --- | --- |
| standaloneCustomFilterCannotBeBypassedByAbsentTypeAndTextCriteria | visible/hidden 两条事件；类型条件为空、无搜索文本，自定义过滤器仅接受 visible；读取生产游标 | 总数 1，且只返回 visible |
| combinedCriteriaEvaluateCustomFilterOncePerCandidate | query/USER 类型及中文搜索条件，自定义过滤器接受唯一事件 | 总数 1，自定义过滤器仅执行一次 |
| textSearchDoesNotDependOnDefaultLocale | 临时设置 tr-TR 区域，搜索 INSERT，事件文本为 insert into t values (1) | 匹配 1 条；finally 恢复原区域设置 |

## 发现与修复

`run-JCpXDW` 新增三项全部失败；该失败运行不能作为整体通过证据，下游未重跑的报告不计入本次结果。

生产入口原来只在有类型条件时应用自定义过滤器，并在文本搜索时再调用一次；改为独立过滤条件也进入过滤循环，搜索阶段不再重复调用。原来使用默认区域的 toLowerCase 后再作忽略大小写匹配，导致土耳其语 I 转换错误；改为直接将原文本交给现有的忽略大小写匹配函数。

## 产品能力边界

当前仓库中默认历史浏览器读取 `QMMCollectorImpl.getPastEvents()` 的内存快照；`QMRegistryImpl.getEventBrowser(false)` 尝试适配其他浏览器，未提供适配时回退默认实现。不能仅依据文本日志文件存在，就宣称 Query Manager 支持跨重启恢复历史。重启、固定/收藏以及历史页面操作需要按实际客户端能力另行验证，本次不计通过。

本轮修复是共享模型逻辑，影响不只 GaussDB；保留既有新到旧排序、类型组合及游标边界测试一并回归。未修改服务器配置或用户工作区。

## 验证结果

`run-V9xGeT` 全部报告时间已校验为本次运行：**1,333 项，1,309 通过，24 跳过，0 失败/错误**。本类 8 项全部通过，包含新增三项；运行包含已配置的集中式/分布式 507 真库回归，但历史过滤测试本身是模型层测试。[脱敏结果](test-results-20260924-history-filter.json)。回归临时 grantee 已删除。GUI 安装包尚未同步本轮模型修复。
