# 主配置序列化失败保护

## 问题与修复

`DataSourceSerializerModern.saveDataSources` 原先捕获并仅记录内存 JSON 序列化的 `IOException`，随后继续调用主配置和凭据写入。上层注册表依据正常返回清除 `lastError`，存在部分配置被写出、失败被当作成功的风险。

移除该吞异常分支，沿公开方法已有的 `throws IOException` 传播原始异常。只有 JSON 序列化及关闭成功后才进入主配置文件写入；注册表现有异常分支负责保存错误状态。

## 新增验证

`ConfigurationReadFailureTest.serializationFailureMustNotWritePartialConfiguration` 两项：普通项目、加密项目。采用真实主保存器，模拟过滤器序列化写出部分对象后抛出指定 `IOException`。

1. 修复前两项均失败：公开保存方法未抛出异常。
2. 修复后要求传播同一异常对象，主配置和凭据文件均不得调用写入。
3. 移除失败过滤器后，用同一保存器显式重试，检查实际输出可解密并解析为合法 JSON，包含空 connections，不含残留 saved-filters；凭据清理路径正常执行。

这是序列化阶段故障注入，过滤器和文件管理器为替身，不代表磁盘满、部分物理写入、双文件原子性或 GUI 错误提示已验证。未修改其他异常处理及密钥存储副作用策略。

红测日志：`/tmp/config-serialization-red-20260929.log`。复验日志：`/tmp/config-serialization-green-20260929.log`。诊断构建允许失败后继续收集，其 Maven 退出成功不替代逐项验收校验。

复验配置类 **59/59 通过、零跳过**。73 模块诊断共 2526 项：2356 通过、169 跳过、0 失败、1 错误（Rest 本地网络权限），独立验收校验仍拒绝。脱敏逐项证据见 [回归结果](evidence/CONFIGURATION_SERIALIZATION_REACTOR_20260929.json)。
