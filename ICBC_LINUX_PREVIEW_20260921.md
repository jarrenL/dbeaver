# 麒麟 Linux x86_64 安装与使用说明（26.1.5）

本说明适用于 DBeaver 26.1.5 GaussDB 适配版。当前发布标记：gaussdb-2615-20260922，同时提供 Linux 与 Windows x86_64 包，已纳入中文设置修复。平台验证范围见下文；发布标记是固定下载标识。逐步操作见 [图形界面使用说明书](GAUSSDB_GUI_OPERATION_MANUAL_20260922.md)。

## 安装

1. 在麒麟图形桌面确认架构为 x86_64；本包不适用于 ARM64 或 Windows。
2. 下载 dbeaver-gaussdb-26.1.5-kylin-v10-x86_64-preview.tar.gz 和 SHA256SUMS，核对 SHA-256。需保留并同时提供 accompanying-sources.tar.gz（对应运行时与重建原生组件源码）。
3. 解压到普通用户可写目录，进入 dbeaver，运行 ./dbeaver。包内有匹配的 JRE 21，不需要覆盖系统 Java/glibc。
4. 要求 GTK3 等系统图形依赖，详情见 product/community/kylin/README.zh-CN.md（归档内为 README.kylin.zh-CN.md）。只有 SSH 命令行不能使用交互界面。
5. 首次配置启用 Procedure debugger；选择 GaussDB Native，添加客户或供应商提供的版本匹配 JDBC jar，再填写真实数据库地址、端口、库名和账号。驱动因再分发许可未确认，不内置。
6. O（Oracle）模式用于 PL/SQL/Package；M 仅验收基础连接、SQL、表和数据操作。不将 M 不支持 PL/SQL 列为缺陷。

## 本轮修复与验收边界

- 已修复断点重复启停错误，Linux GUI 重复启停及再次命中通过。
- 已修复首次进入跨 schema 子过程源码定位竞态，Linux GUI 首次进入和调用栈导航通过。
- 已修复 CREATE PACKAGE 拆分与分号处理；12 项解析单测及 GaussDB 507 上复杂包创建、编译、执行、删除的 Linux GUI 场景通过。
- 新版批量删包GUI、M全部对象菜单、客户实际桌面云、两个指定金融版以及ARM64新版未完成本轮验收。

修复与最新结果详见 ICBC_LINUX_FIX_PROGRESS_20260921.md 和 ICBC_COMPLEX_SYNTAX_ACCEPTANCE_20260921.md；ICBC_2615_LINUX_GUI_ACCEPTANCE_20260921.md 保留历史失败记录。本包源自已做GUI测试的干净产品副本，不包含SWTBot、测试工作区、密码、数据库服务端或gs_dump等原生工具。属于本地unsigned定制构建，不声称麒麟/Eclipse官方认证。Windows 包为同源交叉构建，尚未完成 Windows 实机 GUI 验证。

源码以此发布tag固定；BUILD-MANIFEST.txt记录源码提交、产品时间戳、共享依赖和原生库来源。DBeaver及各插件许可证、JRE legal随包保留。SWT/Equinox/JRE对应源码在accompanying-sources.tar.gz；原生修改及构建方法见build-natives.sh和THIRD-PARTY-SOURCES.md。
