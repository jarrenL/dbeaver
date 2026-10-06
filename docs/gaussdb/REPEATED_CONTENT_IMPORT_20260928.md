# 连续内容导入失败保护回归

日期：2026-09-28。对应历史场景：9.8 数据编辑、9.1 导入异常恢复。

## 新增场景

正式测试模块 `org.jkiss.dbeaver.ui.editors.data.test` 的
`ContentEditorFileImportTest.failedSecondImportPreservesFirstPendingValueAndAllowsRetry`
新增四个参数场景：文本/二进制分别遇到第二次导入取消、第二次文件不存在。

每个场景均执行以下步骤：

1. 使用真实临时文件导入包含中文的第一份内容，暂不保存。
2. 第二次导入在读取/替换前取消，或选择不存在的文件，断言相应异常。
3. 检查没有提前更新结果集；第一次待保存文本或二进制路径仍在，二进制原值未变。
4. 显式保存，核对保存的是第一份内容。
5. 同一输入对象重新导入第二份合法文件并保存，核对完整内容与保存调用次数。
6. 释放编辑器输入，核对两个外部文件均未被删除或改写。

## 结果和执行证据

- `node gaussdb-dbeaver-tests/scripts/run-hex-content-focused.mjs`：50/50 通过，0 跳过，退出 0。
  日志 `/tmp/repeated-import-20260928.log`，编译输出 `/tmp/hex-content-test-s95d0f`。
- `node gaussdb-dbeaver-tests/scripts/run-existing-osgi.mjs /tmp/hex-content-test-s95d0f --module=org.jkiss.dbeaver.ui.editors.data.test --all-module`：三个测试类 41/41 通过，0 失败/错误/跳过，退出 0，逐类报告门控通过。
  日志 `/tmp/repeated-import-osgi-20260928.log`，报告目录 `/tmp/gauss-existing-osgi-F4N3Nk/reports`。

两次运行存在重叠，不相加为 91 项。本轮仅新增 4 项，没有生产代码修改。

## 验证边界

使用真实文件、生产导入/保存入口及真实 JDBCContentBytes；控制器和取消监视器为测试替身。
不代表真实权限撤销、磁盘故障、进度窗口取消、数据库提交或完整安装包验收。
本轮 Docker API 访问权限被拒，尚不能核实此前 GUI 命令 264 的完成状态，未重新提交命令或计为通过。
完整目标仍未完成。

辅助审核已尝试启动，但在初始化日志文件时被权限拒绝并退出 1；没有有效审核报告，不计入验收。
