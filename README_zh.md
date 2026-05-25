# Spinal PSX GPU

[![SpinalHDL](https://img.shields.io/badge/SpinalHDL-1.12.3-8e44ad.svg)](https://github.com/SpinalHDL/SpinalHDL)
[![Scala](https://img.shields.io/badge/Scala-2.13.14-dc322f.svg)](https://www.scala-lang.org/)

> 使用 SpinalHDL 复刻 PlayStation 1 的 GPU — 目标兼容 [DuckStation](https://github.com/stenzek/duckstation) 模拟器，最终在 FPGA 上部署并运行 PS1 游戏。

[English](README.md) | 简体中文

---

## 概述

使用 SpinalHDL 对原版 PS1 GPU 进行周期精确的重新实现。目标是生成可综合核心，既可以作为 [DuckStation](https://github.com/stenzek/duckstation) 的替代渲染后端，也最终能在 FPGA 上运行 PS1 游戏。

## 项目结构

```
.
├── build.sbt              # SBT 构建配置
├── src/
│   └── PsxGpu/            # SpinalHDL 硬件源码
├── project/               # SBT 插件与配置
├── third_party/           # 参考子模块
│   ├── duckstation/       #   DuckStation 模拟器
│   └── psx-spx/           #   PSX SPX 技术参考
└── .scalafmt.conf         # Scala 格式化配置
```

## 环境要求

| 工具 | 版本 |
|------|------|
| Scala | 2.13.14 |
| SBT | 1.10.2 |
| SpinalHDL | 1.12.3 |

## 快速开始

```sh
# 克隆仓库（含子模块）
git clone --recurse-submodules <repo-url>
```

## 关于参考资料的重要说明

[psx-spx](https://psx-spx.consoledev.net/graphicsprocessingunitgpu/) 技术参考文档是 Martin "nocash" Korth 的 PlayStation 规格文档（原址 [problemkaputt.de](https://problemkaputt.de/psx-spx.htm)）的转换版本。该文档**并非净室逆向工程产物**——其中相当一部分内容直接复制、转述或衍生了 Sony 通过 Psy-Q SDK 分发的保密文档与源代码（参见 [psx.arthus.net/sdk/Psy-Q](https://psx.arthus.net/sdk/Psy-Q/)）。

由于本项目使用 psx-spx 作为技术参考，而 psx-spx 本身衍生自 Sony 的专有材料，**本实现不应被视为净室工程**。

## 路线图

- [ ] **阶段 1 — 领域知识**：理解 PS1 GPU 内部结构（psx-spx）与 DuckStation 渲染器接口
- [ ] **阶段 2 — 架构设计**：定义模块边界、顶层 IO、验证策略
- [ ] **阶段 3 — 渐进实现**：增量 RTL：命令解析器 → VRAM → 矩形 → 多边形 → 纹理 → 显示输出
- [ ] **阶段 4 — 验证集成**：与 DuckStation 对比追踪、建立回归测试、目标 FPGA 部署

> 本路线图为高层概览，将在实际推进过程中持续迭代调整。

## 参考链接

- [SpinalHDL 文档](https://spinalhdl.github.io/SpinalDoc-RTD/)
- [DuckStation 模拟器](https://github.com/stenzek/duckstation)
- [PSX SPX — nocash PSX 规格文档](https://psx-spx.consoledev.net/graphicsprocessingunitgpu/)
- [nocash 原始 PSX 文档](https://problemkaputt.de/psx-spx.htm)
