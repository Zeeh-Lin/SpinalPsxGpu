# Spinal PSX GPU

[![SpinalHDL](https://img.shields.io/badge/SpinalHDL-1.12.3-8e44ad.svg)](https://github.com/SpinalHDL/SpinalHDL)
[![Scala](https://img.shields.io/badge/Scala-2.13.14-dc322f.svg)](https://www.scala-lang.org/)

> PlayStation 1 GPU reimplemented in SpinalHDL — targeting [DuckStation](https://github.com/stenzek/duckstation) compatibility and eventual FPGA deployment.

English | [简体中文](README_zh.md)

---

## Overview

A cycle-accurate recreation of the original PlayStation 1 GPU using SpinalHDL. The goal is to produce a synthesizable core that can integrate with [DuckStation](https://github.com/stenzek/duckstation) as a replacement renderer, and ultimately run PS1 games on FPGA.

## Project Structure

```
.
├── build.sbt              # SBT build config
├── src/
│   └── PsxGpu/            # SpinalHDL hardware sources
├── project/               # SBT plugins & settings
├── third_party/           # Reference submodules
│   ├── duckstation/       #   DuckStation emulator
│   └── psx-spx/           #   PSX SPX technical reference
└── .scalafmt.conf         # Scala formatting config
```

## Prerequisites

| Tool | Version |
|------|---------|
| Scala | 2.13.14 |
| SBT | 1.10.2 |
| SpinalHDL | 1.12.3 |

## Quick Start

```sh
# Clone with submodules
git clone --recurse-submodules <repo-url>
```

## Important Notice on Reference Material

The [psx-spx](https://psx-spx.consoledev.net/graphicsprocessingunitgpu/) technical reference — a conversion of Martin "nocash" Korth's PlayStation specs (originally at [problemkaputt.de](https://problemkaputt.de/psx-spx.htm)) — is **not a clean-room reverse engineering document**. A substantial portion of it was copied, paraphrased, or derived from confidential Sony documentation and source code distributed through the Psy-Q SDK (see [psx.arthus.net/sdk/Psy-Q](https://psx.arthus.net/sdk/Psy-Q/)).

Because this project uses psx-spx as a technical reference, and psx-spx itself derives from proprietary Sony materials, **this implementation should not be considered clean-room**.

## References

- [SpinalHDL Documentation](https://spinalhdl.github.io/SpinalDoc-RTD/)
- [DuckStation Emulator](https://github.com/stenzek/duckstation)
- [PSX SPX — nocash PSX Specs](https://psx-spx.consoledev.net/graphicsprocessingunitgpu/)
- [Original nocash PSX document](https://problemkaputt.de/psx-spx.htm)
