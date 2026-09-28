# 执行计划统计字段与缺失节点类型验证

对应历史清单7.1–7.2，使用GaussDB复用的PostgrePlanNodeXML/PostgrePlanNodeBase生产解析器。输入为独立构造的XML，不冒充服务器实测计划。

## 新增场景

1. 父节点与子节点各自保留Shared-Hit-Blocks、Shared-Read-Blocks、Local-Read-Blocks等BUFFERS属性，不串用父统计；0值、大于32位的计数4294967296和小数I-O-Read-Time原文可通过生产属性接口读取，缺失统计返回null。
2. Actual-Rows=0优先于Plan-Rows=500，Actual-Loops=0和耗时0保留；缺失cost和buffer字段不伪造为0。
3. 缺失Node-Type和空Node-Type两种输入应使用DEFAULT节点类别，不崩溃。
4. 默认桌面区域设为tr-TR时Index Scan和Nested Loop分类稳定，测试finally恢复原区域。

新增5项执行，加上原有父子结构、并行前缀和估算字段4项，共9项。

## 发现与修复

红测 `/tmp/plan-metrics-red.log` 为8项中7通过、1失败：缺失Node-Type在getNodeKind调用toLowerCase时空指针。修改为对空值使用空字符串、使用Locale.ROOT转换。区域设置测试在修复后加入，未将其标为独立红测复现。

绿测 `/tmp/plan-metrics-green.log`：9/9通过、0跳过、0失败。测试仓脚本 `scripts/run-plan-focused.mjs` 直接编译当前两个生产类和测试，使用已有只读依赖；不是完整Tycho构建，不与历史1882项相加。

## 边界

只证明标量统计属性和指定节点结构解析，不证明服务器实际生成BUFFERS、多DN统计聚合、嵌套Worker统计结构或GUI图表展示。缺失节点类型输入不会崩溃不代表残缺计划语义完整。共享PostgreSQL解析器受影响，完整PG/GaussDB和保存计划回归仍需执行。
