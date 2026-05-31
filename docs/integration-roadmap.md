# 硬件 GPU 接入 DuckStation 路线图

> 日期: 2026-05-27
> 总结: 基于对 DuckStation GPU 架构和 psx-spx 规格书的分析，梳理从 SpinalHDL 实现到 FPGA 部署的完整路径。

---

## 1. 关键认知澄清

### 1.1 psx-spx 是规格书，不是实现

`third_party/psx-spx` 是纯文档项目——nocash 的 PlayStation 规格文档的 MkDocs 转换版。包含零行 RTL 代码。它的 [graphicsprocessingunitgpu.md](third_party/psx-spx/docs/graphicsprocessingunitgpu.md)（1517 行）是 GPU 硬件实现的完整规格书。

### 1.2 DuckStation 三层 GPU 架构

DuckStation 将一颗真实 PSX GPU 芯片的功能拆分为三层：

| 层 | 组件 | 职责 |
|----|------|------|
| 命令前端 | `g_gpu` (GPU 类) | GP0/GP1 命令解析、FIFO 管理、CRTC 时序、vblank 中断 |
| 线程编排 | `VideoThread` | 16MiB 无锁环形 FIFO、CPU/GPU 线程同步 |
| 渲染后端 | `GPUBackend` 子类 | 多边形光栅化、VRAM 管理、纹理缓存、显示输出 |

其中 `VideoThread` 是纯宿主层概念——真实硬件不需要"线程之间传命令"——其余两层 `g_gpu` + `GPUBackend` 加起来才相当于真实 PSX GPU 芯片。

### 1.3 硬件 GPU 覆盖范围

你的 SpinalHDL GPU 实现的功能对应 DuckStation 中两个组件之和：

- 命令解析 + FIFO + CRTC → 对应 `g_gpu` 的职责
- 渲染管线 + VRAM + 纹理缓存 + 显示 → 对应 `GPUBackend` 的职责

但不包括 `VideoThread`（线程编排）和 `VideoPresenter`（宿主窗口/swap chain）。

---

## 2. 集成策略

### 2.1 连接层方案：GpuFpgaBackend

写一个 C++ 类 `GpuFpgaBackend`，继承 DuckStation 的 `GPUBackend` 抽象基类。这个类是硬件 GPU 和模拟器之间的适配层：

| 职责 | 具体做法 |
|------|----------|
| 命令翻译 | 将 DuckStation 已解析的高层绘制命令重新编码为 GP0 原始字流，写入硬件端口 |
| 时钟推进 | 用 while 循环翻转硬件时钟信号，直到硬件完成当前操作（GPUSTAT 的 busy 位清除） |
| VRAM 同步 | `ReadVRAM()` 时从硬件读回写入 `g_vram[]`，`UpdateVRAM()` 时从 `g_vram[]` 推送到硬件 |
| 显示输出 | `UpdateDisplay()` 时从硬件 framebuffer 读像素，交给 `VideoPresenter` 显示 |

### 2.2 阶段二能测到什么（GpuFpgaBackend 路径）

当 GpuFpgaBackend 替代 DuckStation 现有的渲染后端时，`g_gpu` 仍然在解析命令、管理 CRTC。这意味着：

**可以测到的硬件模块**（渲染管线侧）：

- 多边形/线/矩形光栅化（最复杂、最有验证价值）
- VRAM 读写仲裁
- 纹理缓存和 CLUT 缓存
- 半透明混合、抖动

**测不到的硬件模块**（命令前端侧，但可以在阶段一独立验证）：

- GP0/GP1 命令解析器——被 `g_gpu` 绕过了
- 16 字 FIFO——`g_gpu` 有自己的 4096 条目软件 FIFO
- CRTC 显示时序——`g_gpu` 的 `CRTCTickEvent` 在驱动 vblank

这不是缺陷，而是合理分工。命令解析器逻辑简单，用独立 Verilator testbench 喂 GP0 字流就能充分验证，不需要跑整个模拟器。渲染管线逻辑复杂、容易出错，通过真实游戏画面验证才有意义。

### 2.3 DuckStation 源码改动量

阶段二只需修改 DuckStation 约 10 行：

- `src/core/types.h`：`GPURenderer` 枚举新增 `HardwareFPGA`
- `src/core/video_thread.cpp`：`CreateGPUBackendOnThread()` 函数新增一个 case 分支
- `CMakeLists.txt`：链接 Verilator 生成的库

`g_gpu` 和 `VideoThread` 完全不动。

---

## 3. 分阶段路线图

### 阶段一：独立硬件开发与验证（当前阶段）

不碰 DuckStation。每个子模块通过独立 Verilator testbench 验证。

子模块划分：

| 模块 | 功能 | 验证方式 |
|------|------|----------|
| Gp0Decoder | GP0 命令解码 + 16 字 FIFO | 喂 GP0 字流，检查命令分发结果 |
| Gp1Controller | GP1 寄存器状态机 | 写 GP1 命令，检查 GPUSTAT 字段 |
| RenderPipeline | 光栅化 / 纹理映射 / 混合 | 喂顶点数据，读回 VRAM 像素值 |
| VramController | VRAM 读写仲裁 | 并发读写，检查时序冲突 |
| TextureCache | 2KB 纹理缓存 | 逐页检查命中/未命中 |
| CrtcController | 扫描线计数器 + vblank | 推进数万时钟周期，验证中断时序 |
| DmaInterface | DMA2 linked-list 协议 | 模拟排序表传输 |

每个 testbench 是独立小程序：编译只需 Verilator + C++，不依赖 DuckStation。

**完成标准**：每个子模块 testbench 通过，加上一个顶层集成 testbench 跑若干简单场景（例如渲染一个三角形到 VRAM，读回像素比对）。

#### 1.1 子模块实现顺序（2026-05-31 确定）

原则：渲染核心先行，基础设施紧跟，逐步串联。每一步只依赖上一步，每步都能独立验证。

| 步骤 | 模块 | 类型 | 依赖 | 验证方式 | 预计 session |
|------|------|------|------|----------|-------------|
| 1 | **TriangleRasterizer** | 渲染核心 | 无 | 仿真 log 输出像素坐标和颜色，检查三角形内部点数、颜色插值、drawingArea 裁剪 | 2-3 |
| 2 | **VramController** | 基础设施 | 无 | 写→读→比对；与光栅化器联调：渲染三角形到 VRAM，读回像素比对 | 1-2 |
| 3 | **Gp0Executor**（执行引擎） | 串联 | Gp0Decoder, Gp0CmdFifo, VramController, TriangleRasterizer | 从 FIFO 消费命令→收集参数→驱动光栅化→像素写入 VRAM 全通路 | 1-2 |
| 4 | **TextureCache** + 纹理化渲染 | 渲染核心 | VramController, TriangleRasterizer | 2KB 缓存命中/未命中、CLUT、UV 插值；三角形/矩形带纹理渲染 | 2-3 |
| 5 | **LineRasterizer** + VRAM 传输 + Misc 命令 | 渲染 + 基础设施 | VramController, Gp0Executor | 线条光栅化、矩形填充、CPU↔VRAM DMA、QuickFill、ClearCache | 2-3 |
| 6 | **Gp1Controller** + **CrtcController** + **DmaInterface** + 顶层集成 | 基础设施 + 集成 | 全部上述模块 | GPUSTAT 寄存器、display mode、扫描线/vblank 中断、DMA2 linked-list；顶层 testbench 跑简单场景 | 2-3 |

节奏：**渲染核心 → 存储 → 串联 → 增强 → 补全 → 集成**

第 1 步即可在仿真 log 中看到三角形的"渲染结果"（文本形式），后续逐步过渡到 VRAM 存储和 DuckStation 视觉输出。

### 阶段二：DuckStation 集成验证

硬件 GPU 接入模拟器，跑真游戏验证渲染管线。

改动范围：

- `SpinalPsxGpu` 仓库：新建 `wrapper/gpu_fpga_backend.h/cpp`，继承 `GPUBackend`
- DuckStation 上游：最小修改（~10 行，新增枚举值和后端创建分支）

架构示意：

- `g_gpu` 解析 GP0 命令，打包为 `VideoThreadCommand`
- `VideoThread` 将命令推送到你的 `GpuFpgaBackend`
- `GpuFpgaBackend` 重新编码为 GP0 字流，写入 `VMyTopLevel`（Verilator 对象）
- 推进时钟直到硬件完成渲染
- 读回 VRAM / 显示输出，交给 `VideoPresenter`

**完成标准**：2-3 个经典 PSX 游戏或 homebrew demo 的渲染结果与 DuckStation 软件后端逐帧一致。

### 阶段三：FPGA 部署

同一份 SpinalHDL 源码综合到 FPGA，同一份 `GpuFpgaBackend` 切换到 FPGA 通信。

阶段二到阶段三的代码改动仅限于 wrapper 中的通信层：

- Verilator 的 `m_dut->clk = !m_dut->clk; m_dut->eval()` 替换为 FPGA 的寄存器写操作
- Verilator 的 `m_dut->io_gp0_data = val` 替换为 PCIe 或以太网的寄存器写
- 上层命令编码和 VRAM 同步逻辑完全复用

**完成标准**：FPGA 开发板通过 PCIe/以太网连接 PC，DuckStation 使用 `GpuFpgaBackend` 正常渲染游戏画面。

---

## 4. 许可证注意事项

DuckStation 使用 CC-BY-NC-ND-4.0。ND（禁止演绎作品的**分发**）条款的影响：

| 行为 | 许可状态 |
|------|----------|
| 修改 DuckStation 源码在本地测试 | 允许（ND 限制分发，不限制使用和修改） |
| 公开你修改过的 DuckStation 完整 fork | 不允许（分发演绎作品违反 ND） |
| 公开你的 SpinalHDL + Verilator C++ wrapper | 允许（你的代码，你自己的许可证） |
| 公开你写的 DuckStation patch 文件 | 允许（patch 是你原创，不属于 DuckStation 演绎作品，这是模拟器/mod 社区的常见做法） |

你的 SpinalHDL 代码库从头到尾不受 DuckStation 许可证影响。

如果日后许可证成为实质障碍，可以考虑请求 DuckStation 作者接受一个最小扩展点（多一个 `GPURenderer` 枚举值 + 一个弱符号钩子），或切换到 GPL 许可的替代模拟器。

---

## 5. 总结

1. **写 GpuFpgaBackend 能行**——它在阶段二只测渲染管线，命令前端和 CRTC 在阶段一独立验证
2. **改动极小**——DuckStation 约 10 行修改，g_gpu 和 VideoThread 不动
3. **先硬件、后集成**——阶段一用独立 testbench 验证每个子模块，阶段二才接入模拟器
4. **许可证不是当前问题**——本地测试不受 ND 限制，公开时用 patch 文件即可
5. **阶段二到阶段三代码复用率高**——只改通信层的两三个函数
