# Spinal PSX GPU

使用 SpinalHDL 复刻 PlayStation 1 的 GPU，目标兼容 [DuckStation](https://github.com/stenzek/duckstation) 模拟器，最终在 FPGA 部署 GPU 并运行 PS1 游戏。

## 项目结构

```
├── build.sbt                  # SBT 构建配置
├── hw/
│   ├── spinal/psxgpu/         # SpinalHDL 硬件源码
│   ├── gen/                   # 生成的 Verilog/VHDL
│   ├── verilog/               # 手写 Verilog（可选）
│   └── vhdl/                  # 手写 VHDL（可选）
└── project/                   # SBT 插件配置
```

## 环境要求

- Scala 2.13.14
- SpinalHDL 1.12.3
- SBT

## 常用命令

```sh
# 生成 Verilog
sbt "runMain Gpu.YourTopLevelVerilog"

# 运行仿真
sbt "runMain Gpu.YourTopLevelSim"
```

## 相关链接

- [SpinalHDL 文档](https://spinalhdl.github.io/SpinalDoc-RTD/)
- [DuckStation 模拟器](https://github.com/stenzek/duckstation)
- [PS1 GPU 技术参考](https://psx-spx.consoledev.net/graphicsprocessingunitgpu/)
