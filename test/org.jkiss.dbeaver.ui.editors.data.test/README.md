# 内容编辑器组件回归

本模块承载文本/二进制编辑器的内容重载、文件导入、显式保存、放弃和失败重试测试。它是 `org.jkiss.dbeaver.ui.editors.data` 的测试 fragment，以保持包级访问，不扩大生产API，也不向纯模型测试模块添加UI依赖。

## 执行与边界

- 已加入 `test/pom.xml` 和 `tools/gaussdb-review-reactor.txt`，继承父测试模块的 JUnit/Tycho 配置。
- `src/mockito-extensions` 为本模块启用 inline mock，文件选择器和工作台静态入口用于组件隔离。运行环境需要支持 Mockito/Byte Buddy instrumentation；不能把初始化失败当用例跳过。
- 测试调用实际生产加载/保存代码，使用真实临时文件和内容对象；窗口、值控制器等为替身，不等同实际 SWT 交互或数据库提交测试。
- GUI操作与数据库读回证据另见 `docs/gaussdb/TEXT_FILE_IMPORT_20260928.md`。

## 当前接入状态

2026-09-28：三个类合计31项从独立测试夹具迁入，无重复副本；专项运行器编译本模块源码，联合正式平台读取类9项共40项全部通过。正式Maven运行已识别模块，但在依赖缓存锁阶段失败（run-Ncu1z1），新fragment尚无正式OSGi执行成功证据。不能仅凭模块注册和普通classpath通过就宣称完整接入已验收。
