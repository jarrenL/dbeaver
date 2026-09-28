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

分卷完整生命周期、传入已有任务错误时的清理等其他路径仍需跟进。压缩流结束方法的验证见下节，不等同完整压缩导出任务。不能据此宣布全部导出错误已闭环。共享传输消费器受影响，不仅是GaussDB。

## 表头/表尾异常补验

新增4项分别注入表头、表尾的DBException与IOException。红测10项中7通过、3失败：表头DBException和表尾两种异常被吞掉；表头IOException原本传播正常。日志 `/tmp/stream-header-footer-red.log`。

修复表头/表尾为传播DBCException并保留cause；新增finishFile将表尾和资源结束统一处理，表尾失败仍调用closeExporter，后续关闭失败不覆盖主错误。finishTransfer原正常结束分支调用该方法，把异常交给已有错误事件处理路径，而不是提前漏掉清理。

另补2项生产结束方法测试：表尾+关闭双故障仍dispose并保留主次错误，重复结束不重放；成功路径严格验证表尾、dispose、flush、close顺序。最新 `/tmp/stream-header-footer-green.log` 为12项全部通过、0跳过、0失败，编译目录 `/tmp/stream-failure-focused-cUFNnb`。数字包含此前6项，不是新增12项。

本批仍为私有生产方法级验证，尚未实例化完整事件注册表执行finishTransfer、验证任务状态或GUI弹窗。传入已有错误、分卷及压缩结束的完整生命周期不能据此标为通过。

## ZIP 收尾与复合故障

补充6项执行：条目关闭、归档完成、刷新各自失败的3项；真实ZIP中文文件名与扩展字符内容解压核对1项；四阶段同时失败和复用同一异常实例2项。

首轮16项中12通过、4失败（`/tmp/stream-zip-red.log`）：前三项复现IOException被记录后吞掉；正常归档内容核对通过，但结束后writer引用未清除的断言失败。

修复closeOutputStreams：检查PrintWriter错误标志，分别尝试closeEntry、finish、flush、close；保留首次IOException，其他不同异常作为suppressed，并清空资源引用。复合故障测试验证错误顺序、所有清理步骤均执行，重复清理无额外调用；同一异常实例不进行自抑制。

最新直接编译专项 `/tmp/stream-zip-multiple-green.log`：18/18通过、0跳过、0失败，包含此前12项。真实ZIP测试使用内存字节流，重新解压验证唯一条目“导出.csv”和完整中文、扩展汉字、emoji内容。异常测试模拟ZipOutputStream故障，不代表实际磁盘满或网络文件系统中断已验收。完整Tycho、传输任务事件及GUI仍需独立回归。
