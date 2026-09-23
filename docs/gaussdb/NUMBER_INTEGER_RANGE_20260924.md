# 整数转换范围验证

## 发现

对应数值类型边界、客户端转换和数据编辑。生产 NumberDataFormatter 原来在解析为 Long 后直接调用 byteValue/shortValue/intValue，超范围输入会静默截断或变号。`run-0YyMfe` 的 byte、short、int 正负越界六项全部复现失败，合法边界正常。

## 修复

Byte/Short/Integer/Long 类型提示先按 BigDecimal 精确解析：

- 整数值使用对应的 exact 转换方法；越界抛出 ParseException，避免变号或饱和值。
- 有非零小数部分时保留 BigDecimal，不按提示强行截断；原有“类型提示不丢弃小数”的行为保留，并避免 Double 精度损失。
- `127.000` 可以精确转换成 byte，整数形式科学计数法也继续支持。
- 不改变浮点类型提示及 NaN/Infinity 的既有处理策略。

## 本轮新增十项

1. 八项正负越界：Byte、Short、Integer、Long 各上下界外一单位。
2. 一项合法端点：四种整数类型的最小值/最大值保留原包装类型和值。
3. 一项小数保护：四种整数提示下保留 18 位小数，并核对末尾零及科学计数法的整数转换。

这些是生产格式器单元验证，不是网格或 JDBC 全链路。无类型提示的超长整数、BigInteger 返回类型、浮点溢出和分组符语法仍待专门验证；本轮不宣称其已经覆盖。

## 执行结果

`run-Gcb4Eb` 本轮新增十项全部通过；整套新鲜结果 1430 项、1406 通过、24 跳过、0 失败/错误。临时授权角色已删除。[脱敏逐例报告](test-results-20260924-integer-range.json)。
