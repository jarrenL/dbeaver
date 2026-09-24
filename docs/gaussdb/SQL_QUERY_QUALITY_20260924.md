# 查询列、插入列与排序列静态检查

对应历史清单5.2的三类规则，属于可读性和结构变更风险提示，不是SQL非法判定，也不会阻止或改写查询。实现沿用SQL编辑器语义诊断通道，使用WARNING及英文/简体中文文案。

## 实现范围

|规则|诊断位置|反例及边界|
|---|---|---|
|SELECT显式列名|投影项的 `*` 或 `t.*`|COUNT(*)、乘法、字符串及注释不报告；嵌套SELECT各自检查|
|INSERT显式列名|缺少insertColumnList的VALUES/SELECT/DEFAULT VALUES源|有显式列名不报告；不读取目标表也不自动猜列序|
|ORDER BY避免序号|sortKey中的columnIndex|列名/别名不报告；OVER内部数字是常量，不作为外层序号排序报告|

新增INSERT语法分支，让不带目标列列表的VALUES、SELECT、DEFAULT VALUES进入现有语义模型；原带列列表分支保留。告警基于语法树，不能用注释/字符串里的关键字触发。窗口判断沿父节点向上直到所在querySpecification，避免把窗口内嵌套子查询的排序混同窗口排序。

`SQLQueryQualityDiagnosticTest` 包含32组实际解析/诊断断言，覆盖上述正反例、注释、别名、子查询、批量VALUES、窗口函数。对每条目标告警检查WARNING及源码区间。测试不调用真实数据库，也不等同GUI显示验收。

## 已执行结果

32项新增组件执行通过。完整回归 `run-ES4sM0` 通过后，修正文案避免将DEFAULT VALUES错误描述成必然依赖列顺序；最终 `run-RTgXAD` 共1742项、1719通过、23跳过、0失败/错误。机器结果见 `sql-query-quality-results-20260924.json`。

集中式和分布式507分别在单独gsql会话执行事务内临时表场景：`a integer DEFAULT 7`，无列名VALUES插入1、DEFAULT VALUES插入7、INSERT SELECT插入2，`SELECT * ... ORDER BY 1`均得到1/2/7，COUNT与SUM均为3/10。随后ROLLBACK，退出会话；独立pg_class检查同名对象两库均为0。该验证只证明SQL有效和结果正确，不证明DBeaver界面告警。

初次临时夹具的ON COMMIT DROP被分布式拒绝（仅支持PRESERVE ROWS/DELETE ROWS），该次不算通过。后续使用普通会话临时表并回滚，没有改服务端配置来绕过限制。集中式首次`-c`批量调用仅展示最后ROLLBACK，未作为结果证据；重新按逐条stdin执行取得上述完整结果。

## 尚待验证（本组仍为部分覆盖）

- GaussDB连接下三条规则的GUI显示、编辑后清除和多告警共存。
- 星号展开/EXCEPT等方言扩展、更多窗口语法、不完整输入与补全交互。
- 本次修改作用于共享语义识别器，需关注其他数据库方言的编辑体验；并非仅GaussDB独有入口。
- LIKE前导通配符、重复表达式/CASE、EXISTS筛选、NOT IN可空性等历史规则仍独立待补。
