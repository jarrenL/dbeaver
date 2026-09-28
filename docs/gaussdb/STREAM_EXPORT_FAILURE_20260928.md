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

分卷完整生命周期、真实失败任务事件通知及取消操作等其他路径仍需跟进。压缩流结束方法及传入已有错误的组件验证见下节，不等同完整压缩导出任务。不能据此宣布全部导出错误已闭环。共享传输消费器受影响，不仅是GaussDB。

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

## 已有错误与取消异常的结束路径

读取DataTransferJob确认：生产者抛错或取消检测抛DBInterruptedException后，调用consumer.finishTransfer(monitor, error, task, false)，随后重新抛出原错误。消费器旧实现仅在error为null时结束输出，错误路径遗漏清理。

新增测试直接调用公开finishTransfer，分别传入读取IOException或DBInterruptedException，并组合输出流关闭成功/失败，共4项。首轮读取错误的2项均失败，processor.dispose未被调用（`/tmp/stream-existing-error-red.log`）。修复后，已有错误且非最终汇总回调时调用closeExporter，不调用成功表尾；清理IOException附加到原错误，不替换原错误。重复回调不再次关闭流。

最新 `/tmp/stream-existing-error-cancel-green.log`：22/22通过、0跳过、0失败，包含此前18项。测试替换事件注册表为模拟对象并在finally恢复，配置为空事件列表；不覆盖实际事件扩展、完整DataTransferJob重新抛错、GUI点击取消或文件系统故障。取消异常为人工注入，不声称真实运行中取消已通过。

## 公开结束入口的事件分派补验

在此前4项已有错误测试中配置模拟事件处理器，验证processError收到同一个原始异常、相同事件设置，输出关闭尝试先于通知，且不调用成功processEvent。重复结束只验证清理幂等，不宣称通知去重。

新增4项：表尾、dispose、ZIP finish失败分别经公开finishTransfer返回DBException，抑制列表中的错误与事件收到的错误一致，cause为注入的原始IOException；成功对照在非最终回调完成表尾和关闭但不通知，在最终汇总回调发送一次FINISH且不再次关闭。

最新 `/tmp/stream-event-routing-final.log`：26/26通过、0跳过、0失败。此轮无新增生产修改。使用生产分派代码和模拟注册表/描述符/事件处理器，不是OSGi扩展实例、脚本通知、系统弹窗或完整DataTransferJob调度验证；这些验收边界保持未完成。

## 任务层原始错误保留

进一步调用DataTransferJob私有transferData生产方法，模拟生产者抛数据库DBException或DBInterruptedException，结束回调分别正常返回、抛DBException、抛运行时异常、重新抛同一异常，共8项。校验原错误身份、附加异常、结束回调及monitor.done。

红测 `/tmp/stream-job-primary-red.log` 为34项中30通过、4失败：两类原错误均被通知阶段的受检/运行时异常覆盖。DataTransferJob修复为捕获结束回调异常，附加到原错误（排除自身），继续记录并重新抛出原错误。取消异常类型得到保留，但尚未运行调度器验证最终CANCEL状态。

绿测 `/tmp/stream-job-primary-green.log` 为34/34通过、0跳过、0失败。专项脚本同时编译当前DataTransferJob和StreamTransferConsumer源码；测试绕过Job构造及调度，用反射调用真实transferData，生产者、消费器、日志和monitor为模拟对象。不等同真实数据库断连、操作系统调度或GUI通知验收。

## 任务运行方法的状态补验

新增2项调用真实DataTransferJob.run：生产者抛读取错误或取消异常，结束通知再失败。验证取消返回CANCEL；普通错误按既有约定返回带原始异常的OK状态（由调用方处理，以免重复弹窗），不是成功验收。原始异常保留通知错误为suppressed。两项都只获取第一个数据管道，后续管道无交互，monitor结束。

首轮测试替身默认拦截run导致返回null，是测试配置错误，不计产品缺陷；改为仅run调用真实实现，其余框架方法保持模拟。`/tmp/stream-job-status-green.log`：36/36通过、0跳过、0失败，无新增生产修复。仍绕过Eclipse Job构造和线程调度，不宣称完整调度器、客户端任务窗口或真实取消操作通过。

同轮完整构建尝试run-V8up7m在Tycho缓存锁获取超时退出，没有生成本轮完整测试通过证据。Docker列表及一次只读查询可用，但后续调用被拒绝，角色夹具准备未成功，真库回归未执行；这些失败不计为测试通过。

## 追加导出读取失败导致原文件被截断

检查分卷共用的openOutputStreams时发现，prepareDataFileConflictBehavior在APPEND模式捕获importData的DBException后仅记录警告，继续询问shouldTruncateOutputFileBeforeExport并打开文件。对需重写的结构化格式，这会在读取旧内容失败后仍截断原文件。

2项新测试使用JUnit临时目录和真实文件，模拟可追加导出器，分别选择普通追加/结构化重写。红测 `/tmp/stream-append-preservation-red.log` 为38项中36通过、2失败：两条路径均未报告读取错误，结构化路径真实文件由36字节变成0字节。不是仅用模拟流推断丢失。

修复为读取失败立即包装IOException并保留cause，在选择截断或打开输出之前终止。验证文件原始字节保持不变，不调用截断判断；同一消费器修正输入后重试，读取回调确认原文件仍在，普通追加和结构化重写后的完整字节均正确。

`/tmp/stream-append-preservation-retry.log` 为38/38通过、0跳过、0失败。生产文件冲突处理和文件打开/关闭真实执行；解析器用模拟导出器注入异常，结构化重写输出由测试模拟，不能据此宣称真实XLSX文件在完整GUI任务中已验收。此前XLSX导出器单独的坏文件测试不包含消费器吞错，此次补的是上层缺口。分卷完整切换仍待补验。

## 真实 XLSX 消费器桥接补验

新增3项使用真实DataExporterXLSX、StreamTransferConsumer内部StreamExportSite及真实临时文件：空文件、文本伪装、合法工作簿截断。通过生产openOutputStreams触发APPEND和importData，校验IOException → DBException → 底层解析异常链；失败后outputStream为空，坏文件完整字节不变。

随后同一消费器/导出器切换到有效工作簿，通过真实追加读取及文件打开路径，调用真实init/header/row和消费器finishFile；重新以XSSFWorkbook打开磁盘结果，确认单表3行、原表头/原数据和新增“追加中文𠀀😀”全部保留，坏文件仍不变，处理器和输出引用已清空。不是模拟结构化输出。

专项脚本新增编译当前DataExporterXLSX，`/tmp/stream-real-xlsx.log` 为41/41通过、0跳过、0失败。此轮无生产修改。来源对象、列元数据及数据库会话为模拟对象；未经过真实JDBC取行、导出向导、initExporter完整初始化或Eclipse任务调度，完整GUI验收仍待完成。
