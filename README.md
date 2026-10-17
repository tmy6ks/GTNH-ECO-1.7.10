<h1 align="center">Neo ECO AE Extension</h1>

<p align="center">
    High-performance AE2 storage, crafting, and computation multiblocks for GT New Horizons.
</p>

<p align="center">
    <img src="https://img.shields.io/badge/Minecraft-1.7.10-62b47a?style=for-the-badge" alt="Minecraft 1.7.10">
    <img src="https://img.shields.io/badge/Loader-Forge-e68c32?style=for-the-badge" alt="Forge">
    <img src="https://img.shields.io/badge/Platform-GTNH-d4af37?style=for-the-badge" alt="GT New Horizons">
    <a href="https://github.com/Yang120231/GTNH-ECO-1.7.10">
        <img src="https://img.shields.io/badge/Source-GitHub-24292f?style=for-the-badge&amp;logo=github" alt="GitHub">
    </a>
</p>

<h3 align="center">

English | [简体中文](/README_ZH_CN.md) | [繁體中文](/README_ZH_HK.md) | [文言](/README_LZH.md)

</h3>

## Overview

Neo ECO AE Extension - GTNH 1.7.10 is a rewrite of [Neo ECO AE Extension](https://github.com/DancingSnow0517/NeoECOAEExtension) for Minecraft 1.7.10 and the GT New Horizons ecosystem. It brings the modern project's high-performance Applied Energistics 2 storage, crafting, and computation concepts to GTNH.

Because Forge 1.7.10, GTNH AE2, NEI, rendering, registries, and data formats differ substantially from modern Minecraft, this project was rebuilt for the legacy environment rather than directly backporting the modern source code. Some artwork and visual assets originate from the modern project.

## What does the GTNH 1.7.10 edition offer?

The mod introduces three independent multiblock systems for storage, crafting, and computation. Each system is available in L4, L6, and L9 tiers, with higher tiers providing greater capacity and performance.

It also provides ECO storage cells and matrices, high-throughput crafting components, computation hardware, GTNH-compatible interfaces, and NEI integration designed for large late-game AE2 networks.

## Important Reminder

This GTNH 1.7.10 edition and the [modern Neo ECO AE Extension project](https://github.com/DancingSnow0517/NeoECOAEExtension) are independently maintained. The modern project is led and developed by **DancingSnow0517**; this GTNH 1.7.10 rewrite is maintained by **Yang120231**.

Please do not submit issues, bug reports, or support requests for this GTNH edition to the modern project. Likewise, problems specific to modern Minecraft versions should be reported to the modern project rather than here.

## Development

This project uses the GTNH development toolchain and GTNH-maintained forks of AE2, NEI, CodeChickenCore, and related libraries. Development notes are available under [`docs/`](/docs/README.md).

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

## Custom Upload Logic (this fork's additions)

> For the full diff against upstream, see [MODIFICATIONS.md](/MODIFICATIONS.md). Upstream source, LICENSE, and authorship are preserved as-is; the following logic is layered on top as **ASM bytecode patches** under `patches/` — upstream `src/` is untouched.

This fork adds a set of GTNH mold-recipe-oriented improvements to pattern auto-upload:

1. **Upload list shows the mold name**: when the target's non-consuming item slot holds the correct mold, the list shows `original name [can upload <mold name> recipe]`; empty or unrelated targets keep the original name without spurious suffixes.
2. **Integrated / programming circuits are not molds**: `integrated_circuit`, `prog_toolkit` (Programming Toolkit MK.II), and `programming_circuit` are never misidentified as molds.
3. **sciencenotleisure input hatches / humongous input hatches supported**: molds placed in these hatches are recognized; when an ME interface is adjacent to such a hatch, the list and auto-upload also show/select the mold inside.
4. **Targets with the correct mold are ranked first**: auto-upload is more likely to pick the target that already holds the matching mold.
5. **Matching fix**: recipes that need a mold now match the target that actually holds the corresponding mold, fixing the original "wrong target uploaded" behavior.
6. **Duplicate patterns are rejected and recycled into blank patterns**: before uploading, the session checks whether the network already holds a pattern for the same recipe, comparing the pattern NBT **while ignoring the `encodedAt` encoding timestamp** (upstream compared it too, and since that timestamp differs on every encode, duplicate detection never fired). If a pattern for the same recipe already exists, the upload is rejected and that pattern is **recycled back into a blank pattern** and returned to the network / player instead of creating a duplicate.

You can use the prebuilt `neoecoae-7.3.0-beta2-fix1.jar` attached to the Release (all patches already injected, drop it into `.minecraft/mods`); for a from-source build, see [MODIFICATIONS.md](/MODIFICATIONS.md). A 100% upstream-stock `neoecoae-7.3.0-beta2-stock.jar` (none of this fork's patches) is also provided.

## License

This project is distributed under the [GNU General Public License v3.0](/LICENSE). Credits for the modern project and the GTNH rewrite are retained in the project metadata.
