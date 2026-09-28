# 会话取消/终止接口：编号输入校验

## 复现及修改

PostgreSessionManager.alterSession原先把sessionId字符串直接拼接进pg_cancel_backend/pg_terminate_backend SQL。正常界面传入模型生成的编号，但接口自身未校验其他调用者提供的字符串。新增9种无效输入测试，旧实现均未抛出预期DBException，红测9失败。

现在在创建Statement前校验非空ASCII十进制、正数、signed long范围，并使用解析后的long拼接SQL。无效值返回DBException，不把原始输入放入错误消息。有效64位编号的调用协议不变，没有自动重放或新增服务器操作。

## 场景和测试方法

输入：null、空串、空格、0、-1、1.5、含SQL片段的字符串、Long.MAX_VALUE+1、全角数字。每项分别调用取消和终止分支，断言DBException，createStatement从未调用、statement无交互、调用者session未关闭。全部使用Mockito替身，未发真实取消/终止请求。

新增9/9通过，PostgreSessionIdentityTest共15/15（包括既有小编号、32位边界、64位大编号及身份比较），Gauss会话读取测试10/10不受影响。

这是API输入边界防护，不是已证实的GUI安全漏洞，也不证明真实服务端取消成功。真实会话权限、返回结果、并发结束及GUI提示仍需独立验收。

## 回归证据

73模块诊断回归五个测试模块共2694项：2520通过、173跳过、0失败、1错误（RestTest网络权限拒绝）。独立门禁仍拒绝整体通过；门禁自身7/7通过。未重新发布安装包或推送远程。

红测日志：`/tmp/session-id-validation-red-20260929.log`；修复后日志：`/tmp/session-id-validation-green-20260929.log`；[脱敏逐项结果](evidence/SESSION_ID_VALIDATION_REACTOR_20260929.json)。
