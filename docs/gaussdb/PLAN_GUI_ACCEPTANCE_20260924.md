# 麒麟客户端执行计划界面验证

## 环境和范围

麒麟 V10 x86_64 容器中的真实 SWT 客户端，通过图形界面连接 GaussDB 507 分布式实例，使用普通账号。宿主为 ARM，Linux x86_64 为模拟执行，不等同客户桌面云性能验收。

使用既有隔离安装 `/opt/history-gui-20260923`，model 插件为 `2.0.45.202609231933`；并非包含全部最新源码修改的新安装包。本轮未改生产代码、不增加 JUnit 数量。

## 查询

```sql
SELECT g, sum(g) OVER () AS total
FROM generate_series(1, 5) AS g
ORDER BY g DESC;
```

全程只读，无建表或数据变更。未启用 ANALYSE。

## 操作与结果

1. SQL 编辑器输入查询，选择“SQL 编辑器 → 解释执行计划”，出现“PostgreSQL 执行计划配置”，确认默认选项。
2. 初次执行报 `08003: This connection has been closed`。菜单重连后仍失败；此时未判定计划功能通过。
3. 打开连接树，明确断开目标连接，再双击该连接建立连接。执行原查询，结果为 `g=5,4,3,2,1`，每行 `total=15`，执行日志成功且行数为5。
4. 再次解释同一查询，计划树显示 `Sort → WindowAgg → Function Scan`；函数实体为 `generate_series`。
5. 选择 Sort 节点，属性显示节点类型、成本 `72.33–74.83`、Plan-Rows `1000`、Plan-Width `4`、Sort-Key `g DESC`。WindowAgg 成本 `0.00–22.50`、Function Scan `0.00–10.00`。
6. 通过独立 gsql 会话执行相同 EXPLAIN，节点、成本、估计行数和排序键与界面一致。估计1000行来自服务端，不是实际查询5行被客户端误计。

![普通查询恢复后的结果](images/plan-gui-20260924/plan-query-result.png)

![计划树](images/plan-gui-20260924/plan-tree.png)

![选择排序节点查看属性](images/plan-gui-20260924/plan-sort-properties.png)

## 异常和证据边界

- SWTBot 队列737–763。初始失败截图见下；未分析最初会话失效原因，也未将菜单重连无效的原因归咎于服务器或客户端实现。
- 自动化一次误开新连接向导，已取消且未创建连接；一次使用过期控件编号失败，重新抓取控件后恢复。这两步不是产品通过证据。
- 所有截图已人工式视觉核对，树和属性另有控件文本证据。
- 仍未覆盖 ANALYSE 实际耗时/行数、JOIN/分布式 Stream/分区等全部节点、保存/加载计划、目标金融版本和最新全量包。
- 当前界面中配置标题仍标为 PostgreSQL，且部分属性和状态文字为英文。这是展示边界，不宣称全面汉化完成。

![初始失效连接报错](images/plan-gui-20260924/plan-closed-connection.png)
