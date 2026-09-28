# SQL别名及DML目标识别回归

对应历史清单8.2的UPDATE、DELETE、子查询别名以及9.4的DML对象识别。

## 新增场景

每项使用真实SQLQuery解析入口，断言语句类型、目标schema和table；不使用模拟AST。

| SQL结构 | 预期目标 | 结果 |
| --- | --- | --- |
| UPDATE SET中包含其他表的聚合子查询 | public.accounts，而非audit.other_table | 通过 |
| UPDATE FROM关联另一张表 | public.accounts | 通过 |
| DELETE WHERE EXISTS，内外层使用同名别名a | 外层public.accounts | 通过 |
| DELETE USING关联另一张表 | public.accounts | 通过 |
| INSERT INTO SELECT读取另一张表 | 写入目标public.accounts | 通过 |
| UPDATE中文schema/table和中文引用别名 | 去引号后的中文模式、中文表 | 通过 |

## 证据

主仓基线77d8c0296b，加上述6项测试，无生产代码修改。测试仓run-shared-focused.mjs明确编译当前SQLQuery、SQLSelectItem、GaussDBDialect及GaussDBProjectionMetadataTest。该类66项包含原60项投影/CTE/JOIN/表达式测试；与此前439项同一进程执行，505/505通过，0失败、0跳过、0中止。

日志：`/tmp/shared-projection-dml-20260928.log`。原60项并非新增测试，不与历史全量回归数相加。该类加入正式全量门控，要求实际出现且全部通过。

## 边界

这里只证明客户端语句分类与目标元数据提取，不证明GaussDB目标版本支持全部语法、不执行DML、不验证权限及事务、不保证结果网格可编辑。UNION来源、补全候选、复杂作用域及GUI仍需独立验收。依赖中其他模块仍来自已有构建产物，不是完整Tycho构建。
