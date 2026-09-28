# 网络配置显示信息与身份加载回归

## 后续：正常保存流程的旧凭据替换

源码核对 `DataSourceRegistry.saveDataSources`：每个存储的每轮保存都新建 `DataSourceSerializerModern`。因此不能用人为复用一张旧凭据表的行为推断正常保存会残留旧键。

新增 3 项使用真实保存器和加密器、模拟配置管理器捕获实际输出：首次保存后，分别改名、删除配置、关闭密码保存，再创建新保存器保存。解密核对改名时不包含旧名称键、关闭密码保存时不包含密码；删除最后一个配置向凭据文件名写入 `null`，即交由配置管理器清理旧文件。主配置 JSON 不含密码，原内存 handler 和前次输出字节保持不变。

该配置测试类 **53/53 通过，零跳过**，无新增生产修复。这是生产保存入口与加密输出验证，配置管理器写入被捕获；**尚未证明实际磁盘删除、原子写入或写入失败恢复**。日志 `/tmp/network-credential-cleanup-20260929.log`。

完整 73 模块诊断：2,512 项中 2,342 通过、169 跳过、1 项 Rest 网络权限错误，独立门控仍拒绝通过。逐项证据：[NETWORK_CREDENTIAL_CLEANUP_REACTOR_20260929.json](evidence/NETWORK_CREDENTIAL_CLEANUP_REACTOR_20260929.json)。不与此前批次叠加。

## 后续：不受信任导出的 handler 引用边界

新增 4 项：实际 SSH/PG SSL handler × 配置管理器安全标志。`isTrusted=false` 时生产保存器只写 handler 类型和启用状态，不写用户名、密码、安全属性、普通 host 参数或保存密码开关，且不产生独立凭据条目。用生产解析器重新加载后，handler 存在并启用，但没有上述参数和凭据；检查原 handler 身份及所有原属性保持不变。

该配置类 **50/50 通过，零跳过**，本轮无生产修改。只验证内存 JSON 及配置对象，不等于磁盘导出、共享权限、实际连接或任意自定义字段均不含秘密。普通顶层配置元数据仍按已有格式保存，本项检查范围是 handler 引用。

日志 `/tmp/network-untrusted-export-20260929.log`。

完整 73 模块诊断 2,509 项中：2,339 通过、169 跳过、1 项 Rest 本地网络权限错误；独立门控仍拒绝通过。逐项证据：[NETWORK_UNTRUSTED_EXPORT_REACTOR_20260929.json](evidence/NETWORK_UNTRUSTED_EXPORT_REACTOR_20260929.json)。本轮 Docker API 仍拒绝，未动 Linux GUI 保留现场，不新增真库/GUI 通过数。

## 后续：SSH/SSL handler 凭据关联

新增 8 项：实际扩展注册的 `ssh_tunnel`、`postgre_ssl` × 是否保存密码 × 配置管理器是否标记为安全存储。平台测试模块显式声明 SSH 插件依赖，缺少 handler 时测试失败，不作条件跳过。

每项连续两次调用生产保存器和解析器，第二次使用改名后的配置。检查稳定 ID、当前显示名、用户名、启用状态、普通 host 属性及安全属性；保存密码关闭时，两个输出位置都不应持久化密码，加载后密码为空。独立凭据模式下，主 JSON 不含测试密码/私钥值，并核对 `profile:<稳定ID>` 与 `network/<handler>/profile/<当前名称>` 对应；安全配置模式则检查凭据写入配置且没有额外凭据表条目。

测试类 **46/46 通过、零跳过**。本轮没有生产修复，只增加测试依赖与场景。这里的“安全配置”是生产 `DataSourceConfigurationManager.isSecure()` 分支，测试存储介质仍为内存，不能据此证明磁盘加密。未发起实际 SSH 隧道、TLS 握手或数据库连接；跨配置隔离、secret-storage 后端和历史凭据键清理仍须单独验证。

完整 73 模块诊断共 2,505 项：2,335 通过、169 跳过、1 项错误（仍为 Rest 本地网络权限拒绝），独立门控拒绝通过。日志 `/tmp/network-handler-credentials-20260929.log`；逐项证据：[NETWORK_HANDLER_CREDENTIAL_REACTOR_20260929.json](evidence/NETWORK_HANDLER_CREDENTIAL_REACTOR_20260929.json)。不与前轮计数累加。

## 后续：保存器与独立解析入口往返

扩展至生产 `saveNetworkProfiles → JSON → parseProfiles → DBWNetworkProfileManager.getProfile`，空属性/非空属性两组分别连续保存和加载两轮。检查稳定 ID、中文扩展汉字名称、多行描述、引号/反斜杠属性及空字符串，使用真实管理器方法按名称查找。

修复前两组均失败：独立 `parseProfiles` 入口仍以 ID 覆盖名称且丢失描述，按原名称查询返回空；非空属性组另复现保存器未序列化顶层属性。修复该解析入口的 ID/名称/描述，并让保存器保存已有的普通属性字段，允许空字符串值。没有把 SSH 密码或 handler 安全属性迁移到普通属性。

新增两项通过，该配置测试类现为 **38/38、零跳过**。此前通过的 `parseDataSources` 加密/普通项目四组测试继续执行。本项采用真实 JSON 写入器、解析器和管理器方法，但输入输出为内存文本、没有配置 handler，**不证明 SSH/SSL 连接、handler 凭据引用、磁盘导出或 GUI 向导通过**。

修复前日志 `/tmp/network-profile-roundtrip-red-20260929.log`，复验日志 `/tmp/network-profile-roundtrip-green-20260929.log`。

本轮完整 73 模块诊断：2,497 项中 2,327 通过、169 跳过、1 项本地网络权限错误，独立门控仍拒绝通过；不与历史批次累加。逐项证据：[NETWORK_PROFILE_ROUNDTRIP_REACTOR_20260929.json](evidence/NETWORK_PROFILE_ROUNDTRIP_REACTOR_20260929.json)。辅助审核在日志目录初始化时因权限失败，未产生报告。

## 场景与问题

对应历史清单 3.12（连接配置导入/加载、名称与属性）。保存器 `DataSourceParser.saveNetworkProfiles` 已将配置 ID 作为 JSON 键，另保存 `name` 和 `description`。加载器却连续两次将 JSON 键设置为名称，没有设置 ID 或读取显示信息。

这会使重新加载的配置丢失显示名称和描述；ID 仍依赖显示名称回退计算，重命名后 ID 随之变化。修复为明确设置 JSON 键对应的 ID，读取名称和描述；旧文件没有名称时仍用 ID 显示，不改变旧文件格式。

## 测试方法

`ConfigurationReadFailureTest.networkProfileIdentityAndDisplayMetadataSurviveLoading` 使用四组组合：普通/加密项目 × 有显示名称/旧文件无名称。经过生产解析器和实际加密器，注册表及网络配置管理器为替身，检查：

- ID 与 JSON 键一致；中文名称、描述、属性按原值恢复。
- 缺失名称、描述的旧文件可加载，名称回退为 ID。
- 配置发布到网络配置管理器，并记录在本轮解析结果中。
- 加载后修改显示名称不改变 ID。

第一次运行两个普通项目场景分别复现名称丢失、ID 随改名变化；另外两个加密场景出现测试夹具嵌套 Mockito stubbing 错误。先将加密操作移出 stubbing 表达式，再与生产修复共同复验。夹具错误不计入产品缺陷。

本项不等于 SSH/SSL 实际连接、凭据引用全链路、重命名界面或文件导出向导验收。其余配置引用和网络连接场景仍需继续验证。

## 复验结果

完整 73 模块离线诊断重新编译生产代码后，该类 **36/36 通过，零跳过**。五测试模块合计 2,495 项：2,325 通过、169 跳过、1 项错误。唯一错误仍为 `RestTest.restClientServerTest` 的网络权限拒绝；跳过没有计为通过。

诊断运行沿用无图形 AWT、显式 Byte Buddy 启动代理和失败后继续收集参数，独立验收门控仍因实际错误拒绝通过，不以 Maven SUCCESS 替代验收。逐项证据：[NETWORK_PROFILE_REACTOR_20260929.json](evidence/NETWORK_PROFILE_REACTOR_20260929.json)。日志 `/tmp/network-profile-metadata-green-20260929.log`，修复前日志 `/tmp/network-profile-metadata-red-20260929.log`。

本轮 Docker API 拒绝访问，未修改保留的 Linux GUI 现场，未新增真库或 GUI 通过结论。
