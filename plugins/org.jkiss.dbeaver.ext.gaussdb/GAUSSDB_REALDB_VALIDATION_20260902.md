# GaussDB 507 真库验证报告（2026-09-02）

> 验证环境：GaussDB 507 Docker，分布式部署
>
> 对应分支：`feature/gaussdb-compatibility`
>
> 本报告记录真实数据库结果；Package 编译必须在集中式 ORA 环境完成后续验收。

> 2026-09-05 补充：新增的 M/未知模式、routine language、17 个 API 精确签名、API/目标
> routine EXECUTE 权限和调试角色门控，已在本机 GaussDB 507 Docker 的 ORA、MYSQL、M
> 数据库完成 catalog 回归；完整 DBeaver UI 冒烟仍需随产品构建执行。

> 507 回查发现 `add_breakpoint` 的 catalog 签名为 `(oid,integer)`，而新版参考文档记为
> `(text,integer)`；插件已显式兼容两者。调试函数 `proacl` 为空，不能单靠函数 ACL 判断
> 调试授权，因此同时检查 system administrator/superuser 或 `gs_role_pldebugger` 成员身份。
> ORA 库的 `public.dbeaver_debug_target` 已确认是持久化 `plpgsql` procedure，当前用户具备
> 目标 EXECUTE 与调试身份；MYSQL 库具备所需的全部 17 个签名。M 模式在读取 language/API
> catalog 前直接拒绝，避免该模式对 `proargtypes` 等系统目录操作的兼容性错误。

## 1. 验证结论

原始 7 项需求中，前 6 项已经在 GaussDB 507 分布式真库完成全链路验证，插件行为与
`DBE_PLDEBUGGER` 服务端行为一致。第 7 项 Package 编译的代码、UI 和单元测试已经完成，
但当前分布式环境不支持创建 Package，且不存在 `DBE_PLDEVELOPER.GS_ERRORS`，因此不能在
该环境完成端到端验收。

| 编号 | 功能 | 507 分布式真库结果 | 结论 |
|---|---|---|---|
| 1 | Oracle/B/MySQL 等兼容模式 | 连接探测、模式识别和能力门控通过 | 通过 |
| 2 | 断点调试及 F7/F8/Shift+F7/F9/F10 | 启动、单步、继续、退出和终止链路通过 | 通过 |
| 3 | 断点管理 | 创建、启用、禁用和删除通过 | 通过；重复位置去重补丁已加入 |
| 4 | 变量查看、修改和变量名 Watch | `info_locals`、`set_var` 链路通过 | 通过；不承诺任意表达式 |
| 5 | 调用堆栈 | `backtrace`、OID 源码和行定位通过 | 通过 |
| 6 | 调试提交/回滚 | 执行事务完成选择及异常回滚链路通过 | 通过 |
| 7 | Package 编译、错误定位和批量删除 | 分布式环境不具备服务端能力 | 代码完成，待集中式 ORA 真库验收 |

## 2. 断点编号与重复添加验证

- `DBE_PLDEBUGGER.add_breakpoint` 返回的编号从 `0` 开始。插件以 `-1` 表示“尚未注册”，
  因而 `0` 是合法编号，启用、禁用和删除判断与服务端一致。
- 删除 0 号断点后再次添加，服务端编号继续递增；不能假设已删除编号会被复用。
- DBeaver 本地映射使用 routine OID + 行号查找，不持久化或推算服务端编号。
- 同一 OID + 行号重复添加时，服务端可能生成新编号并使旧映射失效。当前补丁会先删除
  同位置的已注册断点，再调用 `add_breakpoint`，从而防止孤儿服务端断点。
- `add_breakpoint` 没有返回编号或返回 NULL 时，当前实现会明确失败，不把无效描述符加入
  本地 Breakpoints 列表。

## 3. Package 环境限制

当前 507 分布式 Docker 环境的实际结果：

- `gs_package` catalog 存在。
- `CREATE PACKAGE` 被服务端拒绝。
- `DBE_PLDEVELOPER.GS_ERRORS` 不存在。
- 插件对 `42P01` 的可选元数据降级路径符合预期，不会把缺失视图误报成连接故障。

仍需在支持 Package 的集中式 ORA 实例验证：

1. `ALTER PACKAGE <schema>.<name> COMPILE`。
2. `COMPILE SPECIFICATION` 和 `COMPILE BODY`。
3. spec/body 对象状态刷新。
4. `DBE_PLDEVELOPER.GS_ERRORS` 多错误、行号和 source part 映射。
5. 声明/Body 编辑器的错误行跳转。
6. 跨 schema 多选删除及部分失败反馈。

未完成上述集中式测试前，Package 编译应表述为“代码已实现，待目标环境验收”，不能表述
为“GaussDB 所有部署均已验证支持”。

## 4. 其他确认

分布式环境不存在 `DBE_PLDEVELOPER.GS_DEBUG` 视图。当前调试实现不依赖该视图，会话由
`DBE_PLDEBUGGER.turn_on/attach` 及控制 API 管理，因此此差异不影响已验证的调试链路。

## 5. 自动化回归

- 新增同 OID + 行号重复断点匹配测试，并显式覆盖服务端编号 `0`。
- 调试会话初始化只向服务端注册 UI 已启用且全局断点开关开启的断点。
- 原有测试覆盖断点描述符序列化、启用状态、结束标志、变量只读/修改以及 Package 三类
  编译 SQL和错误行解析。
- 自动化测试不能替代第 3 节所列的集中式 Package 真库验收。

## 6. 2026-09-05 调试门控与双会话复测

- ORA 库 `gdbdrv_a_test`：`public.dbeaver_debug_target(6)` 的 OID 为 `172034`，语言为
  `plpgsql`，对象类型为 procedure；目标 EXECUTE、17 个 API 精确签名和调试身份检查通过。
- 双会话执行 `turn_on -> CALL -> attach -> info_locals -> continue -> turn_off` 成功。服务端返回
  调试端点 `gs_single:0`，首次停在过程第 4 行；控制端读到 `p_in=6`、`v_local=0`、
  `v_const=100 (constant)`，继续后返回 `[EXECUTION FINISHED]`。目标事务使用 `ROLLBACK` 收尾。
- MYSQL 库 `gdbdrv_b_test`：所需 17 个 API 签名存在；`add_breakpoint(oid,integer)` 按 507
  实际 catalog 匹配。
- M 库 `gdbdrv_m_test`：该版本对 `p.proargtypes::text` 返回 `Unsupport type`，验证了必须先按
  compatibility 拒绝再读取 routine language/API catalog；当前实现已按此顺序短路。
- 64 个 Maven reactor 模块构建成功；GaussDB model 58 个测试、Debug 11 个测试，共 69 个，
  failure/error 均为 0。
