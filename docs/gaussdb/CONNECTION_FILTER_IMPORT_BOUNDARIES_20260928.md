# 连接过滤器导入类型边界

## 后续：作用域、开关及实际导入调用方

新增10项：type数组、id对象、name数字、description布尔值、enabled无效字符串、case-sensitive数组六种拒绝；旧true/false字符串两种正例；UserDBSObjectFilterUtils实际导入入口两项。红测`/tmp/shared-filter-scope-red-20260928.log`375项中369通过、6失败，错误字段原先通过通用转换被静默接受。

生产FilterSerializer对作用域/名称/说明校验字符串，对开关校验布尔或明确true/false字符串；缺省/null保持原行为，不回显错误值。导入入口验证同一数组中后项非法时DataSourceDescriptor没有收到任何调用；有效数组中schema/table作用域准确、缺类型条目跳过、用户过滤器标记正确。该描述符是Mockito替身，实际导入工具类与序列化器直接编译运行，不证明磁盘或GUI原子性。

联合`/tmp/shared-filter-scope-green-20260928.log`375/375通过、0跳过、0失败，过滤器类28项。其他347项复跑；无额外测试数量累加。多个设置键、完整连接文件中其他字段已修改后的失败回滚、重复JSON字段、配置升级、GUI及磁盘凭据仍未闭环。

## 范围与缺陷

对应历史清单3.12连接配置导入/过滤、10.4配置元数据。直接编译生产FilterSerializer并调用JSON反序列化入口，不替代完整连接文件导入向导。

新增六类非法输入：数组中的null过滤器、include含null/数字/对象、exclude含布尔值、include本身是字符串。红测联合362项中356通过、6失败（`/tmp/shared-filter-invalid-red-20260928.log`）：null条目导致NullPointerException，其余被字符串化或忽略。尤其标量include会变成空条件，可能扩大导航器对象显示范围；此处不是数据库授权控制，不声称权限绕过。

修复在过滤器反序列化入口拒绝null条目，include/exclude非空值必须为字符串集合，非法值统一JsonParseException；错误消息不回显配置内容。未修改通用JSONUtils，避免改变其他配置的兼容行为。既有缺省/null/空数组条件保留合法，通过三项新增正例验证启用状态、空集合与对象匹配。每个非法输入后还用同一序列化器导入有效中文过滤器，核对包含、排除及无关对象不匹配。

## 结果及限制

FilterSerialization类共18项（原9项＋新增9项）。与此前347项联合运行：`/tmp/shared-filter-legacy-green-20260928.log`365/365通过、0跳过、0失败。15个测试类同次编译/运行，依赖仍包含既有构建产物；不是完整Tycho构建。正式回归验证器新增本类必需执行门控。

未覆盖完整连接导入原子更新、字段type/id/布尔值全部类型约束、重复字段、配置升级、磁盘凭据、GUI错误提示及实际过滤树刷新。无真库连接，无文件导入操作或授权变更。完整构建、真库与Hermes环境限制仍未解除。
