# 本 fork 的额外修改：模头（Mold）识别与上传优化

本仓库基于 [Yang120231/GTNH-ECO-1.7.10](https://github.com/Yang120231/GTNH-ECO-1.7.10)（上游，
GPL-3.0）。**上游的代码、LICENSE、作者署名全部原样保留**，本 fork 只在此之上叠加一组"模头识别"
补丁，解决原版在 GTNH 里使用模具类配方时的几个体验问题。

## 改了什么（相对上游）

上游在 `442305f` 已吸收了本补丁早期的部分内容（输入总成路由 `addCraftingInputControllerRoutes`、
排序框架 `compatibilityRankWithRecipeType`）。本 fork 额外补齐的是**模头 / 模具相关**逻辑：

1. **上传列表显示模头名**：目标不消耗物品槽里有正确模具时，列表里显示
   `原名 [可上传<模头名>的配方]`；空槽或无关目标显示原名（不再出现垃圾后缀）。
2. **集成电路/编程电路不算模具**：`integrated_circuit`、`prog_toolkit`（编程工具箱 MK.II）、
   `programming_circuit` 不会被误当成模头。
3. **支持 sciencenotleisure 的输入总成 / 巨型输入总成**：模具放在这些总成里也能被识别；
   ME 接口贴着这些总成时，列表与自动上传也会显示/选中里面的模头。
4. **有正确模头的目标排序优先**（自动上传更可能选对）。
5. **匹配修正**：需要模具的配方能正确匹配到放了对应模具的目标（修复原版"传错目标"）。
6. **相同样板禁止重复上传，并回收为空白样板退回网络**：上传前检查网络中是否已有同配方样板。
   比较时剔除样板 NBT 顶层的 `encodedAt` 编码时间戳——原版直接比 NBT，而 `encodedAt` 每次编码都不同，
   导致 `neoecoae$recycleDuplicate` 的去重判定永远为假。修复后若网络中已存在同配方样板，
   则**拒绝本次上传**，并把该样板**回收为空白样板**退回网络 / 玩家。
7. **不消耗物品反查配方池（补全识别）**：GT 里的「不消耗物品」有多个类别（模具、模头、粉为催化剂、
   透镜等），且每类都有很多种，无法枚举。改为向配方池反查——遍历 `RecipeMap.ALL_RECIPE_MAPS`，
   两条判据任一命中即确认：
   - `RecipeMap.getNonConsumedInputItemIds()`：其内部遍历该池全部配方、收集 `mInputs` 中
     `stackSize == 0` 的物品，对应**粉催化剂、透镜**等；
   - `GTRecipe.mSpecialItems`：特种槽，声明为 `Object`（实际可能是 `ItemStack` / `ItemStack[]` /
     `Object[]`），对应**模具、模头**等。GT 自带的 `getNonConsumedInputItemIds()` **不扫描它**，
     因此必须单独扫。

   命中后即得所属配方池（`unlocalizedName` 可推断机器类型）。结果缓存在
   `neoecoae$ncCache`（`ItemId -> String`），且仅在常规匹配全部失败时才触发反查。

## 识别原理（完整链路）

### 1. 容器发现

`neoecoae$adjacentMachines(host)` 先从上传目标取出 `TileEntity`，按
`TileEntity` → `IInterfaceHost` → `IInterfaceViewable` → `IPatternUploadProvider` 四路尝试
（neoecoae 的 `TileECOInterface` 本身是 `TileEntity`，第一条即命中），
然后扫描**全部 6 个方向**，凡相邻方块是 `IGregTechTileEntity`，取其
`IMetaTileEntity` 加入候选列表。不依赖 `getTargets()`，也就不受接口方向配置影响。

### 2. 不消耗物品收集（按容器类型分支）

`neoecoae$collectSpecialFromHatch(hatch, map, cands)`：

| 容器类型 | 读取方式 |
|---|---|
| 普通 `MTEHatch`（**输入总线**、输入仓） | `func_70302_i_()` 取槽数 → 逐槽 `func_70301_a(i)`，跳过 `getCircuitSlot()` 电路槽，收集所有非空物品 |
| `IDualInputHatch`（**输入总成** / **巨型输入总成** / **样板总成** / **ME 输入总成**） | ① 先读 `getSharedItems()`；② **无条件**再扫 `getRealInventory()`，并跳过 `toString` 含 `Encoded` / `attern` 的样板 |

> **为什么必须双来源读取**：`getSharedItems()` 通常只含电路与手动槽，**不消耗物品槽在
> `getRealInventory()` 里**。若按旧逻辑「只有 `getSharedItems()` 一个物品都没收到时才回退」，
> 一旦 shared 非空（例如放了编程电路），就永远不会扫真实仓库 → 总成里的模具永远读不到。
> 这是「输入总成 / 巨型输入总成识别不出」的真正原因。

### 3. 判定：反查配方池（不枚举物品）

`neoecoae$mapNamesFor(ItemStack)` 遍历 `RecipeMap.ALL_RECIPE_MAPS`，两条判据**任一命中**即确认：

| 判据 | 数据来源 | 覆盖类别 |
|---|---|---|
| `RecipeMap.getNonConsumedInputItemIds()` | GT 自身实现：遍历该池全部配方，收集 `mInputs` 中 `stackSize == 0` 的物品 ItemId | **粉催化剂、透镜** 等 |
| `GTRecipe.mSpecialItems` | 本补丁新增扫描；该字段声明为 `Object`，实际可能是 `ItemStack` / `ItemStack[]` / `Object[]` | **模具、模头** 等特种槽物品 |

两者互补：GT 自带的判据**完全不看 `mSpecialItems`**，而模具/模头恰恰存在那里。

命中后返回该池的 `unlocalizedName`（多个用逗号分隔）——**配方池即机器类型**。
结果缓存在 `neoecoae$ncCache`（`HashMap<ItemId, String>`），且只在常规匹配全部失败时才触发反查，
正常路径零额外开销。

### 4. 匹配与显示

- 命中后把该物品作为「模具」加入 `findRecipeQuery()` 的输入数组，使配方能被正确命中
  （`specialSlotSensitive=false` 的配方图如挤压机，必须把模具放进 items 才能匹配）；
- 上传列表显示 `原名 [可上传<模头名>的配方]`；
- 有正确模具的目标 `compatibilityRank` 优先，自动上传更可能选中正确目标。

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

## 如何构建带模头补丁的 jar

1. 按上游 README 构建官方 jar：`./gradlew build`（需要 GTNH 开发工具链）。
2. 用 `patches/inject56.py` 把补丁类注入构建产物：
   ```
   python patches/inject56.py <官方jar> <产物jar> <条目名> <class文件路径>
   ```
3. 或者直接用 GitHub Release 里附带的 `neoecoae-7.3.0-beta2-fix3.jar`（已注入全部补丁，可直接放进
   `.minecraft/mods`）。

## GPL-3.0 合规

- 本 fork 仍以 **GPL-3.0** 发布，保留上游 LICENSE 与作者署名（Yang120231；现代版 DancingSnow0517）。
- 分发 patched jar 时，对应源码即：上游源码 + 本仓库 `patches/` 下的 ASM 补丁脚本。

## patches/ 文件说明

| 文件 | 作用 |
|---|---|
| `PatchUploadTarget.java` | 修改 `PatternUploadTarget`：模头后缀显示、模头检测、排序优先 |
| `PatchMold14.java` | 修改 `PatternRecipeMatcher`：模头收集与匹配核心 |
| `PatchDedup.java` | **去重核心**：新增 `sameRecipe`（比较前剔除 `encodedAt`）与 `holdsRecipe`，并把 `PatternUploadSession.neoecoae$recycleDuplicate` 里的 `hasPattern` 调用改写为 `holdsRecipe` |
| `DedupTargetOnly.java` | 只重建 `PatternUploadTarget` 的变体（仅追加 `sameRecipe` / `holdsRecipe`，不改 Session）——用于 Session 已打过补丁的中间产物 |
| `inject56.py` | 将补丁 class 注入到构建出的 jar（只替换目标 class，不碰语言文件） |
