# GaussDB 适配版 DBeaver Linux 交付验收记录

## 1. 验收对象

- 源码提交：`2ee3f49ac6c9d4ff825e64f5b4c6ca150a6435a8`
- 产品版本：DBeaver `26.1.5.202610060423`
- 目标平台：麒麟 V10，Linux `x86_64` 与 `aarch64`
- 数据库：GaussDB Kernel 507 分布式实验环境
- JDBC：GaussDB 原生驱动；驱动文件由数据库供应方或客户提供，未打入产品包

## 2. 自动化回归

完整测试集连续执行两次，测试身份和结果完全一致：

| 运行目录 | 总数 | 通过 | 跳过 | 失败 | 错误 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `gaussdb-dbeaver-tests/results/run-KhbNpo` | 2776 | 2758 | 18 | 0 | 0 |
| `gaussdb-dbeaver-tests/results/run-CXfA4h` | 2776 | 2758 | 18 | 0 | 0 |

18 个跳过项没有计入通过，主要是当前实验环境未提供的 TLS/mTLS、GB18030、空集群 `dumpall`、监控权限，以及服务端不支持的部分序列、重载函数、外键和 round-robin 能力。调试专项真库测试为 5/5 通过。

## 3. Linux GUI 与真库连接

在麒麟 V10 用户空间分别验证：

| 架构 | 执行方式 | 结果 |
| --- | --- | --- |
| `aarch64` | ARM64 原生执行 | 启动、中文界面、正常退出、重启、原生 JDBC 连接、数据库与 `postgres` 元数据展开均通过 |
| `x86_64` | ARM 宿主上的 Rosetta 指令仿真 | 启动、中文界面、正常退出、重启、原生 JDBC 连接、数据库与 `postgres` 元数据展开均通过 |

x86_64 的验证证明包内 ELF/JRE/GTK 组合能够在该仿真用户空间运行，不等同于客户 x86_64 整机或桌面云认证。客户现场仍需用实际麒麟桌面、远程桌面输入转发、网络、JDBC、账号和权限进行最终验收。

本轮还复现并修复了 GaussDB 集合类型目录返回 `typcategory=O` 时的元数据加载异常。修复后两个架构的 GUI 均可展开 507 元数据，日志中不再出现 `Invalid type category [O]` 或 `No enum constant PostgreTypeCategory.O`。详细根因见 `GAUSSDB_COLLECTION_CATEGORY_O_VALIDATION_20261006.md`。

## 4. 交付包校验

两个应用目录均通过以下检查：

- 产品构建完成，共 204 个 Maven/Tycho 模块；
- 干净产品检查：878 个文件，无 SWTBot、验收插件、工作区或凭据；
- 逐文件产品清单：1202 个条目，重新校验一致；
- x86_64 启动器为 x86-64 ELF，ARM64 启动器为 ARM aarch64 ELF；
- 分别内置对应架构的 Temurin JRE 21；
- 包含中文资源片段，x86_64 为 118 个，ARM64 为 119 个；
- 从最终压缩包重新解压后再次通过干净产品与逐文件清单校验；
- 三个归档文件均通过 `SHA256SUMS` 校验。

交付目录：`/Users/lj/Documents/GaussDB/deliverables/linux-latest-20261006-PomCVU`。

交付文件：

- `DBeaver-GaussDB-26.1.5-202610060423-KylinV10-x86_64.tar.gz`
- `DBeaver-GaussDB-26.1.5-202610060423-KylinV10-aarch64.tar.gz`
- `DBeaver-GaussDB-26.1.5-202610060423-sources.tar.gz`
- `SHA256SUMS`

## 5. 结论与边界

本次 Linux 双架构交付候选已完成现有本地条件可执行的重复回归、麒麟 GUI、GaussDB 507 真库元数据和归档完整性验证。它不宣称完成客户桌面云整机认证，也不把未提供环境或服务端不支持而跳过的 18 项算作通过。Windows 不在本次 Linux 目标范围内。
