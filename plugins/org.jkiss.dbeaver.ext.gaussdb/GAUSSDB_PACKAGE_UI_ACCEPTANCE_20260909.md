# 包功能客户端验收（2026-09-09）

> 后续边界验收与修复见 [包编译边界验收](GAUSSDB_PACKAGE_BOUNDARY_ACCEPTANCE_20260909.md)。
> 下文为第一轮历史结果；跨页、多包错误定位与状态刷新已在后续轮次补测。

## 环境与结论

macOS ARM64、SWTBot、原生 GaussDB JDBC、`gaussdb-507-ha-lab` Centralized 507
（BusinessCentralized，A 模式），普通用户 package_tester 自己拥有的 ui_pkg 测试包。
本轮通过：包节点、三种编译菜单、包体真实错误自动定位和双击跳转、修复保存再编译、
两包批量删除。未使用管理员身份代替客户端普通用户。

这是实际客户端验收，不仅是 SQL 脚本测试。但仍不是全版本、全平台、全部边界验收：
Windows GUI、客户目标版本兼容模式矩阵、混合包头/包体错误的跨页导航、多包编译错误
分别打开源码等场景尚未覆盖。分布式 CN＋DN 环境也尚未搭建成功。

## 实测发现的修复

1. GaussDBDatabase 构造时 serverInfo 尚未完成探测，包支持状态被缓存为 false。
   改为在查询能力时依据最新内存快照重新判断，无新增数据库查询；首次连接可见包节点。
2. 编译 handler 用 GaussDBPackage 类型直接适配导航节点，取不到选中对象，菜单点击无操作。
   改为适配 DBSObject 再检查具体类型。
3. executeStatement 将 SQLException 包装为 DBCException，编译失败跳过错误日志路径。
   改为 JDBC execute 保留 SQLException，失败后从元数据会话读取 GS_ERRORS，优先真实行号。
4. 源码宿主使用全局 adapter manager，未调用编辑器自身嵌套适配，导致只有错误框无跳转。
   改用 IWorkbenchPart.getAdapter，并对匹配对象尝试当前编辑器。

## 观察与数据库断言

| 场景 | 结果 | SWTBot 本机证据编号 |
| --- | --- | --- |
| 首次连接包节点 | 修复前缺失，修复后显示 ui_pkg 下实际包对象 | 316、329–331 |
| 右键编译声明 | pkg_ui compiled successfully | 355–356 |
| 右键编译主体 | pkg_ui compiled successfully | 357–358 |
| 右键编译全部 | pkg_ui compiled successfully | 359–360 |
| 错误包体重编译 | pkg_error 的缺失 %TYPE 引用被拒绝，日志显示 invalid type name，第 3 行、第 1 列 | 413–414 |
| 自动错误定位 | 包体编辑器 caretLine=3，selection={89,90}，对应缺失类型声明 | 414 |
| 双击错误定位 | 先移到第 1 行，再双击错误记录，回到第 3 行 | 415–416 |
| 批量删除 | 确认框准确列出 ui_pkg.delete_a 和 ui_pkg.delete_b 两个包 | 417–418 |
| 删除后独立查询 | 两个包均不存在，pkg_ui 和 pkg_error 保留；pkg_ui.plus_one(41)=42 | 419–420 + gsql |
| 客户端修复并保存 | 替换缺失类型为 INTEGER，SQL 预览确认后执行；value_of()=1，GS_ERRORS 无记录 | 421–425 + gsql |
| 修复后再编译 | 主体编译成功，旧错误日志清除，仅保留本次编译 SQL | 426–427 |

缺陷 fixture 使用 enable_force_create_obj=on、plsql_show_all_error=off，使含错误的包体
实际入库。开启 plsql_show_all_error 时该测试会抛出 Debug mod 错误，不能当作保存成功。
所有这些参数只影响创建 fixture 的独立会话，未改全局生产参数。

证据：`/tmp/gaussdb-swtbot/queue/NNN.cmd.result`，最后客户端日志 launch17.log。
这些是临时本机文件；动作 OK 不等于通过，以上按后续 widget 状态和独立 SQL 核对。
测试驱动因旧 widget ID、中文菜单名称错误或根节点选择限制产生的失败不计作产品缺陷。
临时产品副本在重启后更新本轮生产类，SWTBot bundle 不进入正式 feature。

## 回归及构建

- 592 项测试：589 通过、3 跳过、0 失败/错误。
  platform=448（3 跳过）、GaussDB model=64、GaussDB debug=39、PostgreSQL=41。
  日志 `/tmp/gaussdb-swtbot/package-tests4.log`。
- 完整产品构建 BUILD SUCCESS：`/tmp/gaussdb-swtbot/package-product3.log`，14:56:02 完成。
- 新增测试覆盖初始化晚到的能力状态，以及 GS_ERRORS 的包体行号、OID/schema 过滤及
  SPEC 编译不混入 BODY 错误。

测试删除的两个包属于本轮专用 fixture，可用 `/tmp/gaussdb-ha-lab.yparB8/ui-fixtures.sql`
中相应 CREATE PACKAGE 段重建。其余测试包与 Docker 实例保留以便复测。
