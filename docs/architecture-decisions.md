# SpinalPsxGpu 架构决策记录

> 日期: 2026-05-25
> 背景: 将 SpinalHDL 实现的 PSX GPU 接入 DuckStation 作为后端渲染器

---

## 1. 项目目标

### 1.1 最终目标
- 在 FPGA 上运行 SpinalHDL 实现的 PSX GPU
- FPGA 与 PC 上的 DuckStation 通信
- FPGA 完成 GPU 渲染，结果传回 DuckStation（或由 FPGA 直接驱动显示器）

### 1.2 现阶段降级目标
在 PC 上验证 SpinalHDL GPU 逻辑的正确性：
- SpinalHDL 生成 Verilog
- Verilator 编译为 C++ 仿真模型
- 打包成 C++ wrapper，直接链接进 DuckStation，同进程调用
- **不采用**跨进程 IPC（socket/共享内存/文件），避免实时渲染的性能瓶颈

---

## 2. DuckStation GPU 架构概览

DuckStation 的 GPU 分三层：

```
┌─────────────────────────────────────────┐
│ CPU 线程: GPU (gpu.h/cpp)               │
│   - 解析 GP0/GP1 命令                   │
│   - 维护 DMA、FIFO、CRTC 时序           │
│   - 将命令打包成 VideoThreadCommand     │
├─────────────────────────────────────────┤
│ 命令队列 → VideoThread                  │
│   - 线程间通信                          │
│   - 后端生命周期管理                    │
├─────────────────────────────────────────┤
│ 视频线程: GPUBackend 派生类             │
│   - GPU_HW: 硬件渲染 (OGL/Vulkan/D3D)  │
│   - GPU_SW: 软件渲染 (CPU 光栅化)      │
│   - GPUNullBackend: 空实现             │
│   ← 我们要在这里新增 GPU_FPGA          │
└─────────────────────────────────────────┘
```

### 2.1 关键文件

| 文件 | 作用 |
|---|---|
| `src/core/gpu_backend.h` | 后端抽象基类，定义所有纯虚接口 |
| `src/core/video_thread_commands.h` | 命令数据结构，CPU 线程 → 视频线程的协议 |
| `src/core/gpu_backend.cpp` | 命令分发总入口 `HandleCommand()` |
| `src/core/gpu_sw.h/cpp` | 软件渲染后端，不依赖 GPU API，适合参考 |
| `src/core/video_thread.cpp` | 后端创建逻辑 `CreateGPUBackendOnThread()` |
| `src/core/types.h` | `GPURenderer` 枚举 |

### 2.2 全局 VRAM

- `g_vram[VRAM_WIDTH * VRAM_HEIGHT]` (1024x512 x16bit)
- CPU 侧和 DMA 逻辑会直接读写这块内存
- 后端必须在 `ReadVRAM()` 时把数据写回 `g_vram[]`

---

## 3. 技术路线对比

| 方案 | 描述 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. Verilator C++ wrapper** | SpinalHDL→Verilog→Verilator→C++类，同进程链接 | 零 IPC 开销；与 DuckStation 无缝集成；性能最好 | 需要写 C++ wrapper 驱动时钟 | **推荐** |
| B. SpinalSim (Scala) | SpinalHDL 原生仿真，Scala testbench | 不需要写 Verilog testbench | Scala/JVM ↔ C++ 交互麻烦；性能差 | 不推荐 |
| C. 跨进程 IPC | DuckStation 和仿真器作为两个进程，socket/共享内存通信 | 解耦 | 实时渲染延迟不可接受 | 不推荐 |

**核心区别**: Verilator C++ wrapper 是把硬件模型编译成 C++ 对象，直接在 DuckStation 进程内 `new` 出来，用成员函数读写信号。IPC 方案是跨进程的，有巨大的通信开销。

---

## 4. 推荐架构：两阶段复用

### 4.1 阶段一 — PC 验证（Verilator 仿真）

```
DuckStation (C++)
  └── GpuFpgaBackend : GPUBackend
        ├── 命令 → Verilator 信号赋值
        ├── 推进时钟 cycle
        └── 读回 VRAM / display
              └── VMyTopLevel (Verilator)
                    └── SpinalHDL 生成的 Verilog
```

**具体步骤**:
1. SpinalHDL 生成 Verilog
2. `verilator --cc --trace --top-module MyTopLevel MyTopLevel.v`
3. 写 `GpuFpgaBackend` 继承 `GPUBackend`，在虚函数里驱动 `VMyTopLevel`
4. VRAM 直接映射：让 Verilator 模型的内部 VRAM 数组与 `g_vram[]` 共享或快速同步

### 4.2 阶段二 — FPGA 部署（最小改动）

```
DuckStation (C++)
  └── GpuFpgaBackend : GPUBackend   ← 同一份代码
        ├── 命令 → PCIe/Ethernet 包
        ├── 发送并等待 ACK
        └── 读回 VRAM / display
              └── FPGA 开发板
```

**改动点**:
- `VMyTopLevel* m_dut` → PCIe 设备句柄 / socket
- `m_dut->eval()` → `pcie_write_reg()` / `eth_send_packet()`
- 上层命令转换逻辑**完全复用**

---

## 5. 关键设计决策

### 5.1 决策：FPGA 侧暴露什么接口？

**选择 A: 真实 GP0/GP1 寄存器接口**（推荐）

FPGA 侧和真实 PSX GPU 一样，暴露 GP0 数据端口、GP1 控制寄存器、GPUSTAT 状态寄存器。

```cpp
void DrawPolygon(const GPUBackendDrawPolygonCommand* cmd) {
    // 把 DuckStation 解析好的命令重新编码为 GP0 数据流
    for (每个顶点) {
        write_gp0_fifo(vertex_data);
    }
    wait_gpu_idle();
}
```

- **优点**: FPGA 侧更真实，长期价值大，未来可直接替换软件命令解析层
- **缺点**: DuckStation 已经解析好命令，需要重新编码，有少量冗余工作

**选择 B: 高层绘制接口**

FPGA 侧直接接受高层命令:"绘制三角形，顶点数组如下"。

```cpp
void DrawPolygon(const GPUBackendDrawPolygonCommand* cmd) {
    fpga_send_draw_polygon(cmd->num_vertices, cmd->vertices);
}
```

- **优点**: 效率高，接口简洁
- **缺点**: FPGA 侧需要自定义命令解码状态机，与真实 GPU 行为不一致

**结论**: 选择 A。因为项目目标是"硬件实现 PSX GPU"，接口越接近真实硬件，验证价值越高。

### 5.2 决策：VRAM 同步策略

**选择 A: 后端按需同步**（推荐）

- `ReadVRAM()` 时从 Verilator/FPGA 读回数据，写入 `g_vram[]`
- `UpdateVRAM()` / `FillVRAM()` / `CopyVRAM()` 时把数据发送到 Verilator/FPGA
- 平时两边各自维护自己的 VRAM 副本

**选择 B: 始终共享同一块内存**

- Verilator 模型的 VRAM `Mem` 在 C++ 里是一个数组，可以取指针直接操作
- 但 FPGA 阶段无法共享内存，需要重写

**结论**: 阶段一先用选择 A（最通用），如果 Verilator 仿真发现 VRAM 拷贝是瓶颈，再考虑在阶段一临时用选择 B 优化。

---

## 6. 下一步行动

### 6.1 立即行动
1. 在 DuckStation `src/core/types.h` 的 `GPURenderer` 枚举中新增 `HardwareFPGA`
2. 新建 `src/core/gpu_fpga.h` 和 `src/core/gpu_fpga.cpp`
3. 实现一个**最小可运行**的 `GpuFpgaBackend`（可以先从 `GPUNullBackend` 复制，逐步填充）
4. 修改 `video_thread.cpp` 的 `CreateGPUBackendOnThread()`，支持创建 FPGA 后端

### 6.2 SpinalHDL 侧
1. 定义顶层模块 `PsxGpuTop` 的接口（时钟、复位、GP0 写入、GP1 写入、GPUSTAT 读出、VRAM 读写端口）
2. 先生成一个最小可运行的 Verilog（哪怕只能处理 `FillVRAM`）
3. 用 Verilator 编译，确认 C++ wrapper 能驱动它

### 6.3 第一个里程碑
让 DuckStation 启动时选择 `HardwareFPGA` 后端，能够成功运行并显示画面（哪怕渲染结果是错的或空的）。验证命令通路是通的。

---

## 7. 参考文件路径

```
third_party/duckstation/src/core/gpu_backend.h          # 后端接口定义
third_party/duckstation/src/core/video_thread_commands.h # 命令数据结构
third_party/duckstation/src/core/gpu_backend.cpp        # 命令分发 + NullBackend 模板
third_party/duckstation/src/core/video_thread.cpp       # 后端创建逻辑
third_party/duckstation/src/core/types.h                # GPURenderer 枚举
third_party/duckstation/src/core/gpu_sw.h/cpp           # 软件后端参考
```
