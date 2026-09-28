# 原生工具命令参数与认证失败边界

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
