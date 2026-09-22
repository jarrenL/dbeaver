# Windows x86_64 安装与打包说明

本包为 GaussDB 适配版，未签名，不代表 DBeaver 上游官方发行或 Windows 认证。Windows 安装、启动、卸载及数据库图形界面仍需在目标 Windows 环境验证；跨平台构建和归档检查不能替代这些验证。

## 用户安装

- Intel/AMD 64 位 Windows 使用 x86_64 包，内置 Temurin Java 21；无需 Docker、WSL 或本地 GaussDB。
- EXE 为当前用户安装，不申请管理员权限。安装到 `%LOCALAPPDATA%\Programs\DBeaver-GaussDB-<release>`，创建开始菜单快捷方式及卸载入口。该版本目录已存在时拒绝覆盖，可先卸载或改用 ZIP 放入全新目录。
- 快捷方式显式使用 `%LOCALAPPDATA%\DBeaver-GaussDB\workspace`。卸载仅删除交付清单中的程序文件，不递归删除工作区或额外添加的文件。仍应在升级前备份配置与脚本，先退出客户端再卸载。
- ZIP 完整解压后运行 `dbeaver.exe -data C:\GaussDBClient\workspace`，不需安装；保留完整 jre/plugins 目录。
- 本包未签名，SmartScreen/企业终端策略可能拦截。核对 Release 的 SHA256SUMS，由 IT 批准；不要关闭系统防护。
- JDBC 和 GaussDB 原生备份工具不捆绑。由 DBA 提供匹配服务器版本的 JDBC，使用 GaussDB Native 或 GaussDB 对应驱动类和 URL 连接远端 CN/集中式入口。
- 首次配置启用 Procedure debugger。M 模式不启用 PL 调试；包功能需要支持它的集中式 ORA。Watch 只支持变量名。
- 包内跨平台指南中的相对源码链接需在源码仓库浏览，完整在线文档见发布页面对应提交。

## Reproduction

1. Build the product using Maven/Tycho as documented in the repository. Source must be committed and identified by its full SHA; do not claim uncommitted code matches a commit.
2. Obtain a matching Windows x64 Temurin 21 JRE from Adoptium. Verify its SHA-256 against the upstream asset metadata before extraction; retain all legal files and publish the corresponding JDK source archive with the release.
3. With Node.js 22+ run `node product/community/windows/assemble.mjs PRODUCT_DIR EXTRACTED_JRE NEW_OUTPUT FULL_SOURCE_SHA`.
4. With NSIS 3 on a supported build host, run `makensis -DRELEASE=20260921 -DPAYLOAD=/absolute/output/dbeaver -DREMOVE_MANIFEST=/absolute/output/remove-files.nsh -DOUTPUT=/absolute/output/dbeaver-gaussdb-26.1.5-windows-x86_64-setup.exe product/community/windows/gaussdb-installer.nsi`. On Windows NSIS, use the appropriate `/D` argument syntax.
5. Archive the same `dbeaver` payload as ZIP. Publish checksums, source SHA, JRE source archive and limitations. Test on Windows before promoting beyond preview.

The generated uninstall manifest contains exact packaged files and non-recursive directory removal only. The installer intentionally has no selectable destination and does not merge into an existing directory. This avoids overwriting a user's existing DBeaver installation or recursively deleting user-added files.
