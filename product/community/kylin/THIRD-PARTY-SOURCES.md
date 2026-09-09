# 对应源码、许可证及修改说明

本文件用于本项目定制 Linux 客户端的来源追踪，不替代各组件完整许可证，也不表示数据库供应商、Eclipse 或麒麟官方认证。

## DBeaver 及平台插件

保留产品 `licenses/`、各插件的 `about.html`、`META-INF` 及其他许可证/NOTICE。DBeaver 代码依各源文件 Apache-2.0 声明；平台和第三方插件可能使用不同许可证。交付清单应记录本地源代码提交与未提交补丁；正式归档前必须固定源码状态。

## Eclipse SWT（本项目重建）

- 上游：https://github.com/eclipse-platform/eclipse.platform.swt
- 版本：`v4973r12`，对应产品 GTK SWT `3.134.0.v20260515-1429`。
- 对应源码文件：`sources/swt-v4973r12.tar.gz`。
- 修改：合并构建目录后将 `make_linux.mak` 中 `-std=gnu17` 改为 `-std=gnu11`，以使用麒麟 GCC 7.3；其余警告/错误选项保留。对应修改与完整构建步骤见随交付源码提供的 `build-natives.sh`。
- 许可证：保留源包及插件原有 EPL-2.0 与相关组件声明。
- 所有七个 GTK JNI 库在目标麒麟用户空间重建。修改后的 jar 移除不能认证新字节的原 Eclipse `.SF/.RSA` 文件，是本项目的 unsigned 定制构建，不能被描述为上游签名二进制。

## Eclipse Equinox launcher（本项目重建）

- 上游：https://github.com/eclipse-equinox/equinox
- tag：`R4_37`；commit：`d2f412dcd26daa40100682d822b5b9c3c33269c9`。
- 对应源码文件：`sources/equinox-R4_37.tar.gz`（由该 tag 的 `git archive` 导出）。
- 保留源包 `LICENSE`、`NOTICE` 和逐文件许可证。
- 从 `features/org.eclipse.equinox.executable.feature/library/gtk` 编译；不修改 launcher 源码。产物 `eclipse_11916.so` 与产品 fragment `1.2.1500.v20250801-0854` 匹配。

## Eclipse Temurin JRE（未修改）

版本固定为 `21.0.12.1+1`，不是以后会变化的 latest。保留 `dbeaver/jre/legal/`、`release` 及运行时文件。许可证见所附文件；Adoptium FAQ 说明其二进制使用 GPLv2 with Classpath Exception：https://adoptium.net/docs/faq 。

官方发布：https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1

已将两个已下载 JRE 与官方发布附件 `.sha256.txt` 比对一致：

| 官方文件 | SHA-256 |
| --- | --- |
| OpenJDK21U-jre_aarch64_linux_hotspot_21.0.12.1_1.tar.gz | `14be1f35ebdbd1f6e8d57eb911a3ffb74d6d9aa255abc5daf2b1302002cf2cf2` |
| OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz | `2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500` |

对应源码亦随交付文件集合提供，不仅提供上游链接：`sources/OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz`。已比对官方 SHA-256：`573057d03584ae793fb7ec9a14c76d826d9187a53efeefd99da47403a5308234`。源码源仓库 `https://github.com/adoptium/jdk21u.git`，运行时 release 文件记录 `SOURCE=.:git:1c417fbfc2f7`；构建仓库 `https://github.com/adoptium/temurin-build.git`，`BUILD_SOURCE=e6ba7dec3d07654074559310376a3ae89da5f4ac`。

分发产品时同时提供上述源码文件集合和本项目构建脚本/修改说明，不要仅发送去掉许可证的二进制目录。

## 不包含的测试/专有组件

- GaussDB 服务端安装包、数据库数据、测试账号密码、工作区均不属于客户端交付内容。
- GaussDB JDBC 因未确认再分发依据，需客户或供应商自行提供。
- SWTBot、测试 harness、Xvfb、metacity、xdotool、第三方麒麟 Docker 基础镜像不是产品内置组件。测试环境软件来自各自来源并依各自许可证使用，不将整个测试镜像作为客户软件包交付。
