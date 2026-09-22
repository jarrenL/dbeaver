# GaussDB 客户文档

适用软件：DBeaver 26.1.5 GaussDB 适配版（2026-09-22 中文界面更新）。

## 使用与部署

- **[图形界面使用说明书（含真实界面截图）](../../GAUSSDB_GUI_OPERATION_MANUAL_20260922.md)**：从启用调试功能开始，逐项说明操作入口、操作步骤和预期结果。
- [跨平台安装与配置](../../GAUSSDB_CROSS_PLATFORM_USER_GUIDE.md)：Linux、Windows、macOS 的平台条件、连接和驱动配置。
- [麒麟 Linux 安装说明](../../ICBC_LINUX_PREVIEW_20260921.md)。
- [Windows 功能检查指南](../../GAUSSDB_WINDOWS_TEST_GUIDE.md)。
- [需求及适用范围](../../ICBC_REQUIREMENTS_SCOPE_20260921.md)。
- [软件包下载](https://github.com/jarrenL/dbeaver/releases/tag/gaussdb-2615-20260922)。发布标记和文件名属于固定下载标识，不应改写后使用。

## 版本与验证范围

PL/SQL 调试、断点、变量监视、调用栈和事务收尾用于受支持的 O/Oracle 环境；Package 还要求服务端包能力。M 模式用于连接、元数据、SQL 和表数据维护。

Linux x86_64 已有麒麟 V10 用户空间与 GaussDB 507 的专项界面验证。Windows 包已构建并检查归档/架构，但仍需 Windows 实机验证。目标金融版、客户实际桌面云和具体业务对象应按部署计划完成现场验收。软件未签名，使用前应校验文件并遵守企业终端准入要求。

## 质量与验证记录

- [中文设置修复与验证](../../GAUSSDB_LANGUAGE_VALIDATION_20260922.md)。
- [断点、源码导航及脚本执行修复记录](../../ICBC_LINUX_FIX_PROGRESS_20260921.md)。
- [复杂包语法验证](../../ICBC_COMPLEX_SYNTAX_ACCEPTANCE_20260921.md)。
- [26.1.5 与 GaussDB 507 验证记录](../../ICBC_2615_507_VALIDATION_20260921.md)。
- [需求、架构及历史测试汇总](../../GAUSSDB_REQUIREMENTS_ARCHITECTURE_TEST_SUMMARY_20260911.md)。

历史记录保留原始执行日期、失败和修复证据，不能将早期未修复状态当成当前功能说明，也不能将某环境通过推广为所有环境通过。日常使用以本页链接的使用说明书为入口。源码构建、自动化用例及原始验证材料面向需要复现问题的技术人员，不是用户安装前置条件。
