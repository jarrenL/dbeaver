# 五模块组件回归快照

日期：2026-09-28。源码提交：`8e6d1e7309`。

## 实际执行

| 模块 | 测试类数 | 执行/通过 |
| --- | ---: | ---: |
| GaussDB 核心 | 34 | 629/629 |
| GaussDB 调试 | 15 | 112/112 |
| 共享平台 | 14 | 289/289 |
| 内容编辑器 | 3 | 43/43 |
| PostgreSQL 执行计划 | 4 | 80/80 |

合计 70 类、1,153 项，失败/错误/跳过均为零。各运行进程退出 0，逐类报告门控通过。结果校验器自身 7 个正反例通过，不计入客户端测试数。

本轮增加 OSGi 运行器对 PostgreSQL 测试模块的选择支持，验证计划节点树、保存格式、响应解析及事务保护四个测试类。它们此前已在共享组件执行，本次不是新增 80 个场景，也不是 PostgreSQL 真库或调试器协议回归。

完整逐类计数、日志、报告路径及排除项见[机器可读证据](evidence/OSGI_FIVE_MODULE_REGRESSION_20260928.json)。此前组件快照保留，不覆盖历史证据，也不与本轮相加。

## 执行方式

核心/调试使用 `run-shared-focused.mjs --gauss-core --gauss-debug --compile-only` 重新编译的 `/tmp/shared-focused-hZgAxw`；指定模块运行 `run-existing-osgi.mjs --module=模块名 --all-module`。平台与 PostgreSQL 使用当前源码编译的 `/tmp/shared-focused-kLaSNt`，编辑器使用 `/tmp/hex-content-test-WSZBch`。运行器按源码所属 bundle 隔离类覆盖，并使用既有只读解析依赖。

不是全部生产源码的重新构建。未修改 Maven 缓存、Docker 实例或数据库；原工作区未验证的 GUI 自动化修改未纳入提交或测试。

## 未通过及未验证

完整回归入口重新运行，`gaussdb-dbeaver-tests/results/run-J0Ny2S/maven.log` 记录 Tycho 依赖缓存锁超时，全部测试模块 SKIPPED，退出 1。不是测试通过或新的通过基线；没有删除锁或修改缓存。

Docker API 本轮再次拒绝访问，先前 GUI 命令 264 的结果仍未核实，不重新发送也不计通过。五个 Live 测试类未执行，客户目标版本、最新完整安装包及独立辅助审核尚未验收。整体目标仍未完成，代码尚未推送。
