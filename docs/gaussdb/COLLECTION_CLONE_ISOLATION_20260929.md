# 集合值显式复制隔离（2026-09-29）

对应历史清单数据编辑、取消修改、数组/复合值处理。直接验证真实 `JDBCCollection.cloneValue`，不将模型复制等同于 GUI 撤销验收。

## 后续复核：嵌套资源清理

新增两项复核暴露并修正上一轮清理逻辑的嵌套遗漏：外层后续元素复制失败时，已成功复制的内层集合调用 `release()` 只清空集合引用，没有释放里面新复制的资源。第一项红测明确记录叶子副本的 `release()` 未被调用。

失败清理现按原集合/副本成对递归，仅处理不同身份的复制值。第二项同时验证：内层资源清理抛错记入原异常的 suppressed；继续清理外层其他子副本；identity clone 和全部原值不被释放，原嵌套结构保持。这些是实际集合配合模拟资源对象的释放调用验证，不等于真实磁盘/数据库 LOB 资源压力测试。

修复后共享 **890/890**，OSGi 平台 **22 类 377/377**，PostgreSQL **9 类 159/159**，全部零失败、错误、跳过。日志 `/tmp/nested-clone-red-20260929.log`（889 项中 1 失败）、`/tmp/nested-clone-pass-20260929.log`、`/tmp/nested-clone-platform-20260929.log`、`/tmp/nested-clone-pg-20260929.log`；编译目录 `/tmp/shared-focused-q3pwHq`。本次仅新增两项，不与旧批次叠加计数；仍没有初始加载、真库或 GUI 完整验收结论。

## 新增九项测试

1. 构造时复制外层输入数组，后续修改输入数组不改变已构造值。
2. 修改副本不影响原值或原值 modified 标志，类型和 handler 保留。
3. 嵌套 JDBCCollection 复制后子项修改隔离。
4. NULL 集合和空集合复制后仍区分。
5. 释放副本不清空原集合。
6. 子值复制失败必须传播原异常，原值保持，同对象可显式重试。
7. 后续子项复制失败时，仅释放此前成功创建的子副本。
8. 子副本清理失败成为 suppressed 异常，不覆盖原失败、不阻止其他副本清理。
9. 子项返回自身的 identity clone 不应在后续失败时被释放。

## 缺陷与修复

原 `cloneValue` 调用用于初始构造的公开构造器；该构造器捕获子值复制失败并借用原值，显式复制因此可能返回共享可变子对象。前七项红测中两项失败。

显式复制现在单独创建候选集合并逐项复制：成功才返回；DBCException 或 RuntimeException 时清理新建子副本、保留原值并传播原异常。不更改公开构造器的初始加载兼容行为；初始构造失败时的借用策略仍须结合调用方验证，不能据本次结果称全部构造路径已安全隔离。普通非 DBDValueCloneable 对象仍沿用原有引用语义，本次不是任意 Java 对象的深复制。

## 验证

首轮共享 886 项中 884 通过、2 失败；修复并追加清理边界后 **888/888** 通过。真实 OSGi 平台 **22 类 375/375**、PostgreSQL **9 类 159/159** 通过，全部零失败/错误/跳过。必需类门控新增该类，门控自测 7/7。各批重叠，不相加。

命令入口 `node scripts/run-shared-focused.mjs`；编译目录 `/tmp/shared-focused-i6vtY8` 交给 `run-existing-osgi.mjs` 的平台和 PostgreSQL 模块 `--all-module`。日志 `/tmp/collection-clone-red-20260929.log`、`/tmp/collection-clone-pass-20260929.log`、`/tmp/collection-clone-platform-20260929.log`、`/tmp/collection-clone-pg-20260929.log`。

实际集合对象搭配模拟元数据、handler 和故障子值。Docker API 当前仍不可访问；本次没有 GaussDB 真库、界面撤销、完整构建或新安装包的验收结论。
