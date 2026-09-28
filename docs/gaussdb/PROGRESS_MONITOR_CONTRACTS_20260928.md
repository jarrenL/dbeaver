# 客户端进度监视器与阻塞栈验证

## Eclipse写接口补验（后续）

新增1/2/3层ProxyProgressMonitor三项执行，底层为真实NullProgressMonitor的spy。经IProgressMonitor设置取消true后DBR接口必须读到true，再设置false后读到false；中文扩展字符任务名、小数进度0.25和零进度均按原值转发，不转成整数worked。

红测`/tmp/progress-proxy-red.log`为8项中5通过、3失败，全部首先复现取消true被空实现丢弃；任务名和小数进度也存在空实现，但没有将其声称为本轮独立红测失败。修复三个IProgressMonitor写接口委托getNestedMonitor()后，`/tmp/progress-proxy-green.log`为8/8通过、0跳过、0失败，包含此前5项。

写接口转发已完成指定组件验证，不能据此证明DefaultProgressMonitor内部累计小数状态、OSGi调度、GUI取消按钮、JDBC中断及工作台关闭已全部通过。特别是“设取消标志成功”不等于运行中SQL已经停止。

对应历史清单6.3进度工具及9.7任务控制的共享基础设施，不将TPDSS进度标签工具直接照搬为独立GaussDB功能。

## 五项测试

1. 单层任务结束：通知底层done，不再次beginTask启动已结束任务。
2. 双层任务结束：子任务结束后恢复父任务名称、总量、子步骤及累计进度；父结束后不重启。
3. 三层任务结束：逐层恢复正确的父状态，不串用已弹出任务状态。
4. 两个阻塞对象后进先出；返回的是独立列表快照，调用方清空快照不破坏实际阻塞栈。
5. SubTaskProgressMonitor将beginTask转为父subTask；子done不结束父任务，进度转发，底层已取消状态向上传递，nested monitor身份保留。

用例直接实例化生产DefaultProgressMonitor/SubTaskProgressMonitor，底层Eclipse IProgressMonitor使用Mockito验证调用顺序。取消测试只证明读取既有取消状态，不证明Statement.cancel或取消按钮能中止数据库查询。

## 发现与修复

红测`/tmp/progress-contract-red.log`：5项中2通过、3失败。同一根因：DefaultProgressMonitor.done弹出最后状态后立即恢复该状态，而不是仍在栈中的父状态；最外层done也重新启动自身。

修复为先移除已结束状态，仅当仍有父状态时恢复新的栈顶。绿测`/tmp/progress-contract-green.log`：5/5通过、0跳过、0失败。未改动阻塞取消机制或工作台关闭策略。

## 边界

测试仓`scripts/run-progress-focused.mjs`显式编译三个当前生产监视器和测试，属于组件验证。完整OSGi任务调度、并发进度调用、ProxyProgressMonitor的IProgressMonitor写接口、真实取消、工作台关闭与Linux进度条呈现仍待验证。本结果不能证明所有长任务取消已闭环，也不与旧全量1882项统计相加。
