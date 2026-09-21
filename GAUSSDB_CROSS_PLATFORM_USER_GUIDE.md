# GaussDB 适配版 DBeaver 跨平台安装与使用指南

> 最新26.1.5麒麟x86_64实测见 [GUI验收报告](ICBC_2615_LINUX_GUI_ACCEPTANCE_20260921.md)：核心流程已跑通，但有未修复问题，不是客户桌面云验收全绿或新发布版本。

> 工行范围澄清（2026-09-21）：当前开发基线为真正的 26.1.5。O（Oracle，服务端可能返回 O/A/ORA）模式验收 PL/SQL 调试与 Package；M 仅要求基础连接、元数据、SQL 和数据维护，不提供 PL/SQL 调试/包编译入口。详见 [范围核对报告](ICBC_REQUIREMENTS_SCOPE_20260921.md) 和 [迁移与验证报告](ICBC_2615_507_VALIDATION_20260921.md)。下文保留 09-18 平台操作及历史发布信息；旧 Windows 26.2.0 发布包未被替换。

适用对象：使用 macOS、Windows、Linux（重点为银河麒麟 V10 服务器版）连接 GaussDB 的开发、测试和运维人员。整理日期：2026-09-18。

本文针对本项目的 **GaussDB 适配版 DBeaver**，不是任意官网下载版 DBeaver 的功能承诺。菜单可能因中英文界面略有不同，文中同时给出关键英文名称。

## 目录

1. 使用方式、交付版本与平台选择
2. 安装前准备
3. macOS 安装与启动
4. Windows 安装与启动
5. Linux / 麒麟 V10 安装与启动
6. JDBC 驱动、网络和连接设置
7. SQL、对象管理与兼容模式
8. 存储过程 / 函数调试
9. 包编译、错误定位与批量删除
10. 原生备份、恢复与脚本执行
11. 现场冒烟检查
12. 升级、迁移与故障排查
13. 源码构建与参考资料

## 1. 使用方式、交付版本与平台选择

### 1.1 客户端与数据库是两回事

DBeaver 安装在操作者的电脑或有桌面的 Linux 服务器上，通过 JDBC 访问数据库服务器。**Windows、Mac 客户端均不需要为了连接 GaussDB 安装 Docker 或本地数据库。** Linux 客户端也不要求与数据库部署在同一台机器。

- Windows x86_64 客户端可以连接 ARM64 GaussDB 服务器。
- Mac ARM64 客户端可以连接 x86_64 GaussDB 服务器。
- 客户端包、其 JRE、SWT 原生库必须匹配“运行客户端的系统与 CPU”；并非匹配远端数据库 CPU。
- 集中式数据库连接 DBA 指定的服务入口；分布式数据库连接 CN / 经批准的集群入口，不拿 DN 当普通业务连接入口。
- 原生备份恢复另外需要工具。Linux 的 gs_dump 可执行文件不能直接放到 Windows 或 macOS 里运行，详见第 10 节。

### 1.2 先确认拿到的是哪个版本

目前存在两个不同层次，不能混用结论：

| 内容 | 状态与适用范围 |
|---|---|
| 2026-09-09 麒麟双架构归档 | 已有历史交付文件及证据；源码基线 26688a344254a29677edfd06076345c0fc6d71fe |
| 2026-09-18 当前工作区 | HEAD 为 99e620c16dd8403f4730d6242abcab7d22f524f5，另含尚未提交的 09-17/18 修复；本轮回归 654 通过、4 跳过、0 失败 |
| 包含上述最新修复的跨平台安装包 | 本轮尚未重新打包、推送或做安装验收；不能认为旧包或远端 clone 自动含有这些修复 |

最新三项修复是：普通账号原生工具管道认证、COMMIT 回执丢失时有界退出、真实 marker 删除后的服务端断点清理。下文描述这些行为时，前提都是“安装包确实包含该修复”。

交付方应同时提供：准确文件名、SHA-256、源码提交及补丁状态、内置 JRE 说明、支持的 OS/CPU、驱动获取方式、许可证与对应源码材料。**不要将 SWTBot 测试副本、candidate 临时目录或保存密码的测试 workspace 发给客户。**

### 1.3 平台选择与已有证据

| 客户端环境 | 选择 | 当前证据边界 |
|---|---|---|
| Apple Silicon Mac | macOS aarch64/ARM64 包 | 有本机开发、部分 GUI 与真库测试；不等于所有 macOS 版本安装认证 |
| Intel Mac | macOS x86_64 包 | 不拿 Apple Silicon 测试代替 Intel 实机验收 |
| Windows Intel/AMD 64 位 | Windows x86_64 包 | 已有测试方案，尚无 Windows 实机完整验收证据 |
| Windows ARM64 | 与 Windows/JRE 匹配的 ARM64 产品 | 构建配置存在不等于产品已验证；须单独安排现场验证 |
| 麒麟 V10 服务器版 ARM64 | 专用麒麟 aarch64 包 | 历史双架构测试之一；容器用户空间验证，不是客户整机认证 |
| 麒麟 V10 服务器版 x86_64 | 专用麒麟 x86_64 包 | 历史测试在 ARM 宿主仿真运行，不等于原生 x86 客户实机认证 |
| Ubuntu / 其他 Linux | 匹配架构和系统依赖的 Linux GTK 包 | 按实际 GTK/glibc/JRE 条件验证，不能泛化麒麟结果 |
| 龙芯等其他架构 | 不使用 ARM64/x86_64 包冒充 | 不在当前已验证范围 |

## 2. 安装前准备

### 2.1 向 DBA 索取以下信息

| 项目 | 应提供的信息 |
|---|---|
| 地址 | 可从客户端访问的主机名/IP；分布式为 CN/指定集群入口 |
| 端口 | 实际监听/映射端口，不直接沿用工具预填值 |
| 数据库 | 数据库名、兼容模式、集中式/分布式、完整版本/build |
| 账号 | 普通开发账号、允许访问的 schema、对象权限 |
| 网络 | VPN、白名单、SSH 跳板要求及客户端实际出口 IP |
| TLS | 是否强制加密、CA/客户端证书及主机名校验要求 |
| JDBC | 与目标服务端匹配的原厂 jar、驱动类、版本、校验值 |
| 调试 | 目标语言与调试 API 是否支持、gs_role_pldebugger 及必要执行权限 |
| 包功能 | 是否为支持包的集中式 ORA 库，以及诊断目录是否可访问 |
| 备份恢复 | 可用原厂客户端工具的 OS/CPU/版本，以及获准保存备份的位置 |

不以超级用户作为日常客户端默认账号。不为排查连接而放开全网访问、改成 trust 或关闭证书校验。

### 2.2 分开保存三个目录

- **安装目录**：程序、插件、JRE；升级用新目录。
- **工作区 workspace**：连接配置、编辑器状态、日志；需可写，不与其他实例共用。
- **驱动/备份目录**：客户提供的 JDBC 和导出文件；按企业要求限制权限。

workspace 可能含敏感配置。不要上传到 Git、公共网盘或发给同事当“安装包”。密码是否能迁移取决于安全存储，不承诺拷贝工作区后密码可直接复用。

## 3. macOS 安装与启动

### 3.1 确认系统和架构

在“关于本机”查看芯片；终端辅助查看：

```sh
sw_vers
uname -m
```

Apple Silicon 通常为 arm64，Intel 为 x86_64；终端如果在仿真模式下运行，结果须结合“关于本机”判断。不要混装两种架构的启动器、JRE 或插件原生库。

### 3.2 校验和安装

1. 从项目交付方取得匹配架构的完整客户端，而不是只下载一个 GaussDB jar。
2. 核对文件 SHA-256，例如 `shasum -a 256 /实际路径/交付文件`，与交付方清单逐字比较。
3. DMG 形式：打开后将应用复制到 Applications；ZIP/tar 形式：完整解压，再移动应用。不要只拖出内部可执行文件。
4. 保留旧版本，先用独立工作区验证新包。不要直接覆盖正在运行的 DBeaver。

### 3.3 未签名 / 未公证的应用

未签名不等于一定不能安装，但可能被 Gatekeeper 拦截。确认来源与校验值后，先尝试正常打开，再到“系统设置 → 隐私与安全性”按提示选择“仍要打开 / Open Anyway”，并完成系统确认；受企业策略管理的 Mac 可能必须由 IT 批准。若提示恶意软件或文件损坏，不把它简单当作签名提示，应先联系交付方核验。参见 [Apple 官方说明](https://support.apple.com/en-gb/102445)。

本指南不要求全局禁用 Gatekeeper、关闭 SIP 或递归解除任意下载目录的隔离属性。手工改动已签名应用内部文件也可能使签名失效。

### 3.4 使用独立工作区

假设实际应用路径为 `/Applications/DBeaver.app`：

```sh
mkdir -p "$HOME/DBeaver-GaussDB/workspace"
open -a /Applications/DBeaver.app --args -data "$HOME/DBeaver-GaussDB/workspace"
```

执行前先正常退出已运行的同一应用；如果包内应用名称不同，替换实际路径。需要收集启动控制台信息时，可在确认应用内部布局后直接启动：

```sh
/Applications/DBeaver.app/Contents/MacOS/dbeaver -data "$HOME/DBeaver-GaussDB/workspace" -consoleLog
```

优先使用包内匹配的 JRE。不要因为系统 `java -version` 不同就替换包内 JRE；实际运行 JVM 应从客户端安装详情/配置中核对。

### 3.5 键盘注意事项

调试按键 F7/F8/F9/F10 若被音量/媒体键占用，使用 Fn 组合或调整系统功能键设置。只有 GaussDB 调试上下文激活时才使用这些调试绑定；优先点击工具栏动作验证，再排查按键冲突。

## 4. Windows 安装与启动

### 4.1 不需要 Docker，也不需要 WSL

安装 Windows 原生客户端，连接已部署的服务器 GaussDB 即可。通过 WSL 启动 Linux DBeaver 不算 Windows 原生客户端验证。

在“设置 → 系统 → 系统信息/关于”确认系统类型与处理器。普通 Intel/AMD 64 位机器选 x86_64 产品。

### 4.2 校验、解压、运行

交付 ZIP 示例名 `dbeaver-gaussdb-windows-x86_64.zip` 仅为占位，替换为实际文件名。

```powershell
Get-FileHash 'C:\Downloads\dbeaver-gaussdb-windows-x86_64.zip' -Algorithm SHA256
New-Item -ItemType Directory -Force 'C:\GaussDBClient\workspace' | Out-Null
```

对照交付清单确认哈希后，完整解压到新目录，例如 `C:\GaussDBClient\client`，确认该目录内包含 dbeaver.exe、插件和包内 JRE。若交付物是正式安装器，则按企业批准流程安装，不假定仓库已经生成了签名安装器。哈希计算方式见 [Microsoft Get-FileHash](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/get-filehash)。

```powershell
& 'C:\GaussDBClient\client\dbeaver.exe' -data 'C:\GaussDBClient\workspace'
```

路径以实际解压层级为准。普通用户运行即可；不以“始终管理员运行”解决权限问题。工作区不要放在无写权限的 Program Files 目录中。

若 SmartScreen/终端安全软件阻止启动，保留告警，核对发布来源和校验值，由企业 IT 审批；不要直接关闭系统防护。

### 4.3 网络检查

```powershell
$dbHost = 'db.example.internal' # 替换为 DBA 提供的地址
$dbPort = 8000                 # 替换为实际端口，不是强制值
Test-NetConnection -ComputerName $dbHost -Port $dbPort
```

`TcpTestSucceeded=True` 仅表示 TCP 可达，不代表账号、TLS、数据库权限正确。该命令及结果含义见 [Microsoft Test-NetConnection](https://learn.microsoft.com/en-us/powershell/module/nettcpip/test-netconnection)。

### 4.4 常见平台差异

- PowerShell 执行带空格路径的程序时，使用 `& '完整路径'`。
- Windows Java classpath 分隔符为 `;`，Unix 为 `:`。
- Windows 原生工具必须是适配 Windows 的可执行文件和 DLL，Linux ELF 文件不可直接执行。
- 远程桌面、键盘 Fn 模式、输入法可能截获快捷键；先使用调试工具栏确认功能。
- 需要详细 Windows 用例时，参见 [Windows 测试指南](GAUSSDB_WINDOWS_TEST_GUIDE.md)。该文是测试方法，不是 Windows 已通过报告。

## 5. Linux / 麒麟 V10 安装与启动

### 5.1 采集环境信息

```sh
uname -m
cat /etc/os-release
ldd --version
printf 'DISPLAY=%s\n' "$DISPLAY"
```

麒麟 V10 有不同版本和 CPU，不能只凭系统名称选包。aarch64 对应 ARM64；x86_64 对应 Intel/AMD 64 位。

### 5.2 图形环境要求

DBeaver 是图形客户端。服务器版 Linux 只有 SSH 命令行时，有三种选择：

1. **直接在自己的 Windows/Mac 上运行 DBeaver 连接服务器**，服务器无需安装桌面。
2. 经 IT 批准，在 Linux 服务器提供本地桌面或远程桌面，并在该图形会话内启动。
3. 使用经管理员配置、允许的 X11 转发；需客户端 X server、服务端策略和网络都满足要求。

不要只手工写一个 `DISPLAY=:0` 就认为已有图形环境，也不要使用 `xhost +` 放开所有访问。Xvfb 是测试用虚拟显示设施，单独启动它不会给客户一个可操作桌面。

### 5.3 麒麟专用包与运行依赖

历史交付文件名：

- `dbeaver-gaussdb-26.2.0-kylin-v10-aarch64.tar.gz`
- `dbeaver-gaussdb-26.2.0-kylin-v10-x86_64.tar.gz`

这些名称对应 09-09 归档，不代表包含 09-18 修复。最新包应由交付方重新构建并给出新的清单。

麒麟实测用户空间为 glibc 2.28、GTK 3.24.21。普通 Linux 产品曾因启动器/SWT 要求更高 glibc 无法启动，所以专用包重建了匹配的原生库。**不要将其他系统的 glibc 拷贝覆盖麒麟系统库。**

历史测试镜像使用以下运行依赖；由管理员通过客户获授权的软件源确认包名与版本后安装，不要求使用本地测试镜像：

```sh
sudo yum install gtk3 alsa-lib libXtst dejavu-sans-fonts webkit2gtk3 dbus-x11
```

Ubuntu 等发行版的包名、WebKit ABI 与软件源不同，不能直接照抄这条 yum 命令；按交付说明和实际动态库缺失项准备。本文不声称提供所有 Linux 发行版通用的一键安装脚本。

### 5.4 校验和解压

在交付目录核验 `SHA256SUMS`（按清单覆盖范围准备文件），然后解压对应架构到新的用户目录：

```sh
sha256sum -c SHA256SUMS
mkdir -p "$HOME/apps/gaussdb-client-20260909" "$HOME/DBeaver-GaussDB/workspace"
tar -xzf dbeaver-gaussdb-26.2.0-kylin-v10-aarch64.tar.gz -C "$HOME/apps/gaussdb-client-20260909"
```

上例仅用于 ARM64 历史包；x86_64 替换文件名，最新交付替换版本目录。确认解压后的实际结构，在图形终端进入含 `dbeaver` 可执行文件的目录：

```sh
./dbeaver -data "$HOME/DBeaver-GaussDB/workspace"
```

包内 JRE 与架构匹配，不要求修改系统默认 Java。不要以 root 运行桌面客户端来掩盖权限错误。

### 5.5 启动故障的先后顺序

1. `file ./dbeaver` 确认架构；`uname -m` 对比宿主架构。
2. `ldd ./dbeaver` 检查启动器缺失库。只对可信交付文件运行该命令；SWT/JRE 依赖还需分别检查。
3. 确认实际图形会话可用、有窗口管理器，远程桌面没有拦截功能键。
4. `./dbeaver -data /新的工作区 -consoleLog` 收集启动错误。
5. GLIBC 版本错误应换适配包/由交付方重建；缺 GTK/WebKit 由管理员安装匹配依赖。

麒麟构建细节见 [专用包说明](product/community/kylin/README.zh-CN.md)。

## 6. JDBC 驱动、网络和连接设置（三平台通用）

### 6.1 首次启动

1. 完成首次配置，启用 **Procedure debugger**。AI、统计、每日提示不是 GaussDB 的必需功能，按企业要求选择。
2. 在帮助/关于中的安装详情核对四个插件：ext.gaussdb、ext.gaussdb.ui、ext.gaussdb.debug.core、ext.gaussdb.debug.ui（完整前缀均为 org.jkiss.dbeaver）。
3. 若没有调试入口，先核对插件和功能启用状态，再核对数据库能力；不要盲目安装上游 PostgreSQL 调试器替代 GaussDB 协议适配。

### 6.2 选择正确的驱动条目

数据库 → 新建数据库连接，搜索 GaussDB。驱动 jar 应由 DBA/供应方提供，放到稳定位置，在“编辑驱动设置 → Libraries/库 → Add File/添加文件”选择。

| 条目 | 驱动类 | URL 样式 | 注意 |
|---|---|---|---|
| GaussDB Native | com.huawei.gaussdb.jdbc.Driver | jdbc:gaussdb://主机:端口/数据库 | 使用匹配此驱动类的原厂 jar |
| GaussDB | org.postgresql.Driver | jdbc:postgresql://主机:端口/数据库 | 使用目标版本匹配的 gsjdbc4；不能只凭类名认定是社区 PG 驱动 |
| GaussDB M (version-matched gsjdbc4) | org.postgresql.Driver | jdbc:postgresql://主机:端口/数据库 | M 专用配置，保留其 M 兼容属性；不启用 PL 调试 |

不要同时混放多个定义同一驱动类的 jar；修改已有共享驱动可能影响其他连接，可先复制驱动配置用于验证。只换 jar 不换错配的 class/URL，会导致找不到驱动或 URL 不被接受。M 模式不意味着应改用 MySQL Connector/J。

历史麒麟包不内置原厂 JDBC，未取得再分发依据不能擅自打包。离线客户需提前准备 jar，而不是等向导联网下载。驱动预设端口 8000/5432 均只是默认值，实际以 DBA 为准。

### 6.3 填写连接并验证

填写主机、端口、数据库、用户名和密码。数据库名称必须明确，不能只知道 schema 名称。密码从界面安全输入，不拼到分享的 JDBC URL 或命令行中。

按组织要求配置 VPN/SSH/TLS，再点击 Test Connection。成功后保存并打开 SQL 编辑器，分别运行：

```sql
SELECT version();
SELECT current_database(), current_user;
SELECT name, setting FROM pg_settings WHERE name = 'sql_compatibility';
SELECT datname, datcompatibility FROM pg_database WHERE datname = current_database();
```

目录列/权限因版本不同可能报错，保留原始诊断并交 DBA 核实；不要自行修改系统目录。连通性、模式识别、调试权限、包能力是独立检查，Test Connection 成功不表示后三项一定满足。

### 6.4 远程服务器与 Docker 地址

- 同事从另一台电脑访问数据库：填写服务器可达地址及实际开放端口，不填 127.0.0.1。
- 数据库跑在服务器 Docker 中：填写宿主机地址和映射出的端口，不依赖容器临时 IP。
- `host.docker.internal` 是特定 Docker 环境的辅助名称，不是客户数据库的统一地址。
- 客户端 SSH 隧道转发时，按 DBeaver 隧道页面实际配置处理地址；不要同时手工转发又无意启用第二层隧道。
- 分布式出现 SELECT 成功但 COMMIT 卡住，应由 DBA 检查 CN/DN/GTM 与集群事务状态，不在客户端试验修改系统表/一致性参数。

## 7. SQL、对象管理与兼容模式

连接后展开数据库、schema、表/视图/函数/存储过程。只显示有权限且当前服务器支持的对象；元数据变更后可刷新节点。

兼容模式由数据库决定，**不是客户端下拉切换一下就能让现有数据库变成另一种模式**。有建库权限时，本分支的创建数据库界面提供 DBCOMPATIBILITY 选择，仍须服务器支持。

| 模式识别 | 使用注意 |
|---|---|
| ORA/A | Oracle 兼容语法；包功能还要求支持包的集中式环境 |
| MYSQL/B | B 兼容模式；不是对任意 MySQL 语法无条件支持 |
| TD/C | 按服务器提供的能力使用，不把模式识别通过当作全部 SQL 验收 |
| PG | 使用对应 PG 兼容语法；ON CONFLICT 等按能力门控 |
| M | 本分支关闭存储过程创建、PL 调试和包功能；能浏览普通函数不代表支持 PL/SQL |

运行 SQL 前核对编辑器绑定的连接和数据库，特别是多窗口/多库时。数据编辑前确认自动提交设置；涉及 DDL 的提交行为以服务器为准，不把“回滚”视为任何操作的撤销键。

执行 PL/SQL 定义脚本时，块内分号属于过程正文，不能随意拆开。gsql 的 `\set` 是客户端命令，不是服务器 SQL；`/` 的块结束处理须与当前编辑器脚本分隔配置匹配。先在专用测试库验证脚本执行方式。

## 8. 存储过程 / 函数调试

### 8.1 调试的前提

这里调试的是服务器 PL/SQL/PL/pgSQL 例程，不是用 gdb 调试数据库内核 C/C++ 进程。客户端通过 JDBC 调用 DBE_PLDEBUGGER，使用目标执行连接与控制连接协作。

启动前需要：GaussDB 数据源、受支持模式、可调试例程语言、匹配的 API 签名，以及当前用户的必要权限。DBA 按版本确认 gs_role_pldebugger 等授权；不要通过授予超级用户权限绕过检查。M 模式不满足条件。

### 8.2 启动一次调试

1. 使用独立测试数据库/schema，选无不可逆外部副作用的例程。
2. 在导航树选择目标函数/过程，通过 Debug/调试操作进入配置；核对数据库、schema、例程全名及重载参数。
3. 配置参数。Value 是实际输入值，SQL NULL 是空值，DEFAULT 表示让服务端使用默认参数；空字符串与 NULL 不同。只有对象确有默认参数时才能使用 DEFAULT，并检查是否造成重载歧义。
4. 点击 Debug，进入调试透视图。若需要，打开 Debug、Breakpoints、Variables、Expressions/Watch 等视图。
5. 等待实际暂停行高亮后再单步。不要把普通 SQL 编辑器中相同文本当成已经附加的调试源码。

### 8.3 操作对照

| 操作 | 快捷键 / 使用方法 | 应观察的结果 |
|---|---|---|
| 单步进入 | F7 | 进入被调用的可调试例程或到下一执行位置 |
| 单步跳过 | F8 | 不进入子过程，执行到下一位置 |
| 单步退出 | Shift+F7 | 返回调用方；检查栈及源码位置 |
| 继续 | F9 | 命中下一个有效断点或执行结束 |
| 终止 | F10 | 调试结束；随后独立确认测试数据状态 |
| 设置/删除断点 | 在调试源码左侧标尺双击 | Breakpoints 中出现/消失；重新执行应验证是否真正命中 |
| 启用/禁用断点 | Breakpoints 勾选状态 | 下次运行分别命中/跳过 |
| 修改变量 | 暂停状态，在 Variables 编辑可修改项 | 刷新后读回新值，不只看编辑框内容 |
| 监视变量 | 在 Expressions/Watch 添加变量名 | 当前帧中显示对应值；不支持任意表达式求值 |
| 栈导航 | 双击 Debug 中父/子栈帧 | 定位对应 schema、例程与源码行 |

快捷键只针对 GaussDB 调试上下文，不能推断 PostgreSQL 调试具有同样能力。远程桌面或系统占用按键时，改点相应工具栏动作定位问题。

### 8.4 提交 / 回滚与异常

正常调试完成后，按对话框选择 Commit 或 Rollback。验证结果应另开独立数据库连接查询，避免只在原事务内部观察数据。

过程内主动提交、自治事务、外部网络请求/文件写入等，不能保证被最终 Rollback 撤销。因此禁止拿实际扣款、通知发送等业务过程做无隔离的调试演示。

包含 09-18 修复的版本：事务完成阶段将网络读等待限制为 10 秒或已有更短值，正常步进不采用此统一短超时。丢失提交响应时会报告“结果未确认”，并拒绝重试。**超时不等于未提交；必须通过独立连接核查，不要重复点击提交或重复执行业务过程。**

旧工作区断点升级后可能缺少数据库身份，无法继续下发。删除这些旧断点，在正确数据库的实际调试源码中重新建立；不要复用同名不同库的断点配置。

## 9. 包编译、错误定位与批量删除

### 9.1 环境要求和含义

当前包功能要求支持该语法及目录的 **集中式 ORA 库**。并非所有 GaussDB 发行包或分布式环境支持 CREATE PACKAGE。缺少能力时换客户端不能解决，需要 DBA 提供合适实例。

编译是服务端校验/重编译包定义与依赖，不是运行包内业务过程，更不是在客户电脑上编译出可执行文件。

### 9.2 使用步骤

1. 展开目标 schema 的 Packages，打开包的声明和主体编辑器。
2. 修改前另存源码；保存定义与“对既有定义重编译”是两个操作，不要混淆。
3. 右键包，按需选择 Compile Package、Compile Package Specification、Compile Package Body。
4. 查看编译结果；有错误时确认是 SPEC 还是 BODY，双击诊断定位对应编辑器和行号，修正后再次编译。
5. 若提示诊断目录无法读取/权限不足，不能当作编译通过，也不能当成源码某一行语法错误。将 SQLSTATE 和诊断交 DBA 检查。

实际服务端命令如下，`app_schema.pkg_demo` 是占位，手工执行前替换为确切目标：

```sql
ALTER PACKAGE app_schema.pkg_demo COMPILE;
ALTER PACKAGE app_schema.pkg_demo COMPILE SPECIFICATION;
ALTER PACKAGE app_schema.pkg_demo COMPILE BODY;
```

SPEC 修改可能影响 BODY/其他依赖；编译操作也需要权限和适当维护窗口。诊断读取使用服务端 dbe_pldeveloper.gs_errors 等信息，实际以版本支持为准。

### 9.3 多选与删除

在导航树使用平台多选操作选择目标包，可执行相应批量操作。删除前检查确认框中每个 schema/包名，备份定义并评估依赖。**批量删除是服务器真实 DROP，不是从界面隐藏；不能保证可用事务回滚恢复。** 本指南不提供默认删除已有包的脚本。

## 10. 原生备份、恢复与脚本执行

### 10.1 先区分 JDBC 与原生工具

普通查询、编辑、调试、包编译走 JDBC，不要求安装 gs_dump。原生备份恢复会由 DBeaver 启动客户端机器上的工具进程，工具文件及其依赖不随当前交付包提供。

| 环境 | 从 DBeaver 内启动原生工具的前提 |
|---|---|
| Linux | 与客户端 Linux/CPU/系统库匹配的原厂 gsql、gs_dump、gs_restore（需要全库备份时另有 gs_dumpall） |
| Windows | 有可用且经过验证的 Windows 版本工具及其 DLL；不能使用服务器 Linux 文件 |
| macOS | 有可用且经过验证的 macOS 版本工具及其动态库；不能使用 Linux 文件 |
| 无匹配工具 | DBeaver 继续使用 JDBC 功能；备份恢复由获授权的 Linux 管理机/服务器执行，属于独立操作流程 |

没有证据证明供应方提供了当前客户版本的 Windows/macOS 原生工具，不作可下载/已支持承诺。DBeaver 的 SSH 连接隧道只转发网络，不自动把 gs_dump 变成在服务器远程执行。

### 10.2 配置 Client Home

1. 从数据库供应方取得服务端版本兼容、运行平台匹配的客户端套件，保留 bin/lib 的相对布局。
2. 在可信工具目录检查版本，如 `gs_dump --version`、`gs_restore --version`、`gsql --version`，记录输出。
3. 在连接/原生任务向导的 Local Client / Client Home 设置中选择安装根目录；确认解析到实际工具，而不是系统里的 pg_dump。
4. 本分支将 pg_dump/pg_restore/psql/pg_dumpall 映射为 gs_dump/gs_restore/gsql/gs_dumpall，不通过文件改名伪装平台兼容性。
5. 先用临时数据库、小 schema 做往返测试，再考虑获授权的数据备份。

### 10.3 备份与恢复操作

1. 在目标库的 Tools/工具菜单选择相应备份任务，核对数据库、schema/对象范围、输出路径、格式。
2. PLAIN 是 SQL 脚本，恢复走 gsql；CUSTOM 等归档格式按工具支持走 gs_restore。不能把压缩归档当 SQL 文本执行。
3. 先恢复到新的、专用的验收库。核对“清理/删除已有对象”等选项，避免覆盖业务库。
4. 任务结束后同时检查退出状态、日志、目标文件和独立 SQL 查询。文件非空、日志出现 Complete 都不能单独证明恢复正确。
5. 恢复后核对表/对象数、行数、关键数据及权限；密码、角色、扩展和版本差异应单独处理。

包含 09-18 修复的版本给 GaussDB 工具添加 --pipeline，以 stdin 输入密码，不将自动传入的密码放到 argv；PG 仍保留原路径。CR/LF/NUL 密码会明确拒绝，不能偷偷截断。不要在“额外参数”里手填密码或依赖 PGPASSWORD 绕过该流程。

全库备份的角色密码脱敏与恢复策略不同于完整保留凭据的原厂备份：本分支相关发布路径会以 PASSWORD DISABLE 替代敏感密码内容，恢复后由 DBA 重置/配置账号。不能将它当成无损迁移全部登录凭据。备份即使不含密码也可能含业务敏感数据，应加密保存并限制访问。

### 10.4 验证边界

09-18 已使用 GaussDB 507 Linux 原厂工具、普通账号 TCP 连接验证生产管道传密方法、明文与自定义格式往返恢复、大 stdout 不阻塞。工具运行于 Linux 容器，由 macOS 测试夹具发起，**不是 macOS 原生工具通过，也不是 Windows 工具或各平台任务 GUI 全部通过**。gs_dumpall 的新认证路径未在该轮单独真库验收。

## 11. 现场冒烟检查（每台交付机器都执行）

使用获批准的可删除测试库和测试对象；预期不满足时记 FAIL/BLOCKED，不直接填 PASS。

| 编号 | 操作 | 通过判据 |
|---|---|---|
| S01 | 核对文件校验值、OS/CPU/JRE、插件 | 与交付清单一致，不混入测试插件 |
| S02 | 普通用户首次启动、退出、再次启动 | 无加载错误，工作区可写，保存连接仍存在 |
| S03 | 加载官方 JDBC，测试连接及版本查询 | 连接到预期库/用户/服务器 build |
| S04 | 查看兼容模式与导航树 | 对应库能力正确，M 不显示可用 PL 调试 |
| S05 | 在测试表分别提交和回滚独立事务 | 另一个连接看到提交、看不到回滚的新增数据 |
| S06 | 调试已知无副作用的测试过程 | 暂停行正确，五个指定动作正常生效 |
| S07 | 断点设置、命中、禁用、启用、删除 | 不只 UI 变动，重新执行的命中行为也正确 |
| S08 | 查看/修改变量与添加变量名 Watch | 刷新读回修改后的值 |
| S09 | 进入跨 schema 子例程并双击父帧 | 栈、schema、源码、行号均正确 |
| S10 | 调试结束分别 Commit/Rollback，再做 F10 | 对话框和独立数据结果一致 |
| S11 | 支持包的 ORA 环境执行三种编译 | 成功结果可信；故意错误能定位正确 SPEC/BODY 行 |
| S12 | 删除两个预先创建的专用测试包 | 确认列表正确，数据库确已删除，无额外对象受影响 |
| S13 | 有匹配工具时备份并恢复到新库 | 实际数据一致；无工具则记 BLOCKED/不适用并说明 |

记录模板：日期、人员、OS/CPU、产品哈希、源码版本/补丁状态、JRE、JDBC 版本/哈希、数据库完整 build/模式、步骤、预期、实际、PASS/FAIL/BLOCKED/NOT RUN、截图/日志、清理结果。

已有测试脚本含 CREATE OR REPLACE、故意错误及客户端元命令，执行前阅读：

- [嵌套过程 fixture](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/fixtures.sql)
- [跨 schema fixture](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/cross-schema-gaussdb.sql)
- [包边界 fixture](test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/package-boundary-fixtures.sql)

不要把测试脚本直接指向生产库；多轮结果须记录初始基线，不能为制造“预期行数”清空共享表。

## 12. 升级、迁移与故障排查

### 12.1 安全升级

1. 正常退出客户端，备份工作区、用户脚本、驱动和旧安装包；备份按敏感文件管理。
2. 将新产品装到新目录，核对 SHA 与修复清单，不直接覆盖 plugins 中几个 jar。
3. 首先用新的 workspace 跑第 11 节冒烟；需要验证旧配置迁移时使用旧 workspace 的副本。
4. 新版可能升级 workspace 格式。回退时使用旧包及升级前备份，不能保证旧程序可读新版写过的工作区。
5. 正式迁移后逐个核对连接、密码输入、TLS/SSH 路径和数据库名；跨平台路径要重新选择。
6. 经修复新增数据库隔离要求的旧断点应在正确目标下重建。

### 12.2 常见问题表

| 现象 | 优先检查 | 不应做的事 |
|---|---|---|
| Exec format / bad CPU type | 客户端/工具架构与 OS 是否匹配 | 只改扩展名或复制别的平台 JRE |
| GLIBC_x.xx not found | 是否使用麒麟专用适配包 | 覆盖系统 libc |
| cannot open display | 真实图形会话、远程桌面/X11 授权 | xhost + 或 root 强行运行 |
| 无法创建浏览器视图 | WebKit 依赖、AI 功能与已修复产品版本 | 忽略报错宣称完整启动通过 |
| driver class not found | jar/class/URL 对应关系、重复 jar | 把两个不匹配驱动混装 |
| 连接超时/拒绝 | VPN、DNS、端口映射、防火墙、监听地址 | 直接开放公网全部 IP |
| 密码错误 | 正确用户、数据库、驱动/认证协议、访问规则 | 把密码贴到日志/命令行求助 |
| SSL 主机名不匹配 | 使用证书对应主机名和正确 CA | 永久关闭校验 |
| Debug 不显示/被拒绝 | 插件启用、模式、语言、API、权限 | M 库强行启用 PL 调试 |
| 快捷键没反应 | 调试上下文/窗口焦点、Fn、远程桌面拦截 | 单凭按键没反应断言协议失败 |
| Watch 表达式错误 | 是否只是受支持的变量名、当前栈帧 | 当作完整表达式求值器 |
| 包编译入口没有/CREATE PACKAGE 失败 | 集中式 ORA、服务端发行包/版本能力 | 修改客户端菜单当作服务器能力已补齐 |
| 错误目录读取失败 | gs_errors 是否存在/授权、SQLSTATE | 宣称编译成功或伪造源码行号 |
| 提交超时/连接中断 | 另一个连接核实实际事务结果 | 重复提交或立即重跑业务过程 |
| 原生工具找不到 | Client Home、工具平台、bin/lib 布局 | 用 pg_dump 改名冒充 gs_dump |
| 工作区被占用 | 是否另有客户端使用同一 -data | 进程仍在时强删锁文件 |

### 12.3 提供给维护人员的资料

使用本文指定的 -data 后，主日志路径明确为 `<workspace>/.metadata/.log`；同时可从 Error Log 视图获取异常。启动尚未建立工作区时，提供控制台输出及系统告警截图。

需提供：操作时间、平台版本/架构、产品版本/哈希、实际 JVM、驱动类与 jar 版本、数据库 build/模式、SQLSTATE、最小复现步骤、脱敏日志。不要提交密码、令牌、私钥、完整安全存储或含业务数据的 dump。服务器日志由 DBA 按同一时间段收集。

## 13. 源码构建与参考资料

普通客户优先使用可追溯的完整产品，不要求自行编译。开发人员需要构建时，仓库为 [jarrenL/dbeaver](https://github.com/jarrenL/dbeaver/tree/feature/gaussdb-compatibility)，分支 feature/gaussdb-compatibility。远端是否包含本地最新补丁须先核实。

将 dbeaver 和 dbeaver-common 并列放置，按产品构建记录固定依赖提交、JDK 和 Maven/P2 源，再从 dbeaver 根目录运行：

```sh
../dbeaver-common/mvnw package -f product/aggregate/pom.xml -Pproduct-dbeaver-ce,product-dbeaver-eclipse-ce -T 1C
```

Windows 使用对应包装器：

```powershell
..\dbeaver-common\mvnw.cmd package -f product\aggregate\pom.xml -Pproduct-dbeaver-ce,product-dbeaver-eclipse-ce -T 1C
if ($LASTEXITCODE -ne 0) { throw 'Build failed; do not distribute stale products' }
```

检查 `product/community/target/products` 中实际输出，不能只凭目录里有旧文件就认定本轮构建成功。麒麟还须按专用脚本重建/组装适合 glibc 2.28 的同版本 SWT/启动器，再做干净包验证。完整构建、签名/公证、归档、许可证与目标机安装测试是分别需要完成的环节。

参考资料：

- [兼容性实现说明](plugins/org.jkiss.dbeaver.ext.gaussdb/README_GAUSSDB_COMPATIBILITY.md)
- [原始需求、架构和历史测试汇总](GAUSSDB_REQUIREMENTS_ARCHITECTURE_TEST_SUMMARY_20260911.md)
- [麒麟历史验收记录](GAUSSDB_KYLIN_LINUX_ACCEPTANCE_20260909.md)
- [麒麟安装及原生构建说明](product/community/kylin/README.zh-CN.md)
- [Windows 详细测试方案](GAUSSDB_WINDOWS_TEST_GUIDE.md)
- [09-18 三项修复及真库验证](plugins/org.jkiss.dbeaver.ext.gaussdb/GAUSSDB_THREE_FIXES_VALIDATION_20260918.md)

交付结论始终以“实际拿到的包 + 对应验证记录”为准，不把本指南的操作步骤当作每个平台都已经执行通过的证明。
