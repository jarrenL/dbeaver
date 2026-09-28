# 跨模块组件回归记录（2026-09-29）

## 结论与范围

以代码提交 `59aadfcbe2` 为基线，重新编译指定生产源码与测试，通过现有解析依赖启动独立 OSGi 配置。五个模块共 **77 类、1,230 项，全部通过，零失败、错误、跳过**。逐类结果见 [回归证据](evidence/OSGI_COMPONENT_REGRESSION_20260929.json)。

| 模块 | 类数 | 测试数 |
|---|---:|---:|
| GaussDB 核心 | 34 | 629 |
| GaussDB 调试 | 15 | 112 |
| 平台共享 | 21 | 366 |
| PostgreSQL 执行计划 | 4 | 80 |
| 数据编辑器 | 3 | 43 |

这是已有测试的当前版本复验，不是新增 1,230 个场景。前置编辑器/JDBC 联合 59 项与本批有重叠，不能相加；上一轮共享 798 项也不与本批相加。

## 执行方法

测试工程中执行：

```sh
node scripts/run-shared-focused.mjs --sql-editor --gauss-core --gauss-debug --compile-only
node scripts/run-hex-content-focused.mjs
node scripts/run-existing-osgi.mjs <共享编译输出目录> --module=<测试模块名> --all-module
node scripts/run-existing-osgi.mjs <编辑器编译输出目录> --module=org.jkiss.dbeaver.ui.editors.data.test --all-module
```

本轮共享编译目录 `/tmp/shared-focused-S1amSi`；编辑器编译目录 `/tmp/hex-content-test-Rk36yS`。运行器依据 `compiled-sources.json` 按所属 bundle 隔离源码覆盖，逐类检查实际测试报告，必须有执行数且失败、错误、跳过均为零。临时 GUI 目录的 jar 已不完整，两种专项编译脚本改读现有测试模块的已解析 OSGi 配置，明确包含 framework、排除源码包；缺失依赖时报错而非跳过。

## 尚未通过的验证

- 完整 Maven/Tycho 验证本轮再次失败：`results/run-LPALys/maven.log` 记录依赖缓存 `p2-artifacts.properties.tycholock` 等待 10 秒超时，全部测试模块未执行。未删除锁，不能将本批组件结果写为完整构建通过。
- 五个 Live 测试类不在本批执行范围，名单保留在逐类证据中。没有新增 GaussDB 真库测试结果。
- Docker API 当前访问被拒绝，待保存行的重连、显式保存、事务恢复仍需 GUI 续验；未对该现场执行新的保存或故障注入。
- 外部辅助审核初始化时日志目录权限失败，没有取得审核意见。
- 未制作或验收最新完整安装包，未将这批结果认定为最终交付通过。
