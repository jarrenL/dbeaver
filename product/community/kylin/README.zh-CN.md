# GaussDB 适配版 DBeaver：麒麟 V10 服务器版

本目录为交付构建说明；客户操作步骤见仓库根目录 `GAUSSDB_GUI_OPERATION_MANUAL_20260922.md` 和 `GaussDB_DBeaver_调试与Package操作手册_20261105.docx`。交付修订 `20261105-r2` 更新连接图示和源码交付，应用二进制仍为已验证的 `26.1.5.20261105r1`。仅有候选目录不代表已完成发布。

## 平台范围

分别提供 `aarch64`（ARM64）和 `x86_64` 包。在客户机器执行 `uname -m`，选择完全匹配的架构；不要根据“麒麟 V10”推断架构。其他架构（如 LoongArch）不在本次范围。

已测用户空间：Kylin Linux Advanced Server V10 (Lance)，glibc 2.28、GTK 3.24.21，第三方 V10 SP3 容器镜像。ARM64 原生执行，x86_64 在 ARM 宿主上仿真执行。容器测试不等于麒麟官方认证，也不覆盖客户整机、内核、显卡或远程桌面产品认证。

DBeaver 是图形客户端。服务器必须有可用的图形会话（本地桌面、远程桌面或正确配置的 X11 转发）；仅 SSH 终端不能显示窗口。Xvfb 是本次自动化测试设施，不是客户操作界面。

## 安装和运行

1. 校验交付清单：`sha256sum -c SHA256SUMS`。
2. 解压对应架构的 tar.gz 到普通用户可写目录，无需覆盖系统 Java 或 glibc。
3. 在图形终端进入解压后的 `dbeaver` 目录，运行 `./dbeaver`。包内 JRE 21 与架构匹配。
4. 第一次配置启用 **Procedure debugger**。测试时关闭了 AI integration、匿名统计和每日提示；这些不是 GaussDB 的必需功能。
5. 同一工作区不要由多个实例同时打开。需要隔离时使用 `./dbeaver -data /绝对路径/新工作区`。

测试镜像安装的运行时依赖如下；客户应使用自己获授权的麒麟软件源，由管理员确认后安装：

```sh
sudo yum install gtk3 alsa-lib libXtst dejavu-sans-fonts webkit2gtk3 dbus-x11
```

若系统未配置桌面或窗口管理器，应先按客户服务器管理规范部署图形环境。不要下载其他发行版的 glibc 覆盖系统库。启动故障先保存工作区 `.metadata/.log`，同时记录 `uname -m`、`cat /etc/os-release`、`ldd --version` 与 `echo "$DISPLAY"`。

## GaussDB 驱动与连接

选择 **GaussDB Native**，配置客户 GaussDB 版本匹配的官方 JDBC jar。在驱动设置的 Libraries 中添加该文件，执行 Test Connection。离线环境应由客户或数据库供应方提供 JDBC 文件。

M 兼容部署另提供 **GaussDB M (version-matched gsjdbc4)** 配置；按客户服务端版本提供对应 `gsjdbc4.jar`。不要将不同部署/版本的 JDBC 文件混装在同一个驱动配置中。

候选包不内置 GaussDB JDBC：本次使用已有 `506.0.0.b058` 文件连接 Kernel 507 实测，但尚未取得其再分发依据。不得把 PostgreSQL 驱动的可连接性等同于 GaussDB Native 全部行为兼容。

地址和端口必须使用客户实际数据库参数；测试专用的 `host.docker.internal`、5432/55452 端口和账号不是部署默认值。不要向客户复制测试工作区、保存密码或测试启动配置。

## 功能边界与操作

- 自动识别 A/ORA、B/MYSQL、C/TD、PG、M。M 关闭存储过程/PL 调试与包能力；允许浏览其他受支持对象不代表支持 PL/SQL。
- 调试入口还检查实际 routine 语言、调试 API 能力/签名及权限。应按数据库版本配置最小必需调试权限，不要通过授予超级用户权限绕过检查。
- 调试视图支持断点、单步、变量改值、变量名监视、调用栈导航。游标可展开查看服务端返回的只读状态，并监视 `cur%ROWCOUNT` 等属性；不读取游标结果行。监视不支持任意 SQL/PL 表达式。快捷键 F7/F8/Shift+F7/F9/F10 需图形窗口有焦点；远程桌面应放行这些按键。
- 正常调试完成时选择“提交剩余事务并结束”或“回滚剩余事务并结束”，默认回滚；过程及其调用对象已经提交的内容无法撤销，此选择也不保存或撤销源码修改。F10 用于终止。测试应使用隔离数据，避免业务过程在调试中产生不可回滚的外部副作用。
- 自定义复合类型参数按实际类型 OID 所属 Schema 生成调用，按服务端文本格式整段输入；字段含逗号时使用双引号，整对象空值选择 SQL NULL。具体点击步骤和已验证范围见操作手册；不将复合类型的验证扩大为所有对象类型均可输入。
- Package 基础能力要求 O/A/ORA 兼容库提供 `gs_package`，不再仅凭“集中式/分布式”标签判断。包右键菜单可选 ALL、Specification、Body 编译；编译是服务端校验/重编译，不会执行包内业务过程。
- 目标库提供 `DBE_PLDEVELOPER.GS_ERRORS` 时，错误列表使用服务端结构化诊断并可按实际行、列精确定位。若没有该目录，但提供 `GS_SOURCE` 且原生 `ALTER PACKAGE ... COMPILE` 不受支持，客户端会重放服务器已保存的声明或主体源码并显示数据库直接错误；仅当错误标记能在原始源码中唯一可靠匹配时显示“近似”位置，否则行号显示 `-`。该降级路径不会静默设置 `enable_force_create_obj`、`plsql_show_all_error` 或其他私有会话参数，也不会把编辑器中尚未成功保存的错误文本冒充为服务器源码。
- 批量删除会真实删除所选包；先核对确认框对象列表并自行备份定义。
- 分支已有 `gsql/gs_dump/gs_restore` 原生工具映射与任务适配；工具可执行文件不随此客户端包提供，需配置匹配的 Linux GaussDB client home。本次双架构 Linux GUI 验收以原始七项为范围，未执行原生备份恢复任务，不把历史其他环境的工具验证当成麒麟工具二进制认证。

## 可复现原生构建

产品先按仓库常规 Maven/Tycho 流程构建 Linux GTK 两架构。不得以装有 SWTBot 的测试副本为产品源。

本分支从仓库根目录使用完整聚合入口（相邻的 `dbeaver-common` 必须为匹配版本）：

```sh
mvn -f product/aggregate/pom.xml -Pproduct-dbeaver-ce package
```

不要使用 `-pl :standalone -am` 替代完整聚合构建；Maven 项目依赖选择不保证包含产品引用的所有 Eclipse feature。产品输出位于 `product/community/target/products/org.jkiss.dbeaver.core.product/linux/gtk/{x86_64,aarch64}/dbeaver`。若已单独完成当前源码测试，可在仅打包阶段使用 `-DskipTests=true`，但这次打包不构成测试通过证据。

组装器同时检查输入产品和最终目录：拒绝测试插件、工作区、凭据配置和 `global-settings.ini`。最终目录仅允许 `jre/legal` 内指向该目录内真实文件的相对许可证链接；不允许目录链接、目录外目标或损坏链接。该检查不替代最终归档校验和、原生库架构/字节核验、许可证及实际启动验收。组装失败留下的输出目录应视为未完成候选，不能发布。

在每种架构的麒麟构建环境安装 GCC/G++、make、GTK3/Xtst/GLU 开发包及 JDK 头文件，然后执行：

```sh
bash build-natives.sh /源码/eclipse.platform.swt-4973r12 /源码/equinox/features/org.eclipse.equinox.executable.feature/library /新的输出/native
bash assemble.sh /产品/linux/gtk/aarch64/dbeaver /新的输出/native /下载/jre-aarch64.tar.gz /新的输出/release aarch64
```

第二条可在有 `jar`、`zip`、`tar` 的组装机运行；x86_64 替换对应架构及 JRE。启动器源码须是 Equinox `R4_37`（11916），SWT 须是 `v4973r12`，不能随意换成新版。脚本唯一 SWT 源构建参数调整为 `gnu17`→`gnu11`，保留 `-Werror`。重建的 SWT jar 不保留已失效的 Eclipse 签名；不声称由 Eclipse 签署或认证。

组装成功后仍须验证干净产品、整理许可证/对应源码、归档并生成校验和，才能作为最终交付。

可在组装机生成应用目录的逐文件清单（清单必须位于应用目录之外）：

```sh
node product/community/product-manifest.mjs create /交付/x86_64/dbeaver /交付/x86_64/APPLICATION-MANIFEST.json
node product/community/product-manifest.mjs verify /交付/x86_64/dbeaver /交付/x86_64/APPLICATION-MANIFEST.json
```

清单记录文件 SHA-256、大小、可执行标志、目录及符号链接目标；验证会拒绝新增、缺失和变化项。创建不会覆盖已有清单。应在首次启动前校验原始交付目录，启动后的配置变化也会导致不一致。该工具需要 Node.js，仅用于构建/验收；客户端运行不依赖 Node.js。归档文件仍需单独提供 `SHA256SUMS`，清单不等于发布签名、来源认证或功能验收。
