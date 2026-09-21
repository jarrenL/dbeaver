# GaussDB 适配版 DBeaver：麒麟 V10 服务器版

本目录为交付构建说明；当前验收进度见仓库根目录 `GAUSSDB_KYLIN_LINUX_ACCEPTANCE_20260909.md`。仅有候选目录不代表已完成发布。

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
- 调试视图支持断点、单步、变量改值、变量名监视、调用栈导航。监视不支持任意 SQL/PL 表达式。快捷键 F7/F8/Shift+F7/F9/F10 需图形窗口有焦点；远程桌面应放行这些按键。
- 正常调试完成选择 Commit 或 Rollback，并检查数据库结果；F10 用于终止。测试应使用隔离数据，避免业务过程在调试中产生不可回滚的外部副作用。
- 包限定支持该语法及目录的集中式 ORA 实例。包右键菜单可选 ALL、Specification、Body 编译；错误列表可双击定位源码。编译是服务端校验/重编译，不会执行包内业务过程。
- 批量删除会真实删除所选包；先核对确认框对象列表并自行备份定义。
- 分支已有 `gsql/gs_dump/gs_restore` 原生工具映射与任务适配；工具可执行文件不随此客户端包提供，需配置匹配的 Linux GaussDB client home。本次双架构 Linux GUI 验收以原始七项为范围，未执行原生备份恢复任务，不把历史其他环境的工具验证当成麒麟工具二进制认证。

## 可复现原生构建

产品先按仓库常规 Maven/Tycho 流程构建 Linux GTK 两架构。不得以装有 SWTBot 的测试副本为产品源。

在每种架构的麒麟构建环境安装 GCC/G++、make、GTK3/Xtst/GLU 开发包及 JDK 头文件，然后执行：

```sh
bash build-natives.sh /源码/eclipse.platform.swt-4973r12 /源码/equinox/features/org.eclipse.equinox.executable.feature/library /新的输出/native
bash assemble.sh /产品/linux/gtk/aarch64/dbeaver /新的输出/native /下载/jre-aarch64.tar.gz /新的输出/release aarch64
```

第二条可在有 `jar`、`zip`、`tar` 的组装机运行；x86_64 替换对应架构及 JRE。启动器源码须是 Equinox `R4_37`（11916），SWT 须是 `v4973r12`，不能随意换成新版。脚本唯一 SWT 源构建参数调整为 `gnu17`→`gnu11`，保留 `-Werror`。重建的 SWT jar 不保留已失效的 Eclipse 签名；不声称由 Eclipse 签署或认证。

组装成功后仍须验证干净产品、整理许可证/对应源码、归档并生成校验和，才能作为最终交付。
