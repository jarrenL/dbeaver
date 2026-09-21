# 复杂 PL/SQL / Package 语法边界补测

日期：2026-09-21。范围：DBeaver 26.1.5 工作区修复版本，Linux 麒麟 V10 x86_64 GUI + GaussDB 507 O/ORA/A 包能力环境。
宿主机是 ARM Mac，x86_64 客户端通过 Rosetta 运行；不是客户桌面云原机认证。

## 结论

这些边界可以在本地验证。新增测试发现并修复了以下客户端问题：

1. 将所有 `PACKAGE` 关键字当成块头，错误合并 `DROP PACKAGE`、`ALTER PACKAGE` 及后面的查询。
2. 将独立一行的 `/` 混入 JDBC SQL。
3. 无法正确区分包声明容器、嵌套子程序和共享同一个 END 的初始化段，导致 IF/循环中的提前拆分或后续 SQL 被吞并。
4. 前置注释进入语法模式匹配，导致带脚本头注释的 CREATE PACKAGE 未被识别。

修复后：**12 个方言测试全部通过，71 模块测试构建通过，完整产品构建通过；Linux GUI 执行两组混合脚本（9 条 + 4 条 SQL）成功。**

## 验证方法与结果

| 场景 | 方法 | 结果 |
| --- | --- | --- |
| 包声明、具名 END、末尾分号 | 解析测试断言完整 SQL 文本；GUI 创建包 | 通过 |
| 局部变量、IF/ELSE、FOR LOOP | GUI 执行包体并调用函数；独立 gsql 再查询 | 返回 6 |
| 嵌套局部函数 | 包内函数声明 `seed` 并实际调用 | 随返回 6 的路径通过 |
| 包初始化段 | 包体具有 `BEGIN NULL; END 包名;` | 编译及包函数调用成功；本用例未检查初始化副作用 |
| EXCEPTION WHEN OTHERS | 包体含异常处理段 | 编译及正常路径成功；未故意触发该异常分支 |
| 字符串内分号、斜杠、转义单引号 | 包函数返回字符串 | `END; it's / text`，内容未被拆分或改写 |
| 行注释、块注释、前置注释 | 客户端直接运行带注释的完整测试文件 | 通过 |
| 引号包名含分号 | 创建 `ui_pkg."icbc;quoted_0921"` 并调用其函数 | 返回 7 |
| 独立 `/` 与除法区别 | 同一脚本同时含独立 `/` 行和 `6 / 2` | `/` 未发送成 SQL；除法返回 3 |
| CRLF、缩进后的 `/` | 解析单测检查 Windows 换行和空白 | 通过；该项不是 Windows GUI 验收 |
| ALL/SPEC/BODY 编译 | GUI 脚本依次执行 COMPILE、COMPILE SPECIFICATION、COMPILE BODY | 均成功 |
| 多条 ALTER/DROP/SELECT | GUI 执行一条 ALTER、两条 DROP、一条目录查询 | 识别为 4 条；独立目录查询数量为 0 |
| 非块普通 SQL | 单测覆盖 CREATE TABLE IF NOT EXISTS、SELECT CASE、多条 SELECT | 通过 |
| PostgreSQL 风格函数 | 美元引号函数解析及后续 SELECT 分离测试 | 通过；不是 PostgreSQL 真库调试回归 |
| M/PG 模式隔离 | 单测覆盖 BEGIN/SELECT/COMMIT 分离 | 通过；不为 M 开启 PL/SQL |
| 整段选择执行 | 单测确认 Package 末尾分号；此前 Linux GUI 已验证对应入口 | 单测通过；此前 GUI 证据见修复进展报告 |

## 实测执行

GUI 测试脚本：
`test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/complex-package-boundaries.sql`

执行该脚本得到 9 条 SQL。独立数据库连接验证结果：

```text
6|END; it's / text|3|7
```

随后通过客户端 SQL 编辑器执行：

```sql
ALTER PACKAGE ui_pkg.icbc_syntax_0921 COMPILE;
DROP PACKAGE ui_pkg."icbc;quoted_0921";
DROP PACKAGE ui_pkg.icbc_syntax_0921;
SELECT count(*) FROM gs_package
WHERE pkgname IN ('icbc_syntax_0921', 'icbc;quoted_0921');
```

确认客户端的 DROP 提示及关闭额外结果标签提示后，执行统计为 4 条，独立查询数量为 0。
这是 SQL 脚本中的批量删除验证，不等同于对象树多选删除入口验证。

## 实现位置

- `GaussDBDialect`：按 CREATE PACKAGE 前缀识别声明容器和局部例程；规则仅用于 O 模式；独立行 `/` 规则不影响字符串、注释和普通除法。
- `SQLParserActionKind` / `SQLScriptParser`：新增可带初始化段的声明容器、嵌套例程头动作；注释不参与语法 token 模式匹配。新增动作由 GaussDB 规则选择，未直接改变其他方言的块类型。
- `GaussDBDialectTest`：12 个测试，包含修复前失败的回归场景。

## 证据与环境处理

证据目录：`/Users/lj/Documents/GaussDB/deliverables/icbc-complex-syntax-20260921/`。

- `icbc-complex-baseline-20260921.log`：修复前失败证据。
- `icbc-complex-r4-20260921.log`：最终 71 模块测试成功。
- `icbc-complex-product-20260921.log`：最终完整产品构建成功。
- `org.jkiss.dbeaver.ext.gaussdb.model.GaussDBDialectTest.txt`：12/12 通过。
- `queue/009.cmd.result`：复杂脚本执行，共 9 条。
- `queue/011—015`：DROP 确认、结果标签提示及执行完成，共 4 条。
- `queue/015.cmd.result`、`cleanup-complete.png`：完成后的界面证据。

容器重启后宿主机 55452 端口映射没有恢复，因此只将本轮复制的测试工作区连接改为同网络容器地址 `172.17.0.5:55452`；该地址不是客户连接配置，也不能作为固定部署地址。
未修改服务端认证配置。测试前确认 GUI 专用包名不存在。两个 GUI 测试包通过客户端删除；先行 gsql 探测创建的 `icbc_complex_0921` 另行清理。脚本保留，可重新创建，不涉及原有业务包。
最终目录查询确认三个测试包均不存在。GUI 正常退出，本轮启动的三个测试容器已恢复停止，未操作原本运行的 CN lab 或其他应用容器。

## 验收边界

本报告证明表内具体场景，不声称穷尽整个 PL/SQL 语法。金融 503/505 目标版本、客户桌面云、Windows GUI、异常处理实际异常路径、任意业务包均未由本轮替代验收。
本轮修改纳入发布标记 gaussdb-2615-preview-20260921-r2；具体源码提交及包校验值以发布页 BUILD-MANIFEST.txt、SHA256SUMS 为准。此前预览包不包含这些修复。
