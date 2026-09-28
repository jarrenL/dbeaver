# XLSX 追加边界：新增回归场景

对应历史清单 9.2。新增到 `GaussDBExcelExportTest`：

| 方法 | 执行组合 | 检查点 | 当前状态 |
|---|---|---|---|
| appendPreservesExistingFormulasAndCrossSheetReferences | 复用已有 Sheet、新建 Sheet（2项） | 原数值、公式类型与文本、跨表公式重新计算结果、汇总表行数、追加位置、输入文件字节不变 | 专项通过 |
| fullImportedSheetsArePreservedWhenAppendRequiresNewSheet | 两张已有 Sheet 均达到配置行数上限，分别追加2/5/8行（3项） | 原行无覆盖、不超配额，多次新增 Sheet 的表头与全部数据准确、输入文件不变 | 修复后专项通过 |

测试调用生产 `DataExporterXLSX`，真实创建、读取 XLSX 文件；不模拟工作簿内容。它们不等于导出向导 GUI 验收，也不证明客户办公软件兼容。

2026-09-28 尝试运行标准离线回归，结果目录 `run-3ijysI`。构建在取得本机 Maven/Tycho 缓存锁时失败，未执行到新增测试。没有新鲜 Java 测试通过结论，不沿用上轮报告作为本轮结果。

## 直接编译专项验证与修复

标准 Maven 构建失败后，使用现有本机依赖直接 javac（release 21）编译生产 DataExporterXLSX 与完整 GaussDBExcelExportTest，然后用 JUnit Console 运行整类。只读已有依赖，输出到独立临时目录，不修改缓存权限或锁。

- 首次运行缺少 Objenesis 依赖，属装配失败，不计产品缺陷。补齐已有依赖后，63项中62通过、1失败：两张旧表已满时预期新增第3张表，实际仍仅2张。
- 原因：createSheet直接返回已满的导入工作表；切换逻辑没有跳过所有已满表。另外，不能把本次新创建的流式表重新当成导入表处理。
- 修复：初始化时记录原始导入表数，仅遍历这些表，跳过已达到行数上限者；全部已满后新建表。新生成表不进入后续导入复用流程。共享Office导出器受影响，不限GaussDB。
- 首次修复后63项全部通过；增加5/8行连续切表场景后，最新65项全部通过、0跳过、0失败。测试重新打开实际XLSX，检查全部旧行及新增行，两个公式场景还重新计算跨表引用为35。

专项脚本位于独立测试仓 `scripts/run-xlsx-focused.mjs`。当前依赖本机既有验收插件目录、Maven只读依赖和已编译模块；不是通用CI安装脚本。使用JDK25、Java21目标、JUnit Console 1.13.1、Mockito4.8.1与ByteBuddy1.18.8。日志路径 `/tmp/xlsx-focused-rollover.log`，临时编译目录 `/tmp/xlsx-focused-2H673n`。命令：`node scripts/run-xlsx-focused.mjs`。

这不是完整Tycho/OSGi构建结果，不增加上轮1882项成功回归的计数。完整构建、GUI追加向导、同路径文件覆盖及最终安装包仍待验证。

## 混合容量与无表头补验

新增4项专项测试，无进一步生产修改：

- `appendSkipsFullMiddleSheetAndPreservesUnusedTrailingSheet`：四张已有表分别有2/3/1/2行，上限3行，追加3行后准确分配至第1张和第3张；第2张已满跳过，第4张未使用保留。逐格核对所有旧数据、全部新数据和表数，原输入文件字节不变。
- `headerlessAppendUsesEveryRowOfNewSheets`：无表头，上限分别为1/2/3行，旧表已满；追加5行，逐表核对行数、无额外表头、数据顺序、末表剩余行数和旧表不变。

2026-09-28最新直接编译专项运行：69项开始、69通过、0跳过、0失败，日志 `/tmp/xlsx-focused-mixed.log`，编译目录 `/tmp/xlsx-focused-l0Xkk2`。此数字是整类执行数，不是新增69项，更不计入旧的1882项完整回归。

本轮环境同时拒绝 Docker socket 访问，因此未续跑 Linux GUI 和真库。空二进制的 GUI 保存仍待验收，不能由此前参数绑定的真库结果代替。
