# 跳过项重新接入验证

此前完整回归24项跳过，其中包编译2项、调试会话4项仅因未配置GAUSSDB_REVIEW_CONNECTION/JDBC。测试入口原要求旧日期隔离库命名，本次兼容当前dbv_hist_central_YYYYMMDD专用库，仍需GAUSSDB_HISTORY_ALLOW_DDL=YES；旧隔离库规则保留。驱动类同时支持旧review.driverClass和当前driverClass字段，不放开任意业务库。

执行前检查五个固定测试schema均不存在；执行后再次确认无残留，临时权限角色清理。

run-mNPloL结果：1265项、1246通过、18跳过、1错误。本轮**不是全绿**。[脱敏结果](test-results-20260924-review-reenabled.json)。

实际新增通过证据：包ALL/SPEC/BODY编译及真实错误行定位；缺失诊断目录42P01不伪造源码错误；默认参数重载歧义；COMMIT已落库但回包丢失时禁止重试；无读超时的COMMIT阻塞取消与关闭有界。

断点并发/已删除标记测试在DBE_PLDEBUGGER.turn_on被拒，要求system admin。普通隔离账号未提升权限，因此该项不算通过，也不将其改为无条件跳过掩盖错误。后续需要独立、受控的调试账号执行此用例，继续保留普通账号权限基线。此次只扩大可运行的测试配置兼容性，无生产权限或产品代码修改。
