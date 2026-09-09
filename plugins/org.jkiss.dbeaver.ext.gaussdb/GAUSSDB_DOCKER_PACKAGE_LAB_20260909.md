# Docker 包功能环境实测（2026-09-09）

## 结果

本地下载的 ARM Centralized 内核已在隔离 Docker 实例成功运行。真实创建包头/包体、
调用包函数、SPECIFICATION/BODY/ALL 重编译及 GS_ERRORS 错误记录均已验证。
这是服务端环境冒烟验证，不代表 DBeaver 包编译菜单、双击错误跳转、批量删除已经验收。

纠正之前报告：当前原实例使用 Distributed 内核的 SingleDN 模式，拒绝包编译，
不能据此断言所有分布式部署不支持包。Centralized 内核也支持 single_node；
不能仅凭进程启动参数或相同 version() 字符串判断内核变体。

## 实例

- 容器：`gaussdb-507-ha-lab`，端口只发布到 `127.0.0.1:55452`。
- 数据库：`package_lab`，兼容模式 `A`（该内核建库不接受 `ORA` 字面值）。
- 普通连接用户：`package_tester`；密码保存在本机 `/tmp/gaussdb-swtbot/test-password`，不入库。
- JDBC：`jdbc:gaussdb://127.0.0.1:55452/package_lab`。
- 内核：`/opt/gaussdb/ha-app`；数据：`/opt/gaussdb/ha-data`。
- 版本：Kernel 507.0.0，build d791c80a。
- 来源：百度下载目录 ARM 的 DBS-GaussDB-Euler-Kernel 包，内嵌
  `v10.0.0-GaussDBV5-install-euler-ha-aarch64` →
  `GaussDB-Kernel_507.0.0.B071_Server_ARM_Centralized.tar.gz`。
- 原 `gaussdb-507` 和其 volume 未改动；原审计数据 count=2、sum=24。

## 真实 SQL 结果

`package_acceptance.pkg_smoke` 定义 plus_one(integer) 返回 integer：

1. CREATE PACKAGE、CREATE PACKAGE BODY 成功。
2. plus_one(41) 返回 42。
3. ALTER PACKAGE ... COMPILE SPECIFICATION 成功。
4. ALTER PACKAGE ... COMPILE BODY 成功。
5. ALTER PACKAGE ... COMPILE 成功，再次调用返回 42。
6. `pkg_bad` 包体写入未声明变量赋值，服务端拒绝编译；独立查询 GS_ERRORS 得到：
   name=pkg_bad、type=package body、line=4、src=it is not a known variable。
   gsql 同时报告查询片段内第 2 行，说明不同错误来源存在行号偏移，客户端映射仍需验收。
7. Mac 主机使用原生 JDBC 和普通用户 package_tester 调用包函数，返回 42。
   初始化用户 gausscore 被内核禁止远程连接，未绕过此限制。

脚本/日志在 `/tmp/gaussdb-ha-lab.yparB8/`：package-smoke.sql、package-error.sql、
HaProbe.java、init.log。pkg_smoke 当前由初始化用户创建；普通用户获得执行权限，
后续编辑/编译验收应创建普通用户自己拥有的测试包。

## CN＋DN 尝试与边界

`gaussdb-507-cn-lab` 中启动了 CN、DN、GTM，但手工初始化节点目录后建库仍报
No Datanode defined in cluster，因此不宣称集群可用。初始化期间尝试修正 DN 的
pgxc_node 本地类型，并关闭 allow_system_table_mods；此操作仅作用于新建实验容器。
该实验容器已经停止、未删除，保留排查资料，不占用运行资源。

## 生命周期

这是临时实验环境，数据在容器可写层；删除容器会丢数据。不是生产 HA 集群，未验证
主备切换。容器的 PID 1 是 sleep，Docker 重启后数据库需手动启动：

```sh
docker start gaussdb-507-ha-lab
docker exec -e GAUSSHOME=/opt/gaussdb/ha-app \
  -e GAUSSLOG=/opt/gaussdb/ha-data -e LD_LIBRARY_PATH=/opt/gaussdb/ha-app/lib \
  gaussdb-507-ha-lab /opt/gaussdb/ha-app/bin/gs_ctl start \
  -D /opt/gaussdb/ha-data -Z single_node -l /opt/gaussdb/ha-data/start.log
```

不要在此实验中复用原实例数据目录。需要长期保留前应迁移到独立 volume 并配置启动入口。
