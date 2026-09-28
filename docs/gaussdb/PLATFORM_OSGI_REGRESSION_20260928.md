# 共享平台测试 OSGi 复验

日期：2026-09-28。

## 范围与结论

使用现有已解析的只读依赖，在独立临时 Equinox 配置和工作区启动真实 OSGi 运行环境。当前显式编译的类按所属 bundle 分开覆盖，不将测试与生产类合并到同一类加载器。

本轮选择平台模块中已纳入共享专项的 13 个测试类：认证凭据、连接过滤序列化、网络凭据、日期格式、SQL 恢复策略、过滤器导入导出、历史存储/映射/集合/服务、异常、进度及版本比较。

最终 **273 项通过，0 失败、错误、跳过**，进程退出 0，逐类报告校验通过。普通 Java 联合专项 **705/705 通过，0 跳过**。两批重叠，不能相加。13 类不是平台模块全部测试，也不是完整产品构建或 GUI 验收。

## 测试夹具修正

首次 OSGi 运行 273 项中 265 通过、8 错误：`UserFilterExportRoundTripTest` 在不同 bundle 中直接访问 `FilterMapping` 的包级构造器和字段。相同 Java 包名不代表具有同一运行时包访问权限。

改为反射构造测试数据后，第二轮暴露同类中 `DataSourceDescriptor.setObjectFilter` 的受保护访问；未完成的 Mockito 验证还污染了后续测试，合计 9 项错误。最终通过反射设置夹具、读取 Mockito 已记录调用的方式检查方法名称、次数、类型、作用域及导入过滤器，不再直接跨 bundle 调用非公开方法。

本次未扩大生产 API 可见范围，也未跳过或删除失败测试。它是此前新增测试的夹具缺陷，不是客户端功能缺陷。历史存储最新字段校验与生命周期测试也在最终批次通过。

## 复现记录

测试仓命令：

```sh
node scripts/run-shared-focused.mjs
node scripts/run-existing-osgi.mjs /tmp/shared-focused-DYz00R \
  --module=org.jkiss.dbeaver.test.platform --all-module
```

临时编译路径随运行变化，修改源码后必须重新编译，不能复用旧类声称验证新代码。

- 首轮：`/tmp/osgi-platform-current-20260928.log`
- 中间失败：`/tmp/osgi-platform-fixture-green-20260928.log`（名称含 green，但实际失败，不作为通过证据）
- 最终 OSGi：`/tmp/osgi-platform-fixture-final-20260928.log`
- 最终普通专项：`/tmp/shared-platform-fixture-final-20260928.log`

## 同轮环境复查

Docker 容器列表读取成功，Linux 验收与 GaussDB 507 容器存在。但 Java 真库执行计划 6 项仍全部在连接时遇到 `SocketException: Operation not permitted`，0 通过；未执行 SQL，不能认定数据库功能失败或通过。证据：`/tmp/live-plan-recheck-current-20260928.log`。

外部辅助审查进程初始化仍因状态目录不可写失败，未产生有效审核结论。容器可列举不代表 Java 网络访问、Linux GUI 或完整构建已恢复。
