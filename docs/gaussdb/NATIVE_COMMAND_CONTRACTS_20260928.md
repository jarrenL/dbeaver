# 原生工具命令参数与认证失败边界

## 启动前及准备阶段取消（后续）

新增三个取消时点：executeProcess进入前、getCommandLine期间、setupProcessParameters期间。受控命令仅为本机true；记录命令生成、参数准备及进程启动回调次数，不执行恢复或其他数据库操作。

红测`/tmp/native-prestart-red-20260928.log`39项中36通过、3失败，三个时点都实际到达进程启动回调；取消只在启动后的轮询处理。共享AbstractNativeToolHandler增加进入准备前、命令生成后及ProcessBuilder.start前的检查，均以InterruptedException退出，finally保持monitor.done。修复后联合333/333通过、0跳过、0失败（`/tmp/shared-prestart-green-20260928.log`），既有运行中取消、强制终止、发布及清理测试同时复跑。

本次证明三个确定性取消时点不启动进程，以及不会继续不必要的准备阶段；不是检查与start之间的并发原子性保证，不证明已经启动的恢复可回滚，也未对Windows或真实恢复进程进行验收。共享基类修复仍需完整构建及实际客户端回归。

## 目录备份不得合并旧归档（后续）

新增目标不存在、空目录、已有非空目录三项。受控true进程成功后进入真实备份发布路径：新备份含toc.dat及new.dat，旧目录含旧toc.dat及old.dat。红测36项中35通过、非空目录未拒绝而失败（`/tmp/native-directory-red-20260928.log`）。复制实现原来会替换同名文件而保留旧文件，造成归档混合。

修复仅在备份发布层检查目录目标：非空则抛DirectoryNotEmptyException，不删除或覆盖旧目录；不存在/空目录继续复制。恢复文件复制的行为不变。修复后联合330/330通过、0跳过、0失败（`/tmp/shared-directory-green-20260928.log`），包含新增三项：旧目录两文件原文不变且无new.dat；正例核对两份新内容；所有路径都验证暂存及登记清除。

这是发布前保护，不是目录原子替换，也不覆盖检查后并发写入目标、复制中途失败、远程文件系统或真实gs_dump。使用本地临时目录与受控子进程，不计真库或GUI验收。本轮真库计划六项再次全部在连接阶段因Operation not permitted失败；Hermes初始化同样被权限限制拒绝，没有有效审查报告。

## 发布前文件异常（后续）

新增暂存文件消失、目标父路径被已有文件占用两项。实际受控true进程成功后，进入生产备份executeProcess及copyTransferPath，断言IOException传播、原有文件逐字节不变、暂存路径及登记清除、monitor.done执行。所有文件均属于测试临时目录，无数据库访问。

`/tmp/native-publish-failures-20260928.log`原生专项33/33通过；`/tmp/shared-native-final-20260928.log`联合327/327通过，均0跳过、0失败。本轮没有修改生产代码。以上仅覆盖复制开始前的失败，不证明复制中途故障的原子发布、远程文件系统或Windows保护已经完成。

## 有界取消与终止信号抵抗（后续）

新增两个受控进程：普通sleep，以及shell设置忽略TERM后exec sleep（不创建额外长期子进程）。收到ready后才置取消标志；独立测试线程限4秒完成，finally强制清理进程并确认线程退出。

红测`/tmp/native-cancel-bounded-red.log`31项中29通过、2失败：普通终止退出码被转成IOException而不是取消；忽略TERM进程持续轮询，超过4秒。修复取消分支先destroy、最多等待1秒，未退出则destroyForcibly，之后抛InterruptedException；不再把终止退出码当作工具运行错误。既有取消发布保护测试相应要求InterruptedException而不是返回false，仍验证原备份不被覆盖和暂存路径被删除。

`/tmp/native-cancel-bounded-green.log`31/31通过、0跳过、0失败。进程和工作线程都经测试finally确认退出。该1秒为宽限等待，不保证所有OS下总取消耗时严格等于1秒；未验证不可终止OS状态、进程树、远程服务器事务收尾、真实gs_dump或Windows。共享原生工具的取消语义变化仍须完整回归。

## 启动后异常与线程中断的进程回收（后续）

新增2项真实受控子进程测试：生产executeProcess启动/bin/sleep 30后，启动处理回调抛IOException，或设置当前线程中断令等待阶段抛InterruptedException。断言原始IO异常身份/中断类型保留，方法退出后自有子进程在2秒内结束，进度done；测试finally另有强制清理及退出确认，避免红测遗留进程。

红测`/tmp/native-child-lifecycle-red.log`29项中27通过、2失败：原finally仅结束进度，仍在运行的子进程未被回收。修复将Process引用保留至finally，仅对当前任务仍存活的子进程调用destroyForcibly；清理发生RuntimeException时记录警告，不替换原失败。未改为扫描或终止其他系统进程。

`/tmp/native-child-lifecycle-green.log`29/29通过、0跳过、0失败。此改动影响共享原生工具框架，仍需各数据库工具回归；测试不是GaussDB服务器进程、Windows、子孙进程树、OS拒绝终止或GUI取消验收。强制终止意味着工具不能再执行自身收尾，因此失败后的备份副本不得发布，原有发布保护测试同轮继续通过。

## 上层备份任务循环补验（后续）

新增成功、取消标志、IOException、InterruptedException四项，调用生产AbstractNativeToolHandler.doExecute。子进程执行替换为确定性结果，两个待备份对象：成功依次执行两个并通知完成；第一个取消或抛异常时第二个不执行，取消向上抛InterruptedException，已有中断保留同一实例，IO错误作为DBException原cause保留；失败/取消不发完成通知。备份无需模型刷新，断言不获取刷新对象、不操作导航模型。

专项脚本加入当前AbstractNativeToolHandler源码，`/tmp/native-task-loop-20260928.log`27/27通过、0跳过、0失败（含旧23项），无需新生产修复。四个新增场景验证的是任务循环，不是实际线程中断或数据库取消；之前三个真实受控进程场景同轮继续通过，不混淆两个层次。

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
