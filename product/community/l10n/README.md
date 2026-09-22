# Eclipse 简体中文资源

随附归档为 Eclipse Babel 官方资源包，未经修改：

- 文件：BabelLanguagePack-eclipse-zh_4.26.0.v20230220105658.zip
- 来源：https://download.eclipse.org/technology/babel/babel_language_packs/R0.20.0/2022-12/BabelLanguagePack-eclipse-zh_4.26.0.v20230220105658.zip
- SHA-256：c330aeacbeefbf94687c0af3b4c33ab24ee6bae34d7f6bb92b70a6d9f6099764
- 许可证：归档内的 license.html、epl-2.0.html、about.html 及逐文件声明。

打包脚本仅安装已有宿主插件对应的 `.nl_zh` 资源片段，并校验不含 Java class；不替换 Eclipse 主插件、不引入其他 Eclipse 版本的执行代码。资源文件原样保留在 JAR 中，随源码一并提供原始归档和许可文件。

该资源包早于当前 Eclipse 平台。插件宿主声明无版本限制，但翻译覆盖率不能因此视为完整；新增文本可能回退到英文。交付前需要验证本产品使用的菜单、调试配置、断点、变量、监视及调用栈窗口。数据库服务器返回的诊断原文不在此语言包翻译范围内。

Linux/Windows 组装需要 Node.js 22+ 和 unzip。脚本固定归档哈希，不从 latest 自动获取依赖，不在用户安装时联网下载。
