# 客户端进度监视器与阻塞栈验证

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
