# 工行需求范围澄清与代码核对

日期：2026-09-21。依据用户明确说明：“存储过程是 O 兼容的，M 模式下不用支持，能把基础功能支持了就行。”

## 1. 当前需求基线

DBeaver 26.1.5；本轮允许用 GaussDB 507 验证。客户金融版 505.2.1.SPC0800、503.1.0.SPC2000c 及 x86 麒麟桌面云仍属于后续目标环境，不由 507 或构建通过替代验收。

| 编号 | 需求 | 验收模式及判据 |
|---|---|---|
| R1 | 兼容模式及基础功能 | O/Oracle、B/MySQL、M 正确识别；连接、元数据浏览、SQL 执行、表与数据维护；不承诺模式不支持的服务端能力 |
| R2 | 存储过程/函数调试 | O；F7、F8、Shift+F7、F9、F10 与断点暂停、单步、继续、终止 |
| R3 | 断点管理 | O；新增、启停、删除与断点窗格状态一致 |
| R4 | 变量与监视 | O；查看、修改、监视变量；现有 Watch 为变量名，不承诺任意表达式求值 |
| R5 | 调用栈与导航 | O；多层调用、双击定位正确源码与行号 |
| R6 | 事务控制 | O；结束后选择提交/回滚，与另一连接观察到的数据一致 |
| R7 | Package | O；ALL/SPEC/BODY 编译、错误定位、批量删除；服务端能力及部署限制仍须满足 |

M 的存储过程创建、PL/SQL 调试、断点及 Package 编译为 **N/A**，不是失败、阻塞或通过。M 基础功能仍必须验证，不因 PL/SQL 被排除而免验。保留既有 PG/B 等模式能力，不因工行范围缩小而删除其他用户可用能力。

## 2. 代码核对和修正

- 模式映射：DBCompatibilityEnum 接受 O/A/ORA 为同一个 ORACLE 枚举；新增 O/o 输入识别，不改变现有集中式 A、分布式 ORA 的建库 DDL 输出。新增 O 别名是客户端单测覆盖，并未在返回 O 的金融版上实测。
- 调试规则：GaussDBDebugCore 恢复 M 拒绝；在读取 routine language 前即返回，避免为了被禁用的功能额外查询元数据。非 M 保留已保存对象、OID 和 plpgsql/plsql 语言检查。
- 调试入口、配置面板、断点适配器和会话启动共用上述资格判断；不能通过旧配置绕过 M 限制。服务端 API 签名及执行权限检查继续保留。
- 原有 GaussDBDatabase.isStoredProcedureSupported 和过程创建 manager 的 M 限制保留；包能力 checkPackageSupport 保留 Oracle/部署/目录能力限制。
- 修正共享 routine manager：M 禁止函数/过程创建，生成 CREATE OR REPLACE 动作前再次拒绝 M，避免旧编辑窗口绕过创建菜单限制。保留函数目录浏览及普通 SQL 编辑能力；没有将 M 改判成 ORACLE，也没有修改连接协议、表元数据或数据维护实现。

## 3. 本轮回归与证据边界

后续同日已执行新版 x86_64 麒麟 GUI + 507 真库测试，详见 [GUI验收报告](ICBC_2615_LINUX_GUI_ACCEPTANCE_20260921.md)。核心流程通过但发现断点启用、初次跨schema定位及Package SQL执行问题；不能以以下单测全绿替代GUI验收。

新增/调整测试：O/o 映射与原有 A/ORA 输出不变；M 有 plpgsql/plsql 目录项也不能调试；M 无语言/未保存对象拒绝；ORA 过程可进入后续能力检查，ORA SQL 函数拒绝；PG 原有行为保留。

最终回归：71 模块 BUILD SUCCESS；659 项中 648 通过、11 跳过、0 失败、0 错误。日志：`/tmp/icbc-scope-regression-final-20260921.log`。命令如下：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
mvn -o -fae verify -f product/aggregate/pom.xml \
  -pl "$(paste -sd, tools/gaussdb-review-reactor.txt)" \
  -Dskip-checkstyle=true -Dspotless.check.skip=true
```

11 项跳过包含未配置真库夹具的方法及既有平台跳过项；本轮没有启动数据库或执行 GUI 验收，未跑 Checkstyle/Spotless。测试新增非 M 函数创建保留断言，修改 M 创建拒绝断言；并包含既有模式、类型映射及模型回归。此次范围修正不自动继承旧产品的全部 GUI 验收结论，不会将历史测试重新计数为本轮真库通过。git diff --check 通过；未构建/发布新的客户安装包。

此前 507 真库包编译、断点并发、提交故障等结果及两轮回归的精确区别见 [迁移验证报告](ICBC_2615_507_VALIDATION_20260921.md)。M 创建 PL/SQL 的失败探测保留作为服务端边界记录，不再要求把它跑通。

## 4. 交付边界

当前交付基线为 DBeaver 26.1.5，Linux 与 Windows x86_64 包及源码版本见 [发布页](https://github.com/jarrenL/dbeaver/releases/tag/gaussdb-2615-preview-20260921-r2)。本文记录的范围修正已纳入该版本；早期验证统计保留其原有执行日期和环境限制。

仍需目标环境验证：两个金融版、26.1.5 客户麒麟 GUI 全流程、M 基础功能完整冒烟、O 模式包内例程/包级变量的客户典型场景，以及 ALL 部署组合。不能据本轮范围澄清宣称这些全部完成。
