# 包编译边界验收（2026-09-09，第二轮）

## 范围与结果

继续使用 macOS ARM64 的实际 DBeaver 客户端、SWTBot、原生 GaussDB JDBC，连接
`gaussdb-507-ha-lab` 的 `package_lab`（BusinessCentralized 507，A 模式）。客户端使用
普通用户 package_tester，所有写操作限定 ui_pkg 下本轮 boundary_* 测试包。

已补齐：跨源码页定位、多包错误归属与逐条打开源码、首次打开编辑器的异步定位、
修复后旧错误清理、编译后属性状态即时更新，以及导航树编译时的未保存源码保护。

## 本轮修复

1. 编译结果按“包对象 + 错误”保存；错误自身保留 SPECIFICATION/BODY 与真实行号。
   新增非模态结果列表，显示全限定包名、源码部分、行号、错误。首条错误自动定位，
   其他错误可双击或通过“打开源码”按钮导航。
2. 定位先打开对应包编辑器，切换声明/主体页，再等待源码加载。异步等待有上限，
   新导航请求使旧请求失效；关闭对话框或编辑器后停止。每页日志仅包含本包本页错误，
   因此嵌套日志双击不会拿 BODY 行号跳到 SPEC。
3. 修正共享 SQLObjectDocumentProvider：加载占位文本不再被标记为源码已加载。
   实测旧行为会让首次跳转回到第 1 行，而第二次点击正常。
4. 编译前检查相关已打开编辑器是否有未保存修改，不仅检查当前活动窗口。
   取消在包循环内及循环结束后均退出，不再弹出“全部编译成功”。取消分支为代码检查，
   本轮没有模拟数据库锁等待后的取消端到端场景。
5. 包属性显示可读的 Normal/Invalid/Unknown，替代原来的 `[DBSObjectState]`。
   编译完成后刷新未修改的匹配编辑器，让已打开属性页重新显示最新状态；不强刷脏编辑器。

## 真实界面证据

本机证据目录 `/tmp/gaussdb-swtbot/queue/`。编号是 `NNN.cmd.result`；动作 OK 本身
不是通过标准，以下同时核对了后续控件内容、页可见性、光标位置和独立 SQL 结果。

| 场景 | 实际断言 | 证据编号 |
| --- | --- | --- |
| 多包 ALL 编译 | 同一列表包含 boundary_body/BODY/3 与 boundary_spec/SPECIFICATION/2 | 497、558 |
| 首次打开 BODY | 包体源码可见，caretLine=3，selection={93,94} | 499 |
| 打开另一包的 SPEC | boundary_spec 声明源码可见，caretLine=2，selection={50,51} | 501、560 |
| 已开页跨页切换 | 手动切到声明页，再打开 BODY 错误，切回主体第 3 行 | 505、507 |
| 导航树脏编辑器保护 | 显示 Save package，未执行编译 | 457 |
| UI 修复 BODY | 预览并执行 CREATE OR REPLACE 后，函数 value_of() 返回 42，GS_ERRORS 为 0 行 | 510–514 + gsql |
| BODY 再编译 | 成功，当前主体日志不再含旧错误；另一个包的错误仍保留 | 516 |
| 可读属性 | 有效包 Normal/Normal；无效声明包 Invalid/Unknown（未创建主体） | 531、533 |
| 原地状态更新 | 同一编辑器保存修复、编译声明后，Invalid 改为 Normal，无需关闭重开 | 560、564–568 |
| 最终首条自动定位 | 无额外点击，编译错误后自动打开 boundary_body 主体，caretLine=3；属性 Normal/Invalid | 582–583 |

数据库独立断言（首次 BODY 修复后）：

```text
GS_ERRORS WHERE name='boundary_body': 0 rows
ui_pkg.boundary_body.value_of(): 42
pg_object: boundary_body B=t, S=t; boundary_spec S=f
```

为了复测状态转换，随后重建了 boundary_body/boundary_spec 的错误样例，并再次在 UI
修复 boundary_spec。所以以上是相应测试步骤的断言，不是宣称所有保留 fixture 最终都有效。
试验还发现：对同一包替换为无效 SPEC 后，本版本 GS_ERRORS 仅保留 SPEC 错误，不能
用它伪造“同包同时保留 SPEC 和 BODY 两组错误”的真库结果。这一组合由混合错误单测覆盖；
真库跨页路由和不同包的 SPEC/BODY 错误则已分别实测。

## 重放资料

仓库测试目录 `test/org.jkiss.dbeaver.ext.gaussdb.debug.test/integration/swtbot/`：

- `package-boundary-fixtures.sql`：创建无效主体、无效声明两个独立包；只在专用测试库运行。
- `package-boundary-body-repaired.sql`、`package-boundary-spec-repaired.sql`：粘贴到对应源码页，
  点击保存、检查 SQL 预览、执行，再点击相应编译按钮。
- 多选树节点后使用 `context-selection`，避免 SWTBot 的 TreeItem.contextMenu 重新选择单个节点。

错误样例只在自己的 SQL 会话设置 enable_force_create_obj 与 plsql_show_all_error，未改全局参数。
测试中出现的一份未保存源码已保留在本机
`/tmp/gaussdb-ha-lab.yparB8/boundary_body-unsaved-preserved.sql`，没有写入数据库。
Docker 实例与测试包均保留，未删除原 gaussdb-507 或其他业务容器数据。

## 回归与剩余边界

- 回归 593 项：590 通过、3 跳过、0 失败/错误。
  platform=448（3 跳过）、GaussDB model=65、GaussDB debug=39、PostgreSQL=41。
  日志 `/tmp/gaussdb-swtbot/package-boundary-final3-tests.log`。
- 新增混合 SPEC/BODY 行号回归，补充有效/无效状态显示断言。
- 最终完整产品构建 BUILD SUCCESS：`/tmp/gaussdb-swtbot/package-boundary-build8.log`，
  15:28:29 完成；最终客户端回归日志 `launch23.log`。
- SWTBot 只安装在临时客户端副本，不进入正式产品 feature。
- Windows GUI、客户目标版本/兼容模式矩阵、可用分布式 CN＋DN 集群仍需对应环境。
  构建通过不等于这些平台已完成客户端验收；也不能把单元测试说成全部边界真库验收。
