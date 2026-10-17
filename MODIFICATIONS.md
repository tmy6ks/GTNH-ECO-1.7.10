# 本 fork 的额外修改：磨头（Mold）识别与上传优化

本仓库基于 [Yang120231/GTNH-ECO-1.7.10](https://github.com/Yang120231/GTNH-ECO-1.7.10)（上游，
GPL-3.0）。**上游的代码、LICENSE、作者署名全部原样保留**，本 fork 只在此之上叠加一组"磨头识别"
补丁，解决原版在 GTNH 里使用磨具类配方时的几个体验问题。

## 改了什么（相对上游）

上游在 `442305f` 已吸收了本补丁早期的部分内容（输入总成路由 `addCraftingInputControllerRoutes`、
排序框架 `compatibilityRankWithRecipeType`）。本 fork 额外补齐的是**磨头（模具）相关**逻辑：

1. **上传列表显示磨头名**：目标不消耗物品槽里有正确磨具时，列表里显示
   `原名 [可上传<磨头名>的配方]`；空槽或无关目标显示原名（不再出现垃圾后缀）。
2. **集成电路/编程电路不算磨具**：`integrated_circuit`、`prog_toolkit`（编程工具箱 MK.II）、
   `programming_circuit` 不会被误当成磨头。
3. **支持 sciencenotleisure 的输入总成 / 巨型输入总成**：磨具放在这些总成里也能被识别；
   ME 接口贴着这些总成时，列表与自动上传也会显示/选中里面的磨头。
4. **有正确磨头的目标排序优先**（自动上传更可能选对）。
5. **匹配修正**：需要磨具的配方能正确匹配到放了对应磨具的目标（修复原版"传错目标"）。

## 实现方式说明（重要）

这些修改以 **ASM 字节码补丁** 的形式存在（见 `patches/`），而不是改上游 `.java` 源码。原因：
运行时反编译产物是混淆名、且本地无 GTNH 开发工具链无法编译验证，因此改动保留为可审计的
ASM 补丁脚本源码。上游的 `src/` 目录**完全未被改动**。

## 上游原版基线（重要）

任何「还原 / 对比 / 重新打补丁」操作都必须以 **上游原版 jar 整包**为基准，不能从已打补丁的 jar 里挑几个类还原。

| 项 | 值 |
|---|---|
| 文件 | `neoecoae-7.3.0-beta2.jar`（Release `stock-beta2` 附件同名） |
| 大小 | `1112288` B |
| MD5 | `f2dbf28640d4b63ad673ec99e3b11360` |
| class 数 | 297（jar 条目 886） |
| 补丁标记 | **0** —— `neoecoae$holdsRecipe` / `moldForHost` / `rankBonus` / `specialSlotFor` / `getMoldSuffix` / `sameRecipe` 等 20 个方法名逐类扫描均无命中 |

**踩过的坑**：曾用「从补丁 jar 里挑 `PatternUploadTarget` + `PatternRecipeMatcher` 还原」的方式拼出一个所谓 stock jar，结果 `PatternUploadSession` 仍在调用 `neoecoae$holdsRecipe`，而该方法已随 `PatternUploadTarget` 被还原掉 → 运行时抛 `NoSuchMethodError` 崩溃。**只还原部分类必然产生不自洽的混合体。**

`PatternUploadSession` 里的 `neoecoae$holdsRecipe` 调用本身也是补丁的一部分（原版 29833 B / 补丁版 32560 B），完整还原只能整包替换。

## 如何构建带磨头补丁的 jar

1. 按上游 README 构建官方 jar：`./gradlew build`（需要 GTNH 开发工具链）。
2. 用 `patches/inject56.py` 把补丁类注入构建产物：
   ```
   python patches/inject56.py <官方jar> <产物jar> <条目名> <class文件路径>
   ```
3. 或者直接用 GitHub Release 里附带的 `neoecoae-7.3.0-beta2-fix65-working.jar`（已注入全部补丁，可直接放进
   `.minecraft/mods`）。

## GPL-3.0 合规

- 本 fork 仍以 **GPL-3.0** 发布，保留上游 LICENSE 与作者署名（Yang120231；现代版 DancingSnow0517）。
- 分发 patched jar 时，对应源码即：上游源码 + 本仓库 `patches/` 下的 ASM 补丁脚本。

## patches/ 文件说明

| 文件 | 作用 |
|---|---|
| `PatchUploadTarget.java` | 修改 `PatternUploadTarget`：磨头后缀显示、磨头检测、排序优先 |
| `PatchMold14.java` | 修改 `PatternRecipeMatcher`：磨头收集与匹配核心 |
| `DedupTargetOnly.java` | 新增 `sameRecipe` / `holdsRecipe`（去重/持有判定） |
| `inject56.py` | 将补丁 class 注入到构建出的 jar（只替换目标 class，不碰语言文件） |
