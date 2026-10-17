<p align="center"><img src="/images/logo.png" alt="Logo"></p>
<h1 align="center">Neo ECO AE Extension - GTNH 1.7.10</h1>
<p align="center">面向 GT New Horizons / Minecraft 1.7.10 的 ECO AE Extension。</p>
<h3 align="center">

[English](/README.md) | 简体中文 | [繁體中文](/README_ZH_HK.md) | [文言](/README_LZH.md)

</h3>

## 概述

Neo ECO AE Extension - GTNH 1.7.10 是 [Neo ECO AE Extension](https://github.com/DancingSnow0517/NeoECOAEExtension) 面向 Minecraft 1.7.10 与 GT New Horizons 生态的重写版本，旨在将现代版项目中的高性能 Applied Energistics 2 存储、合成与计算设计带到 GTNH。

由于 Forge 1.7.10、GTNH AE2、NEI、渲染、注册机制和数据格式均与现代 Minecraft 存在显著差异，本项目针对旧版本环境重新实现，而非直接移植现代版源代码。部分美术与视觉资源来源于现代版项目。

## GTNH 1.7.10 版本提供了什么？

本模组引入了相互独立的存储、合成与计算三个多方块系统。每套系统均提供 L4、L6 和 L9 三个等级，等级越高，容量与性能越强。

此外，本模组还提供 ECO 存储元件与存储矩阵、高吞吐量合成组件、计算硬件、适配 GTNH 的交互界面，以及面向大型后期 AE2 网络的 NEI 集成。

## 重要提示

本 GTNH 1.7.10 版本与[现代版 Neo ECO AE Extension 项目](https://github.com/DancingSnow0517/NeoECOAEExtension)相互独立维护。现代版项目由 **DancingSnow0517** 主导开发；本 GTNH 1.7.10 重写版本由 **Yang120231** 维护。

请勿将本 GTNH 版本的 Issue、错误报告或技术支持请求提交到现代版项目。同样，现代 Minecraft 版本特有的问题也应提交到现代版项目，而不是本仓库。

## 开发

本项目使用 GTNH 开发工具链，以及由 GTNH 维护的 AE2、NEI、CodeChickenCore 等依赖分支。开发与移植笔记位于 [`docs/`](/docs/README.md)。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

## 本 fork 的额外修改：上传与模头（Mold）逻辑

> 与上游代码的完整差异见 [MODIFICATIONS.md](/MODIFICATIONS.md)。上游源码、LICENSE 与作者署名均原样保留，以下逻辑以 **ASM 字节码补丁** 形式叠加（位于 `patches/`），上游 `src/` 未被改动。

本 fork 在样板（Pattern）自动上传环节做了一组针对 GTNH 模具类配方的优化：

1. **上传列表显示模头名**：当目标机器的不消耗物品槽里放着正确的模具时，上传列表显示
   `原名 [可上传<模头名>的配方]`；空槽或无关目标显示原名，不再出现垃圾后缀。
2. **集成电路 / 编程电路不算模具**：`integrated_circuit`、`prog_toolkit`（编程工具箱 MK.II）、
   `programming_circuit` 不会被误识别为模头。
3. **支持 sciencenotleisure 的输入总成 / 巨型输入总成**：模具放在这些总成里也能被识别；
   当 ME 接口贴着这些总成时，列表与自动上传同样会显示 / 选中其中的模头。
4. **有正确模头的目标排序优先**：自动上传更可能选到已放好对应模具的目标。
5. **匹配修正**：需要模具的配方能正确匹配到放置了对应模具的目标，修复原版可能"传错目标"的问题。
6. **相同样板禁止重复上传，并回收为空白样板退回网络**：上传前会检查网络中是否已存在同配方的样板，
   比较时**忽略样板 NBT 里的 `encodedAt` 编码时间戳**（原版正是因为该时间戳每次都不同，
   导致去重判定永远失败）。若已存在同配方样板，则拒绝本次上传，并把该样板**回收为空白样板**
   退回网络 / 玩家，不再产生重复样板。

可直接使用 Release 中附带的 `neoecoae-7.3.0-beta2-fix65-working.jar`（已注入全部补丁，放进 `.minecraft/mods` 即可）；
如需自行构建，步骤见 [MODIFICATIONS.md](/MODIFICATIONS.md)。
另含一份 100% 还原上游的 `neoecoae-7.3.0-beta2-stock.jar`（不含本 fork 任何补丁）。

## 许可证

本项目以 [GNU 通用公共许可证 v3.0](/LICENSE) 发布。现代版项目与 GTNH 重写版本的作者信息均保留在项目元数据中。
