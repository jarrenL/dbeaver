# 重载函数搜索真库验证

## 范围

对应历史清单 9.5 对象搜索及函数管理，调用生产搜索实现，通过 JDBC 接口桥接到真实 507 集中式/分布式目录。桥接仅用于接入真实驱动，搜索 SQL 和结果引用由生产代码生成；不是搜索 GUI 验收。

## 构造与断言

在随机隔离 schema 中创建两个 SQL 语言同名函数：一个 numeric 参数返回 1，一个 varchar 参数返回 2。

1. 按精确函数名搜索，结果必须有两项，限定签名分别为 `schema.overload_probe(numeric)` 与 `schema.overload_probe(varchar)`，不能只按名称合并。
2. 按 numeric 签名删除一个函数。
3. 重新执行生产搜索，必须只剩 varchar 签名。
4. 实际以中文 varchar 参数调用剩余函数，确认返回 2，未被连带删除。
5. finally 清理随机 schema。两种部署各运行一次。

## 测试设计修正和限制

最终 `run-LdgHAq` 两个新增真库用例通过；整套新鲜报告 1409 项、1385 通过、24 跳过、0 失败/错误。临时授权角色已删除。[脱敏逐例报告](test-results-20260924-overload-search.json)。

- 首次 `run-waNabS` 使用 Oracle 风格独立 PL/SQL 函数，两种实例都在第二个 CREATE 阶段拒绝重载。**PL/SQL 重载搜索未验证，不能以本报告的 SQL 函数结果替代。**
- 随后 `run-Hh0kix` 改用 SQL 语言函数，两种实例成功创建并返回两个不同签名；测试错误地期待 `in numeric`/`in varchar`。实际目录签名省略隐式 IN，已修正预期；本轮未修改生产代码来迎合错误预期。
- 现有普通函数/过程搜索的大小写、注释、定义正文及跨 schema 删除隔离测试继续保留。序列、包、同义词不在目前 PostgreStructureAssistant 已声明的搜索类型中，需另行评估能力；不能将本轮结果扩展为所有对象搜索通过。
