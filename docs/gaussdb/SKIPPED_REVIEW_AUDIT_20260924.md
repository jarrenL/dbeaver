# 跳过项重新接入验证

## 独立调试账号复验

run-3oeAQZ：1265项、1247通过、18跳过、零失败/错误，[复验脱敏报告](test-results-20260924-review-complete.json)。此前权限不足的断点测试现已通过：真实调试器附着、重复断点去重、启停、4线程各10轮增删启停、删除标记路径及实际工作区标记删除。包编译与其余会话测试也重新执行通过。

使用一次性sysadmin测试身份，HBA仅增加专用数据库/该账号/Docker网关172.17.0.1/32的sha256规则。普通历史测试连接仍使用原普通账号。中间run-qwuWKN因复制HBA后所有者变化，数据库未读取新规则，6项连接被拒；恢复gausscore:dbgrp所有权并reload后才进行最终成功运行，不将该中间失败计为通过。

结束后删除一次性账号和0600凭据文件；五个review schema查无残留；临时grantee删除；原HBA恢复并reload，SHA-256为2f11dd7a624be28e2adeb3bfc8e7c2f636c895203dc63e8fd6ca53af1609a815，与备份一致。原普通账号rolsystemadmin仍false。临时凭据未进入源码或报告。

18项跳过仍不是通过，整体历史清单尚未全部验收。本节补足下面首次审计中的断点权限待办，不代表普通用户具备服务端要求的系统管理权限。

此前完整回归24项跳过，其中包编译2项、调试会话4项仅因未配置GAUSSDB_REVIEW_CONNECTION/JDBC。测试入口原要求旧日期隔离库命名，本次兼容当前dbv_hist_central_YYYYMMDD专用库，仍需GAUSSDB_HISTORY_ALLOW_DDL=YES；旧隔离库规则保留。驱动类同时支持旧review.driverClass和当前driverClass字段，不放开任意业务库。

执行前检查五个固定测试schema均不存在；执行后再次确认无残留，临时权限角色清理。

run-mNPloL结果：1265项、1246通过、18跳过、1错误。本轮**不是全绿**。[脱敏结果](test-results-20260924-review-reenabled.json)。

实际新增通过证据：包ALL/SPEC/BODY编译及真实错误行定位；缺失诊断目录42P01不伪造源码错误；默认参数重载歧义；COMMIT已落库但回包丢失时禁止重试；无读超时的COMMIT阻塞取消与关闭有界。

断点并发/已删除标记测试在DBE_PLDEBUGGER.turn_on被拒，要求system admin。普通隔离账号未提升权限，因此该项不算通过，也不将其改为无条件跳过掩盖错误。后续需要独立、受控的调试账号执行此用例，继续保留普通账号权限基线。此次只扩大可运行的测试配置兼容性，无生产权限或产品代码修改。
