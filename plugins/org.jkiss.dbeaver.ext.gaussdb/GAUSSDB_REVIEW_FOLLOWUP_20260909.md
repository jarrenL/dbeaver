# 2026-09-09 review 收尾

本次变更由 AI 辅助完成，基于 feature/gaussdb-compatibility 的既有工作区改动。

## 用户可见行为

- 包对象的导航树右键菜单新增编译声明、编译 Body，支持包对象多选；仅选中包对象时显示。
  原有声明/Body 编辑器操作栏和编辑器右键编译入口保留。保存仍通过 CREATE OR REPLACE
  编译，不重复执行 ALTER PACKAGE。
- M 模式的过程创建和 PL/SQL 调试禁用。函数目录继续允许浏览，函数创建沿用通用向导；
  其生成 DDL 在 M 模式尚未验收，保留入口不代表承诺创建成功。
- GaussDB model/UI 与 model 测试 bundle 同步升级为 1.0.48，发布日期 20260909。

## 上游审阅范围

共享 debug.core 改动包含变量编辑转发、StackFrame.stepReturn 转发，以及断点自身/global
enabled 状态过滤和启禁回调。PostgreSQL 的 canStepReturn() 仍为 false，当前 PG 实现不支持
Step Return，公共转发修复不会新增该能力；GaussDB 通过自己的 session 支持该动作。

PG 调试工厂新增 providerId=postgresql 限制，影响 Greenplum、Kingbase、Greengage、Cloudberry
等 PostgreDataSourceProvider 子类。原有 PG 配置面板本就只注册 PostgreSQL；本次进一步限制
工厂适配范围，不能假定所有衍生数据库都缺少 pldbgapi。该变化需在上游 PR 中单独披露。

9/5 PostgreSQL JDBC 的 SCRAM 失败发生于连接 GaussDB，不是原生 PostgreSQL 的调试测试。
原生 PostgreSQL 的协议测试与 DBeaver 共享模型/UI 测试应分别记载，不互相替代。

## 仍需产品验收

2026-09-09 复跑 71 模块离线 Maven verify 成功：576 个测试，573 通过、0 failure/error、
3 个原有跳过。日志 `/tmp/dbeaver-review-20260909-build.log`。

新增原生 PostgreSQL 16.15 + pldbgapi + JDBC 42.7.10 双连接回归，11 项断言通过，
覆盖变量修改及真实返回值、断点增删重加、源码/栈、嵌套单步和调用者断点。
日志 `/tmp/dbeaver-pg-review-20260909.log`，可复跑代码和镜像定义位于 Debug 测试插件 integration/。
最终 continue 返回扩展的 08006 完成诊断，目标函数正常返回 23；测试明确记录并核对这两个条件，
不将该协议回归等同于 DBeaver PostgreDebugSession 或共享 UI 的完整端到端验收。

完整 DBeaver UI 操作、集中式 ORA Package 编译及错误定位仍待目标环境验收。
integration/ 中的 Java 程序是独立执行的测试工具，不进入产品 jar，也不会由 Maven 自动运行。
