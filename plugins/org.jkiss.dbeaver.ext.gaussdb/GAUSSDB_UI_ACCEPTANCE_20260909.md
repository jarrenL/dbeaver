# GaussDB 客户端验收记录（2026-09-09）

结论：本轮验收不通过，不能将原始 7 项标为全部验收完成。

## 环境与方法

- 基线提交 bb69812abc，另补 community 产品的 Debug Feature 打包引用。
- 当前源码生成的 macOS aarch64 DBeaver 26.2.0，插件构建号 202609090246；
  本机 JDK 25 显式通过 -vm 指定，未验证随包 JRE 或 Windows 客户端启动。
- GaussDB Kernel 507.0.0 / Docker gaussdb-507，127.0.0.1:5432。
- 创建独立 ORA 数据库 dbeaver_ui_0909、同名临时账号，授予 gs_role_pldebugger。
  使用自有 ui_acceptance schema、审计表和 ui_parent/ui_child 两个嵌套过程。
- 使用华为 JDBC 506.0.0.b058，本轮在独立工作区调整 GaussDB 驱动类和 URL，
  不作为默认驱动自动下载或默认配置的验收证据。密码未保存到连接设置。
- 通过原生客户端界面操作验证，未用协议脚本代替窗口操作。

## 已观察结果

| 项目 | 本轮结果 | 证据/边界 |
|---|---|---|
| 产品包含调试插件 | 原成品缺失；补引用后验证加载 | 新成品 plugins 中包含 GaussDB debug core/UI；启动日志记录两个 bundle 激活 |
| 客户端启动 | 通过（外置 JDK） | 完成首次配置，启用 Procedure debugger，关闭统计上传 |
| ORA 连接 | 通过 | Test Connection 显示连接成功、GaussDB Kernel 507.0.0；其他模式 UI 未复跑 |
| 存储过程浏览 | 通过 | 展开 ui_acceptance/存储过程，看到 ui_child(int4)、ui_parent(int4)，打开父过程属性与参数页 |
| 默认调试启动入口 | 失败 | 工具栏主按钮和数据库菜单“数据库调试…”均显示 Unable To Launch |
| 断点、F7/F8/Shift+F7/F9/F10 | 阻塞，未验收 | 未成功进入 UI 调试会话 |
| 变量编辑/Watch、调用堆栈导航 | 阻塞，未验收 | 未成功进入 UI 调试会话 |
| 提交/回滚弹窗及数据结果 | 阻塞，未验收 | ui_audit 行数 0；没有成功执行 UI 调试目标 |
| Package 编译/错误定位/批量删除 | 环境阻塞 | CREATE PACKAGE 返回 Un-support:DN does not support compiling package. |

## 启动问题复现

1. 新工作区启用 Procedure debugger，连接上述 ORA 库。
2. 展开数据库/模式/ui_acceptance/存储过程，打开 ui_parent(int4)。
3. 点击顶部调试主按钮，或数据库菜单中的“数据库调试…”。
4. 出现标题 Unable To Launch，内容：
   `The selection cannot be launched, and there are no recent launches.`

调试按钮可见证明对象级入口能力探测已放行，但不能证明启动快捷方式成功解析对象。
本轮没有成功操作工具栏下拉箭头，也未验证从源码子页发起的另一条启动路径，
因此不推断所有替代入口都失效。自动化工具对主窗口自绘区域坐标点击多次返回
windowNotFoundAtPosition，键盘和原生菜单仍可操作；这限制了后续替代路径检查。

源码核对：OpenDebugConfigurationHandler 在上下文启动启用时调用 ContextRunner.launch，
并不是无条件打开配置对话框。GaussDB DBGDebugObject 工厂仅注册 EntityEditor，尽管 Java
代码还处理 DBNDatabaseNode 等对象；需结合选择上下文修复/验证首次启动路径，不能仅改文案。

## 本轮改动与构建边界

product/community/DBeaver.product 新增 org.jkiss.dbeaver.debug.feature 引用。
产品 materialize 日志显示六个目标成品生成完成，实际 macOS ARM64 成品可运行；
并行 reactor 最终 BUILD FAILURE：org.jkiss.dbeaver.data.office 无法解析
org.jkiss.bundle.apache.poi:5.4.2（bundleLocation can't be null）。因此本轮使用的是成功生成的
macOS 成品做运行验证，不声明完整产品构建通过；Office 依赖问题还需解决。
日志：/tmp/dbeaver-ui-acceptance-build-20260909.log；
客户端日志：/tmp/dbeaver-ui-acceptance-20260909-launch.log。

9/5、9/9 的自动化和 JDBC 协议回归仍是有效的分层证据，但不能覆盖本轮暴露的产品打包和 UI 入口问题。

## 清理

客户端正常退出，确认测试库连接数为 0 后删除本轮临时库和账号；测试建库/建过程 SQL
中的临时凭据已删除。业务库未作为写入或删除目标。最终查询确认临时库和角色数量均为 0。
本轮 Maven 已自行以失败退出，保留成品和日志供排查。
