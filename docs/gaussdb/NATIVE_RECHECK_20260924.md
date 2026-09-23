# 原生备份恢复复验

run-cc4Ush：1265项、1242通过、23跳过、零失败/错误；原生测试实际通过。[脱敏结果](test-results-20260924-native-recheck.json)。本轮未启用已单独完成的六项review账号测试，因此不能把23跳过与上一轮18简单相加或解释为退化；两轮证据范围不同。review_native目录复查无残留，临时grantee已删除。

原GaussDBNativeLiveTest因旧review配置未传入而跳过，本次增加独立GAUSSDB_NATIVE_CONNECTION/JDBC入口，兼容当前普通隔离账号。仍要求显式GAUSSDB_REVIEW_NATIVE=true、专用数据库/同名账号、GAUSSDB_HISTORY_ALLOW_DDL=YES，并校验127.0.0.1:55452固定实验环境；旧review入口保留。不与需要sysadmin的调试配置混用。

测试使用不存在的review_native schema，创建三行数据。实际gs_dump生成plain SQL，添加4096行、每行1024字符的查询输出，gsql恢复时调用生产RestoreHandler的输出重定向和密码管道，校验不设置PGPASSWORD、输出丢弃、恢复后count=3且sum=6。随后实际gs_dump自定义格式、gs_restore恢复，再检查相同行数与和值。

对象清理仅限该测试schema，远端备份文件路径由UUID生成，finally精确删除；不覆盖用户备份。该层不替代完整备份向导GUI和Windows原生工具验收。gs_dumpall仍需独立空集群，未在此对现有集群执行全量导出。
