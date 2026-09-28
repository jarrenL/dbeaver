# 客户端驱动版本选择边界验证

## 场景映射与边界

对应历史清单6.3的版本工具及1.2驱动版本选择。DBeaver生产VersionUtils由DriverLoaderDescriptor、DriverDescriptorSerializerLegacy和MavenArtifact调用，参与已有驱动文件匹配及仓库版本选择；不把TPDSS的OLTP/Zenith版本工厂直接迁入GaussDB插件，也不以本测试证明GaussDB服务端版本识别正确。

## 18项组件测试

- 12项比较：数字9/10、相同版本、前导零、附加组件、点/横线/下划线分隔、主版本、字母后缀、32位边界、超32位和超64位长数字。每项同时验证正反比较符号与isVersionLessThan。
- 5项选择：数字版本排序、稳定版优先于较新alpha/beta、全部预发布时回退、重复版本、长数字最大版本。每项验证输入未被修改及反转顺序仍得到相同结果。
- 空候选列表返回null。

用例位于共享平台测试模块VersionUtilsTest，直接调用生产工具，不另造排序器代替产品逻辑。没有改变已有alpha/beta识别约定或字母后缀比较语义，不宣称实现完整SemVer/Maven版本规范。

## 复现与修复

红测`/tmp/version-boundaries-red.log`：18项中15通过、3失败。Integer.parseInt遇到超范围数字后进入字典序比较，导致1.999999999被认为大于1.10000000000，最新版本选择也错误；超64位值复现同根因。不是3个独立产品缺陷。

生产实现改用BigInteger.compareTo比较数字组件；非数字仍保留原有字典序逻辑。绿测`/tmp/version-boundaries-green.log`：18/18通过、0跳过、0失败。

测试仓入口`scripts/run-version-focused.mjs`显式编译当前VersionUtils与测试源码，使用现有只读JUnit依赖。此结果不包含真实远程仓库访问、下载/升级界面、完整OSGi构建、GaussDB服务器版本检测或所有驱动插件回归；相关验收仍需单独执行，不并入历史1882项完整基线。
