# 原生工具命令参数与认证失败边界

## 备份发布与取消保护（后续）

新增GaussDBBackupPublishTest三项：成功、取消标志已置位但子进程正常退出、子进程非零退出。通过生产executeProcess实际启动本机/usr/bin/true或false；覆盖命令生成、环境设置和异步日志启动以排除数据库和外部服务，反射登记本测试TempDir中的暂存副本。验证只有成功且未取消时覆盖已有目标，失败/取消保留原备份，所有路径移除暂存文件及登记、结束进度。

红测`/tmp/native-publish-overwrite-red.log`23项中22通过、1失败：取消场景返回true，并把“previous valid backup”覆盖为“new bytes”。修复在原生进程返回后同时检查结果和取消标志，拒绝发布失败或已取消的副本；finally仍清理暂存路径。

`/tmp/native-publish-green.log`23/23通过、0跳过、0失败。这里是真实受控本机进程和文件，但不是gs_dump真实备份、网络目标、GUI取消或Windows；也未覆盖在复制已经开始之后才取消、复制途中IO失败的原子替换问题。缺少本机true/false可执行程序的平台明确跳过，不能算通过。

## 恢复准备阶段失败清理（后续）

新增CUSTOM文件和DIRECTORY目录两项，实际进入生产fillProcessParameters并强制走本地暂存分支；测试只覆盖暂存分支选择和临时路径工厂，将临时路径限定在独立TempDir，路径解析、创建、复制、异常处理和登记均为生产实现。源不存在时应保留NoSuchFileException、删除尚未登记的临时副本，并保留客户端目录。修正源内容后，同一处理器再次准备应成功复制，测试清理副本后原内容仍在。

红测`/tmp/native-staging-red.log`20项中18通过、2失败：临时路径在复制后才登记到localTransferFiles，复制失败时后续finally无从清理。将临时创建抽成受保护工厂以隔离测试目标，并在复制的IOException/RuntimeException失败路径立即调用临时清理，再重抛原异常。

最终`/tmp/native-staging-retry-green.log`20/20通过、0跳过、0失败。文件与目录失败后重试均通过；实际子进程尚未启动，未验证网络复制中断、运行中取消、清理本身被OS拒绝或Windows行为，不能扩大为完整恢复流程验收。

## 本地传输副本生命周期补验（后续）

新增6项直接调用生产copyTransferPath/deleteLocalTransferPath：0/7/65537字节文件三项，逐字节比对复制结果，删除副本及重复/空目标清理后原文件不变；中文嵌套目录、空目录、中文扩展字符/零字节内容一项，清理副本后原目录与同级无关文件保持；源文件缺失、目标父路径为文件两项，异常报告且既有目标/原始文件/冲突文件内容不变。

所有写入和删除位于JUnit为测试创建的独立TempDir，未操作实际备份。`/tmp/native-transfer-files-20260928.log`18/18通过、0跳过、0失败（含旧12项），未发现新生产缺陷。这是实际本机文件操作，不是mock文件系统；但不是完整restore任务、远程文件系统、复制中途取消、部分副本失败清理、符号链接安全或Windows权限验收。尤其不能由工具方法通过推断任务级临时文件注册/回收生命周期已经闭环。

对应历史清单3.15、10.2及连接安全，扩充GaussDBNativePasswordTest。使用生产PostgreNativeToolHandler、PostgreDatabaseRestoreHandler和PostgreScriptExecuteHandler；模拟进程与连接模型，不调用真实备份恢复命令。

## 新增7项执行

- 原已有LF密码拒绝场景扩为LF/CR/NUL三项，新增2项：拒绝发生在获取输出管道之前，进程被destroy，不写部分凭据。
- 写密码、flush、close各失败，共3项：保留原IOException为cause，顶层消息不主动包含合成密码，调用process.destroy。不是实际子进程退出状态/操作系统句柄释放证明。
- 普通SQL恢复与自定义归档两条命令生成路径，共2项：真实临时目录包含中文和空格，输入路径含中文、空格、分号和字面$(text)，数据库名及用户名含空格。断言binary路径、用户名、数据库名和输入路径分别保持单个完整argv元素，不把合成密码放入参数；PLAIN使用--file=，CUSTOM使用独立路径参数，均保留--pipeline。未调用shell，不测试或执行路径中的特殊字符。

## 结果与边界

`scripts/run-native-contract-focused.mjs`直接编译当前三个生产handler与测试类。`/tmp/native-contract-20260928.log`12/12通过、0跳过、0失败，含旧5项，未发现新生产缺陷。既有PostgreSQL环境变量认证和GaussDB管道认证的差异仍通过。

本机文件路径测试不等于Windows .exe寻址、CreateProcess参数转义、权限继承或真实gs_restore接收参数验收。也未证明备份恢复数据一致性、运行中取消清理、真实管道断开后的进程生命周期。相关真库与平台场景仍按各自证据验收，不能把本12项并入历史全量通过数。
