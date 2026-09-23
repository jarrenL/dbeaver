# 原生备份恢复复验

## 恢复脚本错误后重试

run-eCRNx8首轮因断言小写division与实际Division大小写不同失败，直接gsql确认实际错误及退出码3；修正为忽略大小写，仍要求明确除零信息。最终run-XDMUEZ全1265项、1242通过、23跳过、零失败/错误。[错误重试脱敏报告](test-results-20260924-native-error.json)。这是扩充已有集成测试的内部场景，未增加JUnit数量；schema复查无残留，临时grantee清理，无生产修改。

在同一原生集成测试中追加阶段：将随机测试脚本改为SELECT 1/0，随后DELETE专用测试表。gsql配合ON_ERROR_STOP必须非零退出，错误明确包含division by zero且不含密码，数据库仍count=3/sum=6，证明未继续执行DELETE。再将脚本改为INSERT值4并重跑，必须正常退出且count=4/sum=10。

这验证首条语句失败后的停止及后续进程重试；不等同于包含先前已提交DML的脚本自动整体回滚，也不替代GUI恢复失败对话框。

run-cc4Ush：1265项、1242通过、23跳过、零失败/错误；原生测试实际通过。[脱敏结果](test-results-20260924-native-recheck.json)。本轮未启用已单独完成的六项review账号测试，因此不能把23跳过与上一轮18简单相加或解释为退化；两轮证据范围不同。review_native目录复查无残留，临时grantee已删除。

原GaussDBNativeLiveTest因旧review配置未传入而跳过，本次增加独立GAUSSDB_NATIVE_CONNECTION/JDBC入口，兼容当前普通隔离账号。仍要求显式GAUSSDB_REVIEW_NATIVE=true、专用数据库/同名账号、GAUSSDB_HISTORY_ALLOW_DDL=YES，并校验127.0.0.1:55452固定实验环境；旧review入口保留。不与需要sysadmin的调试配置混用。

测试使用不存在的review_native schema，创建三行数据。实际gs_dump生成plain SQL，添加4096行、每行1024字符的查询输出，gsql恢复时调用生产RestoreHandler的输出重定向和密码管道，校验不设置PGPASSWORD、输出丢弃、恢复后count=3且sum=6。随后实际gs_dump自定义格式、gs_restore恢复，再检查相同行数与和值。

对象清理仅限该测试schema，远端备份文件路径由UUID生成，finally精确删除；不覆盖用户备份。该层不替代完整备份向导GUI和Windows原生工具验收。gs_dumpall仍需独立空集群，未在此对现有集群执行全量导出。
