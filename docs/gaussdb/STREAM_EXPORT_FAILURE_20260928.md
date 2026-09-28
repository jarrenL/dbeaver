# 流式导出结束阶段异常传播

对应清单9.1、9.2导出异常与资源清理。生产 `StreamTransferConsumer` 的结束阶段存在三类问题，由新增 `GaussDBStreamExportFailureTest` 实际调用生产方法复现：

1. PrintWriter吞掉底层写入IOException，仅设置error标志；导出站点flush原先不检查该标志。
2. closeExporter记录并吞掉processor.dispose异常。XLSX等导出器在dispose时最终写出文件，异常不能被当作正常结束。
3. 后续输出流关闭异常可能替代原始导出错误。

修复后，站点flush检查PrintWriter错误标志；closeExporter保留首个异常、后续异常作为suppressed，仍尝试dispose及关闭输出。结束后清空处理器、writer和输出流引用，重复清理不再次写出或关闭同一输出流。非IOException的dispose失败包装为IOException并保留cause。

## 专项验证

首轮3项全部失败（`/tmp/stream-failure-red.log`）。修复后扩展6项：dispose错误、PrintWriter真实Writer故障、主错误与关闭错误、成功清理、flush/dispose/close三重错误顺序、非IO错误原因保留；重复清理检查不重放。最新 `/tmp/stream-failure-extended.log`：6项全部通过、0跳过、0失败；编译目录 `/tmp/stream-failure-focused-mgKvsL`。最后仅重命名成功场景方法，使名称准确描述验证范围。

测试通过反射调用生产私有结束方法，处理器与OutputStream为模拟对象，PrintWriter写失败用实际自定义Writer触发。不是实际磁盘空间耗尽，不是完整传输任务或GUI失败弹窗。

独立测试仓 `scripts/run-stream-failure-focused.mjs` 直接编译当前消费器与新测试类，使用本机已有只读依赖；非完整Tycho回归，不合并到历史1882项统计。完整构建、真实导出任务错误通知与最终产品仍待验证。

## 仍需跟进

表头/表尾异常、分卷时的关闭错误、压缩流结束异常等其他路径不在本轮修复范围。不能据此宣布全部导出错误已闭环。共享传输消费器受影响，不仅是GaussDB。
