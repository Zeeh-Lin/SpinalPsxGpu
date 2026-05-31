# spinal-pro Skill 待改进点

> 基于 Gp0Flow / Gp0FifoController / Gp0CmdFifo 一轮实际开发中暴露的问题整理。

---

## 1. 不主动查阅官方手册

Skill 内置的 SpinalHDL 知识库覆盖不全时，没有触发 WebSearch / WebFetch 去查官方文档，而是反复试错。

| 场景 | 实际行为 | 应有行为 |
|---|---|---|
| sim 中访问子组件信号 | 花大量时间手工搭建中间层 IO，多次 freeze | 查 [Simulation → signal](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Simulation/signal.html)，发现 `simPublic()` 一行解决 |
| 时钟组件 sim 冻结 | 反复怀疑 .withFstWave、Config、Stream 方向，逐个排除 | 查 [Simulation → clock](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Simulation/clock.html)，发现 `forkStimulus` 是必须的 |

**引用**：`simPublic()` 文档示例——

```scala
class TopLevel extends Component {
  val counter = Reg(UInt(8 bits)) init(0) simPublic()  // ← 这一行
}
// testbench: dut.counter.toUInt 直接可读
```

---

## 2. 基础类型转换不了解

对 SpinalHDL 的类型体系（`Bits` / `UInt` / `SInt` / `Bool`）的方法掌握不足，导致绕过而非正确使用。

**实例**：`Bits` 不支持 `>=` / `<=`

```scala
// 错误路径（当时的做法）：用 === 枚举所有否定情况回避
val bad = inst(31 downto 24) === B"11100001" ||
          inst(31 downto 24) === B"11100010" ||
          inst(31 downto 24) === B"11100110"
result := !bad

// 正确做法（用户指出后修正）：
val cmdByte = inst(31 downto 24).asUInt       // Bits → UInt
result := cmdByte >= U(0xE3, 8 bits) && cmdByte <= U(0xE5, 8 bits)
```

**缺口**：Skill 应列出常用类型转换链路—— `Bits.asUInt`、`UInt.asBits`、`Bool.asUInt`、`SInt.asBits` 等，并说明各自的可用操作符。

---

## 3. Stream 的 master / slave 方向语义不清

`Stream` 是 SpinalHDL 最常用的握手协议，但 Skill 对方向语义的说明不够。

**实例**：`<>` 连接两个同向端口

```scala
// 错误：两个 slave Stream 之间用 <> 或 := 强行连接
io.gp0In.valid   <> ctrl.io.gp0Write.valid   // 两个 input → 无驱动
io.gp0In.ready   <> ctrl.io.gp0Write.ready   // 两个 output → 多驱动冲突
```

**用户的正确洞察**：

> 既然方向相同，说明它们是同一个东西——不需要连接，直接操作就行。

```scala
// 正确做法：标记 simPublic()，testbench 直接驱动子组件端口
ctrl.io.gp0Write.simPublic()
// testbench: dut.ctrl.io.gp0Write.valid #= true
```

**缺口**：Skill 应说明：

| 操作 | 适用方向 | 含义 |
|---|---|---|
| `master >> slave` | `out → in` | 主端驱动从端 |
| `master << slave` | `in ← out` | 同上，反向写法 |
| `a <> b` | 互补方向 | 双向连接（需一主一从） |
| `a := b` | 同向或 `out ← expr` | 信号赋值 |

---

## 4. Simulation 工作流不完整

Skill 缺少一个"simulation 检查清单"，导致每次踩坑：

| 检查项 | 遗漏后果 |
|---|---|
| `forkStimulus(period)` | 全部有时序逻辑的 sim freeze |
| 输入信号显式初始化 `#= false` | X 传播导致 `waitSamplingWhere` 永不满足 |
| `simPublic()` 标记需访问的内部信号 | `UNACCESSIBLE SIGNAL` 异常 |
| 读信号的正确时机（在清 valid 之前） | 读到错误值（如 bypass valid 恒为 false） |
| `StreamFifo` 的 `pop.ready` 控制 | FIFO 自动排空，测试看到参数而非命令字 |

---

## 5. DSL 边界情况知识不足

SpinalHDL 作为 Scala DSL，有些行为和直觉不同，Skill 未覆盖：

**实例 A**：`when` / `switch` 在 `def` 函数 vs `Component` body 中的行为差异。

`Gp0Flow.bypassFifo` 最初用 `when` 实现，作为独立函数调用时，`when` 需要 Component 上下文来附着生成的硬件。纯函数场景更安全的选择是 `Mux` 表达式。

**实例 B**：`:=` 返回信号本身导致 Scala 表达式链。

```scala
io.signal := value   // 返回 io.signal (Bool)
when(cond) { ... }   // 被解析为 (Bool).when(cond) { ... }
```

Skill 应警告：连续多个 `when` 块之间需要显式分隔（`{ }` 作用域块或中间插入 `val _ =` 语句）。

---

## 改进建议汇总

| # | 改进点 | 优先级 | 做法 |
|---|---|---|---|
| 1 | 遇到阻塞时主动查官方文档 | 高 | Skill 内置触发条件：sim freeze → 检查 forkStimulus/simPublic；类型错误 → 检查 .asUInt 等转换 |
| 2 | 补充类型转换速查表 | 高 | Bits→UInt→SInt 的转换方法和可用操作符列表 |
| 3 | Stream 方向语义 + 连接规则 | 高 | master/slave 图示 + `>>` / `<>` / `:=` 的适用场景 |
| 4 | Simulation 检查清单 | 中 | forkStimulus、信号初始化、simPublic、读信号时机、FIFO 反压 |
| 5 | DSL 陷阱速查 | 中 | `:=` 返回值链、`when` 在函数/Component 中的差异、`def` vs `val` |
