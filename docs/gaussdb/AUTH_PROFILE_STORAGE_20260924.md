# 认证配置安全存储接口验证

对应历史清单 3.13/10.3 的密码保存、清理及错误处理。本轮直接调用生产 `DBAAuthProfile.persistSecrets/resolveSecrets`；只替换 `DBSSecretController` 为测试内存实现，使用虚构凭据和固定测试 key，不读取或修改用户安全存储。

## 测试场景

| 场景 | 操作与断言 |
| --- | --- |
| 保存开关（2 项） | false/true 分别保存，解析提交给存储控制器的 JSON，password 键随选项省略/存在；生产 resolveSecrets 读回主密码、中文用户名和 realm 属性；验证 set→flush→get 顺序。 |
| 关闭后替换 | 先以 true 保存，再切 false 保存同一 key；内存存储中只有一条记录，最新 JSON 没有 password，运行时原密码不变，两次 flush。 |
| 写失败 | 控制器 set 抛 DBException，原异常向上传递、不执行 flush、运行时密码保留。 |
| flush 失败 | 已调用 set 后 flush 失败，原异常传出，不自动重复写入或刷新。 |
| 读失败 | get 抛 DBException，用户名、密码及属性保持原状。 |
| 缺失记录 | get 返回 null，既有运行时凭据保持原状，不产生其他存储操作。 |

## 缺陷及修复

`run-l3QNro` 新增 7 项中 5 通过、2 失败：关闭保存密码仍提交 password，先保存再关闭也不能移除旧主密码。`DataSourceRegistry.persistSecrets` 会调用认证 profile 的实际实现，不依赖上一轮 `SecureCredentials` 构造器，故需单独修复。

生产代码只在 `isSavePassword()` 为 true 时加入主密码。每次生成完整新 JSON，关闭后下一次成功保存替换旧记录。保留用户名、认证扩展属性和运行时密码；未实现对所有自定义秘密字段的统一删除。

## 证据范围

以上是生产模型到安全存储接口的契约测试，不证明真实钥匙串、文件加密、原子写盘、崩溃恢复或 Windows 权限通过。flush 失败之后底层是否落盘不由本测试断言；既有用户记录只有在后续成功保存后才会被更新，修复不扫描或迁移历史文件。UI 和发布包仍需同步验证。

## 最终回归

`run-cKhsKy` 新增 7 项全部通过，全量已配置回归 **1,360 项 / 1,336 通过 / 24 跳过 / 0 失败错误**，全部报告时间校验通过。[脱敏结果](test-results-20260924-auth-storage.json)。回归含既有集中式/分布式 507 JDBC 场景，不把它们作为真实安全存储加密证据。临时 grantee 已删除。
