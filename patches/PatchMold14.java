import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;

/**
 * fix14: 在 fix13 基础上，处理 `specialSlotSensitive=false` 的配方图（如挤压机 extruder）。
 * 日志显示 extruder 的 sens=false，说明 GT 在 filterFindRecipe 中不会比较 specialSlot。
 * 对这类图，把候选模具直接加入 items 数组再做 findRecipeQuery()，才能命中正确的配方。
 * 对 sens=true 的图（如压模、冲压），仍走原来的 specialSlot(mold) 路径。
 *
 * fix9~fix11 始终无效的真正根因（fix12 才定位到）：
 * PatternUploadTarget.ecoTarget() 把 contextHost 设为 TileECOInterface，
 * 而该类只实现 IGridProxyable / IActionHost / IPriorityHost /
 * ICraftingProvider，**不实现 IInterfaceHost**（也不实现 IInterfaceViewable）。
 * 因此 fix9 起的 adjacentMachines() 第一句
 *     if (!(host instanceof IInterfaceHost)) return result;
 * 恒为真 → 相邻机器列表恒为空 → 模具从未被扫描过。
 * 另外 getTargets() 也不可靠：AE2 接口默认可能未配置任何目标方向。
 *
 * fix12 改动（重写 adjacentMachines）：
 * 1. 取 TileEntity 的四条路都试：TileEntity 自身 → IInterfaceHost.getTileEntity()
 *    → IInterfaceViewable.getTileEntity() → IPatternUploadProvider.getTileEntity()。
 *    TileECOInterface 直接就是 TileEntity 子类，第一条即命中。
 * 2. 扫描**全部 6 个方向**（ForgeDirection.values()，跳过 UNKNOWN），
 *    不再依赖 getTargets()。
 * 3. 不手写 FrameNode，交给 ClassWriter(COMPUTE_FRAMES) 自动重算。
 *
 * fix11 的改动（一并保留）：
 * 1. 原版 firstBusSpecial() / appendNotConsumedContextInner() 的
 *    MTEHatchInputBus 判断放宽到公共父类 MTEHatch，覆盖「输入仓」。
 *    依据：func_70302_i_/func_70301_a 声明在共同祖先 CommonMetaTileEntity，
 *    getCircuitSlot() 声明在 MTEHatch（InputBus 才覆写为真实槽，其余返回 -1），
 *    因此 INVOKEVIRTUAL owner=MTEHatch 合法，虚分发正确。
 * 2. fix9/fix10 新增的相邻机器扫描辅助方法同样放宽到 MTEHatch。
 *
 * 辅助方法：
 * - neoecoae$appendFromHatch(Object,RecipeMap,List)   把单个输入仓/总线的不消耗物品加入 list
 * - neoecoae$firstSpecialFromHatch(Object,RecipeMap,List) 从单个输入仓/总线取第一个不消耗物品
 * - neoecoae$getFieldValue(Object,String)               反射取字段（用于 MTEMultiBlockBase）
 */
public class PatchMold14 {
    private static final String MATCHER = "cn/dancingsnow/neoecoae/crafting/upload/PatternRecipeMatcher";

    private static final String RECIPE_MAP = "gregtech/api/recipe/RecipeMap";
    private static final String LIST = "java/util/List";
    private static final String ITEM_STACK = "net/minecraft/item/ItemStack";
    private static final String GT_UTILITY_ITEM_ID = "gregtech/api/util/GTUtility$ItemId";
    private static final String I_META_TILE_ENTITY = "gregtech/api/interfaces/metatileentity/IMetaTileEntity";
    private static final String MTE_HATCH = "gregtech/api/metatileentity/implementations/MTEHatch";
    private static final String MTE_HATCH_INPUT_BUS = "gregtech/api/metatileentity/implementations/MTEHatchInputBus";
    private static final String MTE_MULTI_BLOCK_BASE = "gregtech/api/metatileentity/implementations/MTEMultiBlockBase";
    private static final String I_INTERFACE_VIEWABLE = "appeng/api/util/IInterfaceViewable";
    private static final String IPatternUploadProvider = "cn/dancingsnow/neoecoae/crafting/upload/IPatternUploadProvider";
    private static final String I_INTERFACE_HOST = "appeng/helpers/IInterfaceHost";
    private static final String I_GREG_TECH_TILE_ENTITY = "gregtech/api/interfaces/tileentity/IGregTechTileEntity";
    private static final String TILE_ENTITY = "net/minecraft/tileentity/TileEntity";
    private static final String FORGE_DIRECTION = "net/minecraftforge/common/util/ForgeDirection";
    private static final String INON_CONSUMED = "gregtech/api/interfaces/INonConsumedItemDisplay";
    private static final String GT_RECIPE = "gregtech/api/util/GTRecipe";
    private static final String IDUAL_INPUT_HATCH = "gregtech/common/tileentities/machines/IDualInputHatch";
    private static final String WORLD = "net/minecraft/world/World";

    public static void main(String[] args) throws Exception {
        Path src = Paths.get(args[0]);
        Path dst = Paths.get(args[1]);
        Files.createDirectories(dst.resolve(MATCHER + ".class").getParent());

        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(src.resolve(MATCHER + ".class"))).accept(cn, ClassReader.EXPAND_FRAMES);
        patchMatcher(cn);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                try {
                    return super.getCommonSuperClass(a, b);
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        cn.accept(cw);
        Files.write(dst.resolve(MATCHER + ".class"), cw.toByteArray());
        System.out.println("Patched " + MATCHER);
    }

    public static void patchMatcherPublic(ClassNode cn) {
        patchMatcher(cn);
    }

    private static void patchMatcher(ClassNode cn) {
        addAppendFromHatch(cn);
        addFirstSpecialFromHatch(cn);
        addGetFieldValue(cn);
        addAdjacentMachines(cn);
        addFirstSpecialFromList(cn);
        addFirstSpecialFromAdjacentHost(cn);
        addCollectSpecialFromHatch(cn);
        addCollectSpecialFromList(cn);
        addSpecialSlotFor2(cn);
        addStripCircuitIfInsensitive(cn);
        addGtify(cn);
        addMoldForHost(cn);
        addDiagMatch(cn);
        addSpecialItemIdSet(cn);
        addSpecialItemHit(cn);
        addMapNamesFor(cn);
        addLogStacks(cn);
        addLog2(cn);

        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$savedHost", "Ljava/lang/Object;", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$diagN", "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$oN", "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$dumpN", "I", null, null));
        // fix36：记录本次 matches 收集到的模具，供 PatternUploadTarget 在上传列表显示（像输入总线 Waila 的〔模头（杆）〕）
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "neoecoae$lastMold", "L" + ITEM_STACK + ";", null, null));
        // fix38diag：给上传/显示探针一条独立计数通道，不被 [M]/[S]/[H] 噪声挤掉
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$diagN2", "I", null, null));
        // fix1（反查路线）：ItemId -> "map1,map2" 缓存，避免重复遍历 ALL_RECIPE_MAPS
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$ncCache", "Ljava/util/HashMap;", null, null));

        for (MethodNode mn : cn.methods) {
            if ("neoecoae$appendNotConsumedContext".equals(mn.name)) {
                patchAppendNotConsumedContext(mn);
            } else if ("neoecoae$specialSlotFor".equals(mn.name)) {
                patchSpecialSlotFor(mn);
            } else if ("neoecoae$appendNotConsumedContextInner".equals(mn.name)) {
                patchWidenBusRefs(mn);
            } else if ("neoecoae$firstBusSpecial".equals(mn.name)) {
                patchWidenBusRefs(mn);
            } else if ("neoecoae$log".equals(mn.name)) {
                patchLog(mn);
            } else if ("matches".equals(mn.name)
                    && mn.desc.equals("(L" + RECIPE_MAP
                        + ";Lappeng/api/networking/crafting/ICraftingPatternDetails;L" + ITEM_STACK + ";)Z")) {
                patchMatchesSpecialSlot(mn);
                patchMatchesStripCircuit(mn);
                patchMatchesAddMold(mn);
                patchMatchesLog(mn);
            } else if ("outputsMatch".equals(mn.name)) {
                patchOutputsMatchLog(mn);
            }
        }
    }

    /**
     * fix23diag：在 matches() 末尾（find() 结果 astore 8 之后）打印 [M] map= / [M] found=，
     * 确证挤出机配方到底有没有被 matches() 判为匹配。
     */
    private static void patchMatchesLog(MethodNode mn) {
        // 找到 invokevirtual FindRecipeQuery.find 之后的 astore 8
        AbstractInsnNode target = null;
        for (AbstractInsnNode n : mn.instructions) {
            if (n.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode m = (MethodInsnNode) n;
                if ("find".equals(m.name) && "()Lgregtech/api/util/GTRecipe;".equals(m.desc)) {
                    AbstractInsnNode nx = n.getNext();
                    if (nx != null && nx.getOpcode() == Opcodes.ASTORE && ((VarInsnNode) nx).var == 8) {
                        target = nx;
                    }
                }
            }
        }
        if (target == null) {
            System.out.println("  patchMatchesLog: target astore 8 not found, skip");
            return;
        }
        InsnList ins = new InsnList();
        // neoecoae$log("[M] map=", map.unlocalizedName)
        ins.add(new LdcInsnNode("[M] map="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, RECIPE_MAP, "unlocalizedName", "Ljava/lang/String;"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        // neoecoae$log("[M] found=", recipe!=null ? "Y":"N")
        ins.add(new LdcInsnNode("[M] found="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        LabelNode lNull = new LabelNode();
        LabelNode lEnd = new LabelNode();
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));
        ins.add(new LdcInsnNode("Y"));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lEnd));
        ins.add(lNull);
        ins.add(new LdcInsnNode("N"));
        ins.add(lEnd);
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        mn.instructions.insert(target, ins);
        System.out.println("  patchMatchesLog: inserted after astore 8");
    }

    /**
     * fix23diag：新增 neoecoae$logStacks(ItemStack, ItemStack)，限流 800 行，
     * 打印 [O] exp=/act= 两个物品的 displayName，供 outputsMatch 诊断使用。
     */
    private static void addLogStacks(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$logStacks",
                "(L" + ITEM_STACK + ";L" + ITEM_STACK + ";)V", null, null);
        InsnList ins = mn.instructions;
        LabelNode lRet = new LabelNode();
        // if (neoecoae$oN >= 800) return;
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$oN", "I"));
        ins.add(new LdcInsnNode(800));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lRet));
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$oN", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$oN", "I"));
        // fix24diag：func_77973_b() = getUnlocalizedName() 是 client-only，服务端调用会 NoSuchMethodError。
        // 改用 e.toString() / a.toString()（Object.toString() 服务端永远可用）。
        // neoecoae$log("  [O] exp=", e!=null ? e.toString() : "null")
        ins.add(new LdcInsnNode("  [O] exp="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        LabelNode eNull = new LabelNode();
        LabelNode eEnd = new LabelNode();
        ins.add(new JumpInsnNode(Opcodes.IFNULL, eNull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new JumpInsnNode(Opcodes.GOTO, eEnd));
        ins.add(eNull);
        ins.add(new LdcInsnNode("null"));
        ins.add(eEnd);
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        // neoecoae$log("  [O] act=", a!=null ? a.toString() : "null")
        ins.add(new LdcInsnNode("  [O] act="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        LabelNode aNull = new LabelNode();
        LabelNode aEnd = new LabelNode();
        ins.add(new JumpInsnNode(Opcodes.IFNULL, aNull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new JumpInsnNode(Opcodes.GOTO, aEnd));
        ins.add(aNull);
        ins.add(new LdcInsnNode("null"));
        ins.add(aEnd);
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        ins.add(lRet);
        ins.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 2;
        mn.maxLocals = 2;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$logStacks");
    }

    /**
     * fix23diag：在 outputsMatch 里每次 areStacksEqual(expected, actual, true) 之前插入
     * DUP2 + neoecoae$logStacks(expected, actual)，打印 [O] exp=/act=（限流 800 行）。
     * 这样当 [M] found=N 时，能直接看到是哪一个产物比对失败。
     */
    private static void patchOutputsMatchLog(MethodNode mn) {
        int n = 0;
        for (AbstractInsnNode n0 : mn.instructions) {
            if (n0.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode m = (MethodInsnNode) n0;
                if ("areStacksEqual".equals(m.name)
                        && m.owner.equals("gregtech/api/util/GTUtility")
                        && m.desc.equals("(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;Z)Z")) {
                    // 调用点前一条应为 iconst_1（true），此时栈为 [expected, actual]
                    AbstractInsnNode prev = m.getPrevious();
                    if (prev == null || prev.getOpcode() != Opcodes.ICONST_1) continue;
                    InsnList ins = new InsnList();
                    ins.add(new InsnNode(Opcodes.DUP2)); // [e,a,e,a]
                    ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$logStacks",
                            "(L" + ITEM_STACK + ";L" + ITEM_STACK + ";)V", false)); // 消费 [e,a] -> [e,a]
                    mn.instructions.insertBefore(prev, ins);
                    n++;
                }
            }
        }
        System.out.println("  patchOutputsMatchLog: injected = " + n);
    }

    /**
     * fix25：在 matches() 的 neoecoae$appendNotConsumedContext 调用之后，插入
     * neoecoae$stripCircuitIfInsensitive(map, itemList)。
     *
     * 根因（fix24diag 日志实锤）：matches() 把样板的编程电路（validCircuit 加入 items）
     * 一起传给 GT 的 find()。对 specialSlotSensitive=false 的机器（如挤出机），
     * filterFindRecipe 只查 isRecipeInputEqual(items)，而挤出机铁锭→铁杆配方 mInputs
     * 只有铁锭，多出的 integrated_circuit 令 isRecipeInputEqual 返回 false，
     * find() 返回 null → matches() 恒 N（实测 156/156 全 N，从没进 outputsMatch）。
     *
     * 修复：sens=false 时从 items 剔除 integrated_circuit，让
     * find().items([铁锭]).filter(outputsMatch) 靠产物(铁杆)锁定铁杆配方。
     * sens=true 的机器（assembler 等，配方本身含编程电路）不受影响。
     */
    private static void patchMatchesStripCircuit(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (MATCHER.equals(m.owner) && "neoecoae$appendNotConsumedContext".equals(m.name)) {
                    InsnList ins = new InsnList();
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 0));   // map
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 3));   // itemList (matches 局部槽 3)
                    ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$stripCircuitIfInsensitive",
                            "(L" + RECIPE_MAP + ";Ljava/util/List;)V", false));
                    mn.instructions.insert(insn, ins);            // insertAfter appendNotConsumedContext
                    System.out.println("  patchMatchesStripCircuit: inserted after appendNotConsumedContext");
                    return;
                }
            }
        }
        System.out.println("  patchMatchesStripCircuit: appendNotConsumedContext call NOT found, skip");
    }

    /**
     * fix25/fix26：private static void neoecoae$stripCircuitIfInsensitive(RecipeMap, List)
     *
     * 当 map 的 backend.properties.specialSlotSensitive == false 时：
     *  1. 把 list 里所有编程电路移除（PatternCircuitCompat.isVirtualCircuit，双端安全；
     *     勿用 GETFIELD field_77993_d，该 SRG 字段运行时不存在会 NoSuchFieldError）。
     *  2. fix26：把剩余输入用 GTOreDictUnificator.get(true, s) 统一化成 GT 形态
     *     （item.ingotIron -> gt.metaitem 铁锭）。根因：isRecipeInputEqual →
     *     maxParallelCalculatedByInputs → getAssociation(item.ingotIron)=null（原版物品
     *     无 GT oreDict association），matchesRecipe 对 gt.metaitem 配方恒 false → find() null。
     *  诊断：打印 [U] raw=/unified= 便于确认统一化是否生效。
     */
    private static void addStripCircuitIfInsensitive(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$stripCircuitIfInsensitive",
                "(L" + RECIPE_MAP + ";Ljava/util/List;)V", null, null);
        InsnList ins = mn.instructions;
        LabelNode lRet = new LabelNode();
        LabelNode lLoop = new LabelNode();

        // if (map == null || list == null) return;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));

        // Object props = getFieldValue(map.getBackend(), "properties");
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getBackend",
                "()Lgregtech/api/recipe/RecipeMapBackend;", false));
        ins.add(new LdcInsnNode("properties"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));

        // Object sens = getFieldValue(props, "specialSlotSensitive");
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new LdcInsnNode("specialSlotSensitive"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));

        // if (!Boolean.FALSE.equals(sens)) return;  // 仅处理 sens == false
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "equals", "(Ljava/lang/Object;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lRet));

        // ListIterator it = list.listIterator();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "listIterator", "()Ljava/util/ListIterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        LabelNode lUnify = new LabelNode();
        LabelNode lUnull = new LabelNode();
        LabelNode lUend = new LabelNode();

        // lLoop: while (it.hasNext())
        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/ListIterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lRet));

        // Object o = it.next();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/ListIterator", "next", "()Ljava/lang/Object;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // if (!(o instanceof ItemStack)) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, ITEM_STACK));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lLoop));

        // ItemStack s = (ItemStack) o;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));

        // if (isVirtualCircuit(s)) { it.remove(); continue; }
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "cn/dancingsnow/neoecoae/crafting/upload/PatternCircuitCompat",
                "isVirtualCircuit", "(L" + ITEM_STACK + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lUnify));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/ListIterator", "remove", "()V", true));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        // lUnify: fix27 把原版物品转成 GT 形态。先 GTOreDictUnificator.get(true,s)（GT 物品有效）；
        // 对原版物品（get 无效）再用 gtify 内部 OreDictionary.getOreID/getOreName + getFirstOre_nocopy 映射。
        // ItemStack u = neoecoae$gtify(s);
        ins.add(lUnify);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$gtify",
                "(L" + ITEM_STACK + ";)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // neoecoae$log("  [U] raw=", s.toString());
        ins.add(new LdcInsnNode("  [U] raw="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // neoecoae$log("  [U] unified=", u != null ? u.toString() : "null");
        ins.add(new LdcInsnNode("  [U] unified="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lUnull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lUend));
        ins.add(lUnull);
        ins.add(new LdcInsnNode("null"));
        ins.add(lUend);
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // if (u != null) it.set(u);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lLoop));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/ListIterator", "set", "(Ljava/lang/Object;)V", true));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        ins.add(lRet);
        ins.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 4;
        mn.maxLocals = 8;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$stripCircuitIfInsensitive");
    }

    /**
     * fix28：private static void neoecoae$dumpExtruder(RecipeMap)
     *
     * 诊断：dump gt.recipe.extruder 前若干配方的 mInputs/mOutputs/mSpecialItems，
     * 一次确认「铁锭→铁杆」配方的输入到底是原版 item.ingotIron 还是 gt.metaitem 铁锭。
     * 限流 dumpN（最多 2 次），只处理 extruder。
     */
    private static void addDumpExtruder(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$dumpExtruder",
                "(L" + RECIPE_MAP + ";)V", null, null);
        InsnList ins = mn.instructions;
        final String GT_RECIPE = "gregtech/api/util/GTRecipe";
        LabelNode lRet = new LabelNode();
        LabelNode lLoop = new LabelNode();

        // if (map == null) return;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));
        // if (!"gt.recipe.extruder".equals(map.unlocalizedName)) return;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, RECIPE_MAP, "unlocalizedName", "Ljava/lang/String;"));
        ins.add(new LdcInsnNode("gt.recipe.extruder"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lRet));
        // if (neoecoae$dumpN >= 2) return;
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$dumpN", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_2));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lRet));
        // neoecoae$dumpN++;
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$dumpN", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$dumpN", "I"));

        // Collection recs = map.getAllRecipes();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getAllRecipes", "()Ljava/util/Collection;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));

        // Iterator it = recs.iterator();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));

        LabelNode lPrint = new LabelNode();
        // lLoop: 遍历所有配方，只 dump 产物含 23032（铁杆）的，看其真实输入形态
        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lRet));

        // Object rObj = it.next();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // GTRecipe r = (GTRecipe) rObj;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, GT_RECIPE));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // ItemStack[] outs = r.mOutputs; if (outs == null) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, GT_RECIPE, "mOutputs", "[L" + ITEM_STACK + ";"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lLoop));

        // String os = Arrays.toString(outs); if (os.indexOf("23032") < 0) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new LdcInsnNode("23032"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false));
        ins.add(new JumpInsnNode(Opcodes.IFGE, lPrint));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        // lPrint: 打印铁杆配方的输入/输出/模具
        ins.add(lPrint);
        ins.add(new LdcInsnNode("  [R] in="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, GT_RECIPE, "mInputs", "[L" + ITEM_STACK + ";"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        ins.add(new LdcInsnNode("  [R] out="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        ins.add(new LdcInsnNode("  [R] spec="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, GT_RECIPE, "mSpecialItems", "Ljava/lang/Object;"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        ins.add(lRet);
        ins.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 4;
        mn.maxLocals = 8;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$dumpExtruder");
    }

    /**
     * fix27：private static ItemStack neoecoae$gtify(ItemStack)
     *
     * 把输入物品转成 GT 标准形态，供 specialSlotSensitive=false 的机器（挤出机等）匹配：
     *  1. GTOreDictUnificator.get(true, s) —— GT 物品（gt.metaitem）有效；
     *  2. 若 get 未转换（原版物品如 item.ingotIron，GTNH 2.x 无 association）：
     *     OreDictionary.getOreID(s) → getOreName(id) → GTOreDictUnificator.getFirstOre_nocopy(name)
     *     取 GT 矿石词典标准物品（item.ingotIron -> gt.metaitem 铁锭）。
     * 都失败则原样返回 s。
     */
    private static void addGtify(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$gtify",
                "(L" + ITEM_STACK + ";)L" + ITEM_STACK + ";", null, null);
        InsnList ins = mn.instructions;
        final String ORE = "net/minecraftforge/oredict/OreDictionary";
        final String GUD = "gregtech/api/util/GTOreDictUnificator";
        LabelNode lRetNull = new LabelNode();
        LabelNode lOre = new LabelNode();
        LabelNode lName = new LabelNode();
        LabelNode lRetS = new LabelNode();
        LabelNode lRetGt = new LabelNode();

        // if (s == null) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRetNull));

        // ItemStack gt = GTOreDictUnificator.get(true, s);
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUD, "get", "(ZL" + ITEM_STACK + ";)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // if (gt != null && gt != s) return gt;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lOre));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IF_ACMPNE, lRetGt));
        // gt == s → fall through to OreDictionary path
        ins.add(lOre);

        // fix31：getOreID 对原版物品常返回 -1（GTNH 2.x 原版物品不注册 Forge 矿石词典），name 分支永不进。
        // 改用 s.toString() 判断（getUnlocalizedName 是 client-only；toString 双端安全）。
        // String ts = s.toString(); if (ts.contains("item.ingotIron")) return get(OrePrefixes.ingot, Materials.Iron, 1);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new LdcInsnNode("item.ingotIron"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lRetS));
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, "gregtech/api/enums/OrePrefixes", "ingot", "Lgregtech/api/enums/OrePrefixes;"));
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, "gregtech/api/enums/Materials", "Iron", "Lgregtech/api/enums/Materials;"));
        ins.add(new InsnNode(Opcodes.LCONST_1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUD, "get", "(Lgregtech/api/enums/OrePrefixes;Ljava/lang/Object;J)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRetS));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new InsnNode(Opcodes.ARETURN));

        ins.add(lRetGt);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new InsnNode(Opcodes.ARETURN));
        ins.add(lRetS);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new InsnNode(Opcodes.ARETURN));
        ins.add(lRetNull);
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 4;
        mn.maxLocals = 5;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$gtify");
    }

    /**
     * fix36：public static ItemStack neoecoae$moldForHost(Object host, RecipeMap, IAEStack[])
     * 供 PatternUploadTarget 在上传列表显示目标模具：临时把 contextHost 设为 host，
     * 调 specialSlotFor2 收集该主机输入总线的模具，用完恢复原 contextHost。
     */
    private static void addMoldForHost(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "neoecoae$moldForHost",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";[L" + IAE_STACK + ";)L" + ITEM_STACK + ";", null, null);
        InsnList ins = mn.instructions;
        // Object old = neoecoae$contextHost;
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$contextHost", "Ljava/lang/Object;"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        // neoecoae$contextHost = host;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$contextHost", "Ljava/lang/Object;"));
        // ItemStack mold = specialSlotFor2(map, new ArrayList(), outputs);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$specialSlotFor2",
                "(L" + RECIPE_MAP + ";Ljava/util/List;[L" + IAE_STACK + ";)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));
        // neoecoae$contextHost = old;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$contextHost", "Ljava/lang/Object;"));
        // return mold;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 4;
        mn.maxLocals = 5;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$moldForHost");
    }

    /**
     * fix33（真正的根因修复）：把模具加入 itemInputs。
     *
     * fix32 dump 证实：铁杆配方 mInputs = [item.ingotIron@1, 杆模@0]——消耗输入就是原版
     * item.ingotIron（无需转 GT 形态），但**模具(数量0)也在 mInputs 里**。原 matches() 只传
     * items=[item.ingotIron]，isRecipeInputEqual 遍历配方 mInputs 时数量0的杆模在传入 items
     * 找不到匹配 → 恒 false → find() 恒 null（specialSlot 对 sens=false 机器被 GT 忽略）。
     *
     * 修复：提前调 specialSlotFor2 取模具，add 进 itemList，使 q.items 含模具；原 specialSlotFor2
     * 调用改为复用(aload 5)，避免 contextHost 被二次消费。
     */
    private static void patchMatchesAddMold(MethodNode mn) {
        final String SS = "neoecoae$specialSlotFor2";
        final String SS_DESC = "(L" + RECIPE_MAP + ";Ljava/util/List;[L" + IAE_STACK + ";)L" + ITEM_STACK + ";";
        LabelNode lSkip = new LabelNode();
        // 1. strip 调用后插入：ItemStack mold = specialSlotFor2(map, itemList, outputs); if (mold != null) itemList.add(mold);
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (MATCHER.equals(m.owner) && "neoecoae$stripCircuitIfInsensitive".equals(m.name)) {
                    InsnList ins = new InsnList();
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 0));   // map
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 3));   // itemList
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 7));   // outputs
                    ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, SS, SS_DESC, false));
                    ins.add(new VarInsnNode(Opcodes.ASTORE, 5));  // mold (var5 原 hasCircuit，223 后空闲)
                    // fix36：记录本次模具到 lastMold（含 null=该目标无模具），供上传列表显示模具名
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
                    ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$lastMold", "L" + ITEM_STACK + ";"));
                    // fix35：仅当收集到模具才加入 items（没模具靠 isRecipeInputEqual 缺模具判 N，杜绝 null 混入）
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
                    ins.add(new JumpInsnNode(Opcodes.IFNULL, lSkip));
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
                    ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
                    ins.add(new InsnNode(Opcodes.POP));
                    ins.add(lSkip);
                    mn.instructions.insert(insn, ins);
                    break;
                }
            }
        }
        // 2. 原 specialSlotFor2 调用（靠后的那个）替换为 aload 5，删除其 3 个参数 aload_0/aload_3/aload_7
        AbstractInsnNode target = null;
        for (AbstractInsnNode insn = mn.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (MATCHER.equals(m.owner) && SS.equals(m.name)) {
                    target = insn;
                    break;
                }
            }
        }
        if (target == null) {
            System.out.println("  patchMatchesAddMold: specialSlotFor2 call not found, skip");
            return;
        }
        AbstractInsnNode p1 = target.getPrevious();
        AbstractInsnNode p2 = p1 == null ? null : p1.getPrevious();
        AbstractInsnNode p3 = p2 == null ? null : p2.getPrevious();
        if (p1 instanceof VarInsnNode && p2 instanceof VarInsnNode && p3 instanceof VarInsnNode) {
            mn.instructions.remove(p1);
            mn.instructions.remove(p2);
            mn.instructions.remove(p3);
        } else {
            System.out.println("  patchMatchesAddMold: WARN param aloads not as expected");
        }
        mn.instructions.set(target, new VarInsnNode(Opcodes.ALOAD, 5));
        System.out.println("  patchMatchesAddMold: mold added to itemList, specialSlotFor2 -> aload 5");
    }

    /**
     * fix14：把 matches() 里对 neoecoae$specialSlotFor(map,list) 的调用改成
     * neoecoae$specialSlotFor2(map, list, outputs)。
     *
     * 原理：原逻辑只返回「第一个」不消耗物品，但一个仓里往往同时放着多种模具
     * （如模头(杆)/模头(丝)/模头(螺丝)…），而 GT 的 filterFindRecipe 要求
     * recipe.mSpecialItems 与传入的 specialSlot 精确相等，所以取第一个几乎必然
     * 对不上号。把产物数组一并传进去后，specialSlotFor2 可以逐个候选做
     * findRecipeQuery + outputsMatch 校验，挑出真正匹配的那个模具。
     *
     * 字节码层面只需两步：
     * 1. 在 invokestatic 前插入 aload 7（outputs 所在局部槽，已从 dump 确证）
     * 2. 把目标方法换成三参的 neoecoae$specialSlotFor2
     */
    private static void patchMatchesSpecialSlot(MethodNode mn) {
        int n = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (MATCHER.equals(m.owner) && "neoecoae$specialSlotFor".equals(m.name)) {
                    mn.instructions.insertBefore(m, new VarInsnNode(Opcodes.ALOAD, 7));
                    m.name = "neoecoae$specialSlotFor2";
                    m.desc = "(L" + RECIPE_MAP + ";Ljava/util/List;[Lappeng/api/storage/data/IAEStack;)L" + ITEM_STACK + ";";
                    n++;
                }
            }
        }
        System.out.println("  patch matches: specialSlotFor rewritten = " + n);
        // fix23diag：在 invokevirtual specialSlot(...) 之前把 mold 复制一份打印 [M] special=
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if ("specialSlot".equals(m.name) && "gregtech/api/recipe/FindRecipeQuery".equals(m.owner)) {
                    InsnList ins = new InsnList();
                    ins.add(new InsnNode(Opcodes.DUP));        // [mold, mold]
                    ins.add(new LdcInsnNode("[M] special="));  // [mold, mold, label]
                    ins.add(new InsnNode(Opcodes.SWAP));       // [mold, label, mold]
                    ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false)); // 消费 [label, mold] -> [mold]
                    mn.instructions.insertBefore(m, ins);
                    break;
                }
            }
        }
    }

    private static final String IAE_STACK = "appeng/api/storage/data/IAEStack";

    /** private static ItemStack neoecoae$specialSlotFor2(RecipeMap, List, IAEStack[]) */
    private static void addSpecialSlotFor2(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$specialSlotFor2",
                "(L" + RECIPE_MAP + ";Ljava/util/List;[L" + IAE_STACK + ";)L" + ITEM_STACK + ";",
                null, null);
        InsnList ins = mn.instructions;

        LabelNode lT1 = new LabelNode();   // else-if IInterfaceViewable
        LabelNode lAfterMte = new LabelNode();  // mte 解析完毕
        LabelNode lAddA = new LabelNode();
        LabelNode lAddB = new LabelNode();
        LabelNode lEmpty = new LabelNode();
        LabelNode lSingle = new LabelNode();
        LabelNode lLoop = new LabelNode();
        LabelNode lLoopEnd = new LabelNode();
        LabelNode lHit = new LabelNode();

        // Object host = neoecoae$contextHost; neoecoae$contextHost = null;
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$contextHost", "Ljava/lang/Object;"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$contextHost", "Ljava/lang/Object;"));

        // if (recipeMap == null) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEmpty));
        // if (list == null) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEmpty));

        // ==== 诊断入口日志（限流） ====
        // neoecoae$log("[S] map=", recipeMap.unlocalizedName)
        ins.add(new LdcInsnNode("[S] map="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, RECIPE_MAP, "unlocalizedName", "Ljava/lang/String;"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        // neoecoae$log("[S] items=", list)
        ins.add(new LdcInsnNode("[S] items="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        // neoecoae$log("[S] outs=", Arrays.toString(outputs))
        ins.add(new LdcInsnNode("[S] outs="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString",
                "([Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // List cands = new ArrayList();
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // IMetaTileEntity mte = null;
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // if (host instanceof IMetaTileEntity) mte = (IMetaTileEntity) host;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_META_TILE_ENTITY));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lT1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_META_TILE_ENTITY));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lAfterMte));

        // else if (host instanceof IInterfaceViewable) { tile -> mte }
        ins.add(lT1);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_INTERFACE_VIEWABLE));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lAfterMte));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_INTERFACE_VIEWABLE));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_INTERFACE_VIEWABLE, "getTileEntity",
                "()L" + TILE_ENTITY + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_GREG_TECH_TILE_ENTITY));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lAfterMte));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_GREG_TECH_TILE_ENTITY));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_GREG_TECH_TILE_ENTITY, "getMetaTileEntity",
                "()L" + I_META_TILE_ENTITY + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // lAfterMte: if (mte != null) { collectSpecialFromHatch(mte); a = firstManualSpecial; b = firstBusSpecial; add }
        ins.add(lAfterMte);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lAddA));

        // fix17：host 自身也用「配方 mSpecialItems」这一正确判据收集候选
        // （原来的 firstManualSpecial/firstBusSpecial 用的是语义不符的 isDisplayableItem，
        //  对 extruder 恒筛不出东西；实测 host 自己的槽里明明放着 32372/32375 却进不了候选）
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$collectSpecialFromHatch",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)V", false));

        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$firstManualSpecial",
                "(L" + RECIPE_MAP + ";Ljava/lang/Object;Ljava/util/List;)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lAddB));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));

        ins.add(lAddB);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$firstBusSpecial",
                "(L" + RECIPE_MAP + ";Ljava/lang/Object;Ljava/util/List;)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lAddA));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));

        // lAddA: if (host != null) collectSpecialFromList(adjacentMachines(host), map, cands)
        ins.add(lAddA);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEmpty));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$adjacentMachines",
                "(Ljava/lang/Object;)Ljava/util/List;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$collectSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)V", false));

        // neoecoae$log("[S] candN=", Integer.valueOf(cands.size()))
        ins.add(new LdcInsnNode("[S] candN="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // neoecoae$log("[S] sens=", getFieldValue(getFieldValue(map.getBackend(), "properties"), "specialSlotSensitive"))
        // 注意：RecipeMapBackend.properties 是 protected（跨包 GETFIELD 会 IllegalAccessError），必须走反射
        ins.add(new LdcInsnNode("[S] sens="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getBackend",
                "()Lgregtech/api/recipe/RecipeMapBackend;", false));
        ins.add(new LdcInsnNode("properties"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new LdcInsnNode("specialSlotSensitive"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // neoecoae$log("[S] idsN=", Integer.valueOf(neoecoae$specialItemIdSet(map).size()))
        ins.add(new LdcInsnNode("[S] idsN="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$specialItemIdSet",
                "(L" + RECIPE_MAP + ";)Ljava/util/Set;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I", true));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // if (cands.isEmpty()) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lEmpty));

        // if (cands.size() == 1) return (ItemStack) cands.get(0);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lSingle));

        // ItemStack[] its = (ItemStack[]) list.toArray(new ItemStack[list.size()]);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        ins.add(new TypeInsnNode(Opcodes.ANEWARRAY, ITEM_STACK));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "toArray",
                "([Ljava/lang/Object;)[Ljava/lang/Object;", true));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, "[L" + ITEM_STACK + ";"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 8));

        // Iterator it = cands.iterator();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 9));

        // while (it.hasNext()) {
        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lLoopEnd));

        // ItemStack c = (ItemStack) it.next();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // if (neoecoae$diagMatch(map, its, c, outputs)) return c;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$diagMatch",
                "(L" + RECIPE_MAP + ";[L" + ITEM_STACK + ";L" + ITEM_STACK + ";[L" + IAE_STACK + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lHit));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new InsnNode(Opcodes.ARETURN));

        // lHit: continue loop
        ins.add(lHit);
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        // lLoopEnd: 反查兜底（fix1）——逐候选问 GT「它是不是某个配方池的不消耗物品」
        //   for (c : cands) { nm = mapNamesFor(c); if (nm.length() > 0) { log; return c; } }
        // 全部不命中才回退到原来的 cands.get(0)。
        ins.add(lLoopEnd);
        LabelNode lNcLoop = new LabelNode();
        LabelNode lNcNext = new LabelNode();
        LabelNode lNcDone = new LabelNode();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                "()Ljava/util/Iterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 9));
        ins.add(lNcLoop);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNcDone));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 10));
        // String nm = neoecoae$mapNamesFor(c);  // 复用 local 7
        ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$mapNamesFor",
                "(L" + ITEM_STACK + ";)Ljava/lang/String;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        // if (nm.length() == 0) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNcNext));
        // log("[S] ncHit=", nm)
        ins.add(new LdcInsnNode("[S] ncHit="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        // return c;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
        ins.add(new InsnNode(Opcodes.ARETURN));
        ins.add(lNcNext);
        ins.add(new JumpInsnNode(Opcodes.GOTO, lNcLoop));

        // lNcDone: 原逻辑 return (ItemStack) cands.get(0);
        ins.add(lNcDone);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        ins.add(new InsnNode(Opcodes.ARETURN));

        // lSingle: return (ItemStack) cands.get(0);
        ins.add(lSingle);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        ins.add(new InsnNode(Opcodes.ARETURN));

        // lEmpty: return null;
        ins.add(lEmpty);
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 6;
        mn.maxLocals = 11;
        cn.methods.add(mn);
    }

    /**
     * 反查路线（fix1）：private static boolean neoecoae$specialItemHit(RecipeMap, ItemId)
     *
     * GT 的 getNonConsumedInputItemIds() 只扫描 GTRecipe.mInputs 里 stackSize==0 的物品，
     * **完全不看 mSpecialItems**。而真正的"模具 / 模头"是放在特种槽里的，存于
     * GTRecipe.mSpecialItems（声明为 Object，实际可能是 ItemStack / ItemStack[] / Object[]）。
     *
     * 本方法遍历该池全部配方，把 mSpecialItems 里的 ItemStack 逐个与 id 比对。
     * 作为 mapNamesFor 的第二判据（mInputs 集合未命中时才会走到）。
     */
    private static void addSpecialItemHit(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$specialItemHit",
                "(L" + RECIPE_MAP + ";L" + GT_UTILITY_ITEM_ID + ";)Z", null, null);
        InsnList c = mn.instructions;

        LabelNode lHead = new LabelNode();
        LabelNode lNext = new LabelNode();
        LabelNode lIsArr = new LabelNode();
        LabelNode lArrHead = new LabelNode();
        LabelNode lArrNext = new LabelNode();
        LabelNode lArrEnd = new LabelNode();
        LabelNode lTrue = new LabelNode();
        LabelNode lFalse = new LabelNode();

        // if (map == null || id == null) return false;
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new JumpInsnNode(Opcodes.IFNULL, lFalse));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new JumpInsnNode(Opcodes.IFNULL, lFalse));

        // Iterator it = map.getAllRecipes().iterator();  // local 2
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getAllRecipes",
                "()Ljava/util/Collection;", false));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;", true));
        c.add(new VarInsnNode(Opcodes.ASTORE, 2));

        c.add(lHead);
        c.add(new VarInsnNode(Opcodes.ALOAD, 2));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        c.add(new JumpInsnNode(Opcodes.IFEQ, lFalse));

        // GTRecipe r = (GTRecipe) it.next();  // local 3
        c.add(new VarInsnNode(Opcodes.ALOAD, 2));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, GT_RECIPE));
        c.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // Object sp = r.mSpecialItems;  // local 4
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, GT_RECIPE, "mSpecialItems", "Ljava/lang/Object;"));
        c.add(new VarInsnNode(Opcodes.ASTORE, 4));
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new JumpInsnNode(Opcodes.IFNULL, lNext));

        // if (sp instanceof ItemStack) { if (id.equals(ItemId.create((ItemStack) sp))) return true; goto lNext; }
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new TypeInsnNode(Opcodes.INSTANCEOF, ITEM_STACK));
        c.add(new JumpInsnNode(Opcodes.IFEQ, lIsArr));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY_ITEM_ID, "create",
                "(L" + ITEM_STACK + ";)L" + GT_UTILITY_ITEM_ID + ";", false));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GT_UTILITY_ITEM_ID, "equals",
                "(Ljava/lang/Object;)Z", false));
        c.add(new JumpInsnNode(Opcodes.IFNE, lTrue));
        c.add(new JumpInsnNode(Opcodes.GOTO, lNext));

        // else if (sp instanceof Object[]) { Object[] arr = (Object[]) sp;  // local 5
        c.add(lIsArr);
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new TypeInsnNode(Opcodes.INSTANCEOF, "[Ljava/lang/Object;"));
        c.add(new JumpInsnNode(Opcodes.IFEQ, lNext));
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
        c.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // int i = 0;  // local 6
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new VarInsnNode(Opcodes.ISTORE, 6));

        c.add(lArrHead);
        c.add(new VarInsnNode(Opcodes.ILOAD, 6));
        c.add(new VarInsnNode(Opcodes.ALOAD, 5));
        c.add(new InsnNode(Opcodes.ARRAYLENGTH));
        c.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lArrEnd));

        // Object o = arr[i];  // local 7
        c.add(new VarInsnNode(Opcodes.ALOAD, 5));
        c.add(new VarInsnNode(Opcodes.ILOAD, 6));
        c.add(new InsnNode(Opcodes.AALOAD));
        c.add(new VarInsnNode(Opcodes.ASTORE, 7));
        c.add(new VarInsnNode(Opcodes.ALOAD, 7));
        c.add(new JumpInsnNode(Opcodes.IFNULL, lArrNext));
        c.add(new VarInsnNode(Opcodes.ALOAD, 7));
        c.add(new TypeInsnNode(Opcodes.INSTANCEOF, ITEM_STACK));
        c.add(new JumpInsnNode(Opcodes.IFEQ, lArrNext));
        // if (id.equals(ItemId.create((ItemStack) o))) return true;
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new VarInsnNode(Opcodes.ALOAD, 7));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY_ITEM_ID, "create",
                "(L" + ITEM_STACK + ";)L" + GT_UTILITY_ITEM_ID + ";", false));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GT_UTILITY_ITEM_ID, "equals",
                "(Ljava/lang/Object;)Z", false));
        c.add(new JumpInsnNode(Opcodes.IFNE, lTrue));

        c.add(lArrNext);
        c.add(new IincInsnNode(6, 1));
        c.add(new JumpInsnNode(Opcodes.GOTO, lArrHead));

        c.add(lArrEnd);
        c.add(lNext);
        c.add(new JumpInsnNode(Opcodes.GOTO, lHead));

        c.add(lTrue);
        c.add(new InsnNode(Opcodes.ICONST_1));
        c.add(new InsnNode(Opcodes.IRETURN));
        c.add(lFalse);
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));

        mn.maxStack = 4;
        mn.maxLocals = 8;
        cn.methods.add(mn);
    }

    /**
     * 反查路线（fix1）：private static String neoecoae$mapNamesFor(ItemStack)
     *
     * 不再"猜"某个物品是不是模具，而是直接问 GT 的配方池：
     *   RecipeMap.ALL_RECIPE_MAPS -> 每个 map.getNonConsumedInputItemIds()
     * 后者内部遍历该池全部配方、收集 mInputs 中 stackSize==0 的物品 ItemId，
     * 正对应「模具放在 mInputs 且数量 0」这一表示法（挤出机等即是如此）。
     *
     * 返回命中池的 unlocalizedName（逗号分隔，可据此推断机器类型）；无命中返回 ""。
     * 结果缓存在 neoecoae$ncCache（ItemId -> String），只付一次遍历代价。
     */
    private static void addMapNamesFor(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$mapNamesFor",
                "(L" + ITEM_STACK + ";)Ljava/lang/String;", null, null);
        InsnList c = mn.instructions;

        LabelNode lHave = new LabelNode();
        LabelNode lCached = new LabelNode();
        LabelNode lBuild = new LabelNode();
        LabelNode lHead = new LabelNode();
        LabelNode lSkip = new LabelNode();
        LabelNode lDoAppend = new LabelNode();
        LabelNode lAppend = new LabelNode();
        LabelNode lEnd = new LabelNode();

        // if (stack == null) return "";
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, lHave));
        c.add(new LdcInsnNode(""));
        c.add(new InsnNode(Opcodes.ARETURN));

        // ItemId id = ItemId.create(stack);  // local 1
        c.add(lHave);
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY_ITEM_ID, "create",
                "(L" + ITEM_STACK + ";)L" + GT_UTILITY_ITEM_ID + ";", false));
        c.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // if (neoecoae$ncCache == null) neoecoae$ncCache = new HashMap();
        c.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$ncCache", "Ljava/util/HashMap;"));
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, lCached));
        c.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        c.add(new InsnNode(Opcodes.DUP));
        c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        c.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$ncCache", "Ljava/util/HashMap;"));

        // Object hit = cache.get(id); if (hit != null) return (String) hit;
        c.add(lCached);
        c.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$ncCache", "Ljava/util/HashMap;"));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        c.add(new VarInsnNode(Opcodes.ASTORE, 2));
        c.add(new VarInsnNode(Opcodes.ALOAD, 2));
        c.add(new JumpInsnNode(Opcodes.IFNULL, lBuild));
        c.add(new VarInsnNode(Opcodes.ALOAD, 2));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        c.add(new InsnNode(Opcodes.ARETURN));

        // StringBuilder sb = new StringBuilder();  // local 3
        c.add(lBuild);
        c.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        c.add(new InsnNode(Opcodes.DUP));
        c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        c.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // Iterator it = RecipeMap.ALL_RECIPE_MAPS.values().iterator();  // local 4
        c.add(new FieldInsnNode(Opcodes.GETSTATIC, RECIPE_MAP, "ALL_RECIPE_MAPS", "Ljava/util/Map;"));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "values",
                "()Ljava/util/Collection;", true));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;", true));
        c.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // while (it.hasNext()) { RecipeMap m = (RecipeMap) it.next();  // local 5
        c.add(lHead);
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        c.add(new JumpInsnNode(Opcodes.IFEQ, lEnd));
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, RECIPE_MAP));
        c.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // if (m.getNonConsumedInputItemIds().contains(id)) goto lDoAppend;   // 判据 1：mInputs stackSize==0
        c.add(new VarInsnNode(Opcodes.ALOAD, 5));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getNonConsumedInputItemIds",
                "()Ljava/util/Set;", false));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                "(Ljava/lang/Object;)Z", true));
        c.add(new JumpInsnNode(Opcodes.IFNE, lDoAppend));
        // else if (!neoecoae$specialItemHit(map, id)) continue;   // 判据 2：mSpecialItems（模具/模头特种槽）
        c.add(new VarInsnNode(Opcodes.ALOAD, 5));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$specialItemHit",
                "(L" + RECIPE_MAP + ";L" + GT_UTILITY_ITEM_ID + ";)Z", false));
        c.add(new JumpInsnNode(Opcodes.IFEQ, lSkip));

        // lDoAppend: if (sb.length() > 0) sb.append(',');
        c.add(lDoAppend);
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "length", "()I", false));
        c.add(new JumpInsnNode(Opcodes.IFEQ, lAppend));
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new LdcInsnNode(","));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        c.add(new InsnNode(Opcodes.POP));

        // sb.append(m.unlocalizedName);
        c.add(lAppend);
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new VarInsnNode(Opcodes.ALOAD, 5));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, RECIPE_MAP, "unlocalizedName", "Ljava/lang/String;"));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        c.add(new InsnNode(Opcodes.POP));

        c.add(lSkip);
        c.add(new JumpInsnNode(Opcodes.GOTO, lHead));

        // String r = sb.toString(); cache.put(id, r); return r;
        c.add(lEnd);
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        c.add(new VarInsnNode(Opcodes.ASTORE, 6));
        c.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$ncCache", "Ljava/util/HashMap;"));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new VarInsnNode(Opcodes.ALOAD, 6));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        c.add(new InsnNode(Opcodes.POP));
        c.add(new VarInsnNode(Opcodes.ALOAD, 6));
        c.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 4;
        mn.maxLocals = 7;
        cn.methods.add(mn);
    }

    /** private static void neoecoae$collectSpecialFromHatch(Object hatch, RecipeMap map, List cands) */
    private static void addCollectSpecialFromHatch(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$collectSpecialFromHatch",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)V", null, null);
        InsnList ins = mn.instructions;
        LabelNode lSkip = new LabelNode();
        LabelNode lLoop = new LabelNode();
        LabelNode lEnd = new LabelNode();
        LabelNode lNext = new LabelNode();

        // if (!(hatch instanceof MTEHatch)) return;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, MTE_HATCH));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lSkip));

        // MTEHatch h = (MTEHatch) hatch;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, MTE_HATCH));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // log("[H] cls=", hatch.getClass())
        ins.add(new LdcInsnNode("[H] cls="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // ===== fix13: dual input hatch path =====
        // `SuperCraftingInputHatchME` 等 IDualInputHatch 的 IInventory 方法暴露的是样板槽，
        // 实际物品在 sharedItems（circuit + manual slots）。走 getSharedItems() 读取。
        LabelNode lNotDual = new LabelNode();
        LabelNode lDualLoop = new LabelNode();
        LabelNode lDualNext = new LabelNode();
        LabelNode lDualEnd = new LabelNode();

        // if (!(hatch instanceof IDualInputHatch)) goto lNotDual;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, IDUAL_INPUT_HATCH));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNotDual));

        // ItemStack[] shared = ((IDualInputHatch)hatch).getSharedItems();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, IDUAL_INPUT_HATCH));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, IDUAL_INPUT_HATCH, "getSharedItems",
                "()[L" + ITEM_STACK + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // int j = 0;
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 5));
        // fix48: int added = 0;（统计 dual 路径收集到的物品数，0 则回退走原版输入总线扫描）
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 8));

        ins.add(lDualLoop);
        // if (j >= shared.length) goto lDualEnd;
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new InsnNode(Opcodes.ARRAYLENGTH));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lDualEnd));

        // ItemStack s = shared[j];
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new InsnNode(Opcodes.AALOAD));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lDualNext));

        // if (cands.size() >= 8) goto lDualEnd;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        ins.add(new LdcInsnNode(8));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lDualEnd));

        // ItemStack cp = s.func_77946_l(); cp.field_77994_a = 1; cands.add(cp);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "func_77946_l", "()L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new FieldInsnNode(Opcodes.PUTFIELD, ITEM_STACK, "field_77994_a", "I"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));
        ins.add(new IincInsnNode(8, 1));

        // log("[H] shared=", cp)
        ins.add(new LdcInsnNode("[H] shared="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        ins.add(lDualNext);
        ins.add(new IincInsnNode(5, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lDualLoop));

        // fix48: dual 路径若一个物品都没收集到（getSharedItems 对本模组总成恒空），
        // 回退走原版输入总线扫描（h.func_70301_a(i)），让输入总成/巨型输入总成里的模头被识别。
        // fix2：**不再"仅当 shared 为空才回退"**。
        // 原因：DualInputHatch（输入总成）/ HumongousDualInputHatch（巨型输入总成）的
        // getSharedItems() 可能只返回电路/手动槽，而不消耗物品槽并不在其中；
        // 此时 added != 0，旧逻辑就不会再扫 getRealInventory() → 里面的模具永远读不到。
        // 改为**无条件**继续扫 getRealInventory()，两个来源都收集（containsItemId 会去重，
        // 样板由 toString 含 "Encoded"/"attern" 过滤，且后续反查也不会把样板误判为模具）。
        LabelNode lDualDone = new LabelNode();
        ins.add(lDualEnd);
        // log("[H] dualFb=", hatch.getClass())
        ins.add(new LdcInsnNode("[H] dualFb="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        // fix49: 回退不能走 func_70301_a（dual 仓的 IInventory 视图是样板槽，读到的全是样板），
        // 改扫 getRealInventory()（含不消耗物品槽），跳过样板物品。
        LabelNode lInvLoop = new LabelNode();
        LabelNode lInvNext = new LabelNode();
        LabelNode lInvEnd = new LabelNode();
        LabelNode lChk2 = new LabelNode();
        LabelNode lChk3 = new LabelNode();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_META_TILE_ENTITY));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lDualDone));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_META_TILE_ENTITY));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_META_TILE_ENTITY, "getRealInventory",
                "()[L" + ITEM_STACK + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 9));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lDualDone));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 10));
        ins.add(lInvLoop);
        ins.add(new VarInsnNode(Opcodes.ILOAD, 10));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new InsnNode(Opcodes.ARRAYLENGTH));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lInvEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        ins.add(new LdcInsnNode(8));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lInvEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 10));
        ins.add(new InsnNode(Opcodes.AALOAD));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 11));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 11));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lInvNext));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 11));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, ITEM_STACK, "field_77994_a", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPLE, lInvNext));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 11));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 12));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 12));
        ins.add(new LdcInsnNode("Encoded"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                "(Ljava/lang/CharSequence;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lChk2));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lInvNext));
        ins.add(lChk2);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 12));
        ins.add(new LdcInsnNode("attern"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                "(Ljava/lang/CharSequence;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lChk3));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lInvNext));
        ins.add(lChk3);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 11));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "func_77946_l", "()L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 12));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 12));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new FieldInsnNode(Opcodes.PUTFIELD, ITEM_STACK, "field_77994_a", "I"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 12));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));
        ins.add(new LdcInsnNode("[H] fbInv="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 12));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        ins.add(lInvNext);
        ins.add(new IincInsnNode(10, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lInvLoop));
        ins.add(lInvEnd);
        ins.add(lDualDone);
        ins.add(new InsnNode(Opcodes.RETURN));

        ins.add(lNotDual);

        // int size = h.func_70302_i_();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH, "func_70302_i_", "()I", false));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 4));

        // int circ = h.getCircuitSlot();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH, "getCircuitSlot", "()I", false));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 5));

        // fix18diag：不再用 mSpecialItems 集合过滤（实测该集合为空，一个候选都收集不到），
        // 改为收集仓里所有非空物品（上限 8），交给 findRecipeQuery().find() 内部去判定。
        // int i = 0;
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 6));

        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lEnd));

        // if (i == circ) continue;
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lNext));

        // ItemStack s = h.func_70301_a(i);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH, "func_70301_a",
                "(I)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNext));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, ITEM_STACK, "field_77994_a", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPLE, lNext));

        // if (cands.size() >= 8) break;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        ins.add(new LdcInsnNode(8));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lEnd));

        // if (containsItemId(cands, ItemId.create(s))) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY_ITEM_ID, "create",
                "(L" + ITEM_STACK + ";)L" + GT_UTILITY_ITEM_ID + ";", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$containsItemId",
                "(Ljava/util/List;L" + GT_UTILITY_ITEM_ID + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lNext));

        // ItemStack cp = s.func_77946_l(); cp.field_77994_a = 1; cands.add(cp);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "func_77946_l", "()L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 8));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new FieldInsnNode(Opcodes.PUTFIELD, ITEM_STACK, "field_77994_a", "I"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));

        // log("[H] got=", cp)
        ins.add(new LdcInsnNode("[H] got="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        ins.add(lNext);
        ins.add(new IincInsnNode(6, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        ins.add(lEnd);
        ins.add(lSkip);
        ins.add(new InsnNode(Opcodes.RETURN));

        mn.maxStack = 5;
        mn.maxLocals = 13;
        cn.methods.add(mn);
    }

    /** private static void neoecoae$collectSpecialFromList(List machines, RecipeMap map, List cands) */
    private static void addCollectSpecialFromList(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$collectSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)V", null, null);
        InsnList ins = mn.instructions;
        LabelNode lEnd = new LabelNode();
        LabelNode lLoop = new LabelNode();
        LabelNode lNext = new LabelNode();

        // if (machines == null) return;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEnd));

        // Iterator it = machines.iterator();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));

        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lEnd));

        // Object m = it.next();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // if (m instanceof MTEHatch) collectSpecialFromHatch(m, map, cands);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, MTE_HATCH));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNext));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$collectSpecialFromHatch",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)V", false));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        // lNext: MultiBlockBase 分支入口
        ins.add(lNext);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, MTE_MULTI_BLOCK_BASE));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lLoop));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new LdcInsnNode("mInputBusses"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$collectSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)V", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new LdcInsnNode("mDualInputHatches"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$collectSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)V", false));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        ins.add(lEnd);
        ins.add(new InsnNode(Opcodes.RETURN));

        mn.maxStack = 4;
        mn.maxLocals = 5;
        cn.methods.add(mn);
    }

    /**
     * 把原版方法里所有 MTEHatchInputBus 引用放宽到公共父类 MTEHatch，
     * 使「输入仓」(MTEHatchInput) 与「输入总线」(MTEHatchInputBus) 都被覆盖。
     *
     * 涉及三种指令形态：
     * - TypeInsnNode(INSTANCEOF / CHECKCAST, MTEHatchInputBus)
     * - MethodInsnNode(INVOKEVIRTUAL, MTEHatchInputBus, name, desc)
     * - FrameNode 局部变量表里的类型名字符串
     */
    private static void patchWidenBusRefs(MethodNode mn) {
        int nType = 0, nMethod = 0, nFrame = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof TypeInsnNode) {
                TypeInsnNode t = (TypeInsnNode) insn;
                if (MTE_HATCH_INPUT_BUS.equals(t.desc)) { t.desc = MTE_HATCH; nType++; }
            } else if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (MTE_HATCH_INPUT_BUS.equals(m.owner)) { m.owner = MTE_HATCH; nMethod++; }
            } else if (insn instanceof FrameNode) {
                FrameNode f = (FrameNode) insn;
                if (f.local != null) {
                    for (int i = 0; i < f.local.size(); i++) {
                        if (MTE_HATCH_INPUT_BUS.equals(f.local.get(i))) {
                            f.local.set(i, MTE_HATCH); nFrame++;
                        }
                    }
                }
                if (f.stack != null) {
                    for (int i = 0; i < f.stack.size(); i++) {
                        if (MTE_HATCH_INPUT_BUS.equals(f.stack.get(i))) {
                            f.stack.set(i, MTE_HATCH); nFrame++;
                        }
                    }
                }
            }
        }
        System.out.println("  widen " + mn.name + ": type=" + nType + " method=" + nMethod + " frame=" + nFrame);
    }

    /**
     * private static void neoecoae$appendFromHatch(Object hatch, RecipeMap map, List list)
     *
     * 把单个 MTEHatchInputBus 里的不消耗物品（数量置 1）加入 list（去重）。
     */
    private static void addAppendFromHatch(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$appendFromHatch",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)V",
                null, null);
        InsnList ins = mn.instructions;

        LabelNode skip = new LabelNode();
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode nextIter = new LabelNode();

        // if (!(hatch instanceof MTEHatchInputBus)) return;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, MTE_HATCH));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));

        // MTEHatchInputBus bus = (MTEHatchInputBus) hatch;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, MTE_HATCH));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // int size = bus.func_70302_i_();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH, "func_70302_i_", "()I", false));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 4));

        // int circuitSlot = bus.getCircuitSlot();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH, "getCircuitSlot", "()I", false));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 5));

        // int i = 0;
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 6));

        // loop start
        ins.add(loopStart);
        ins.add(new FrameNode(Opcodes.F_FULL, 7,
                new Object[]{"java/lang/Object", RECIPE_MAP, LIST, MTE_HATCH, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                0, null));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // if (i == circuitSlot) continue;
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, nextIter));

        // ItemStack stack = bus.func_70301_a(i);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH,
                "func_70301_a", "(I)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // if (stack == null) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, nextIter));

        // if (stack.field_77994_a <= 0) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, ITEM_STACK, "field_77994_a", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPLE, nextIter));

        // if (!INonConsumedItemDisplay.isDisplayableItem(map, stack)) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INON_CONSUMED,
                "isDisplayableItem", "(L" + RECIPE_MAP + ";L" + ITEM_STACK + ";)Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));

        // GTUtility.ItemId itemId = GTUtility.ItemId.create(stack);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY_ITEM_ID,
                "create", "(L" + ITEM_STACK + ";)L" + GT_UTILITY_ITEM_ID + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 8));

        // if (containsItemId(list, itemId)) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER,
                "neoecoae$containsItemId", "(Ljava/util/List;L" + GT_UTILITY_ITEM_ID + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, nextIter));

        // ItemStack copy = stack.func_77946_l();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "func_77946_l", "()L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 9));

        // copy.field_77994_a = 1;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new FieldInsnNode(Opcodes.PUTFIELD, ITEM_STACK, "field_77994_a", "I"));

        // list.add(copy);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 9));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, LIST, "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));

        // next iteration
        ins.add(nextIter);
        ins.add(new FrameNode(Opcodes.F_FULL, 7,
                new Object[]{"java/lang/Object", RECIPE_MAP, LIST, MTE_HATCH, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                0, null));
        ins.add(new IincInsnNode(6, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        // loop end
        ins.add(loopEnd);
        ins.add(new FrameNode(Opcodes.F_FULL, 7,
                new Object[]{"java/lang/Object", RECIPE_MAP, LIST, MTE_HATCH, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                0, null));

        // skip / return
        ins.add(skip);
        ins.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{"java/lang/Object", RECIPE_MAP, LIST}, 0, null));
        ins.add(new InsnNode(Opcodes.RETURN));

        mn.maxStack = 5;
        mn.maxLocals = 10;
        cn.methods.add(mn);
    }

    /**
     * private static ItemStack neoecoae$firstSpecialFromHatch(Object hatch, RecipeMap map, List list)
     *
     * 从单个 MTEHatchInputBus 里取第一个不消耗物品（去重）。不是 InputBus 或没有则返回 null。
     */
    private static void addFirstSpecialFromHatch(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$firstSpecialFromHatch",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";",
                null, null);
        InsnList ins = mn.instructions;

        LabelNode skip = new LabelNode();
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode nextIter = new LabelNode();

        // if (!(hatch instanceof MTEHatchInputBus)) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, MTE_HATCH));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));

        // MTEHatchInputBus bus = (MTEHatchInputBus) hatch;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, MTE_HATCH));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // int size = bus.func_70302_i_();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH, "func_70302_i_", "()I", false));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 4));

        // int circuitSlot = bus.getCircuitSlot();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH, "getCircuitSlot", "()I", false));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 5));

        // int i = 0;
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 6));

        // loop start
        ins.add(loopStart);
        ins.add(new FrameNode(Opcodes.F_FULL, 7,
                new Object[]{"java/lang/Object", RECIPE_MAP, LIST, MTE_HATCH, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                0, null));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // if (i == circuitSlot) continue;
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, nextIter));

        // ItemStack stack = bus.func_70301_a(i);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MTE_HATCH,
                "func_70301_a", "(I)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // if (stack == null) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, nextIter));

        // if (stack.field_77994_a <= 0) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, ITEM_STACK, "field_77994_a", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPLE, nextIter));

        // if (!INonConsumedItemDisplay.isDisplayableItem(map, stack)) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INON_CONSUMED,
                "isDisplayableItem", "(L" + RECIPE_MAP + ";L" + ITEM_STACK + ";)Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));

        // GTUtility.ItemId itemId = GTUtility.ItemId.create(stack);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY_ITEM_ID,
                "create", "(L" + ITEM_STACK + ";)L" + GT_UTILITY_ITEM_ID + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 8));

        // if (containsItemId(list, itemId)) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER,
                "neoecoae$containsItemId", "(Ljava/util/List;L" + GT_UTILITY_ITEM_ID + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, nextIter));

        // return stack.func_77946_l();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "func_77946_l", "()L" + ITEM_STACK + ";", false));
        ins.add(new InsnNode(Opcodes.ARETURN));

        // next iteration
        ins.add(nextIter);
        ins.add(new FrameNode(Opcodes.F_FULL, 7,
                new Object[]{"java/lang/Object", RECIPE_MAP, LIST, MTE_HATCH, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                0, null));
        ins.add(new IincInsnNode(6, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        // loop end
        ins.add(loopEnd);
        ins.add(new FrameNode(Opcodes.F_FULL, 7,
                new Object[]{"java/lang/Object", RECIPE_MAP, LIST, MTE_HATCH, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                0, null));

        // skip / return null
        ins.add(skip);
        ins.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{"java/lang/Object", RECIPE_MAP, LIST}, 0, null));
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 5;
        mn.maxLocals = 10;
        cn.methods.add(mn);
    }

    /**
     * private static Object neoecoae$getFieldValue(Object instance, String name)
     *
     * 反射查找并读取对象的字段（包含父类），失败返回 null。
     */
    private static void addGetFieldValue(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                null, null);
        InsnList ins = mn.instructions;

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode tryBlock = new LabelNode();
        LabelNode catchLabel = new LabelNode();

        // Class<?> clazz = instance.getClass();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // while (clazz != null)
        ins.add(loopStart);
        ins.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/Class"}, 0, null));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, loopEnd));

        ins.add(tryBlock);
        ins.add(new FrameNode(Opcodes.F_FULL, 3,
                new Object[]{"java/lang/Object", "java/lang/String", "java/lang/Class"}, 0, null));
        // Field f = clazz.getDeclaredField(name);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        // f.setAccessible(true);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false));
        // return f.get(instance);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        ins.add(new InsnNode(Opcodes.ARETURN));

        // catch (Throwable) -> clazz = clazz.getSuperclass(); continue
        ins.add(catchLabel);
        ins.add(new FrameNode(Opcodes.F_FULL, 3,
                new Object[]{"java/lang/Object", "java/lang/String", "java/lang/Class"}, 1,
                new Object[]{"java/lang/Throwable"}));
        ins.add(new InsnNode(Opcodes.POP));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getSuperclass", "()Ljava/lang/Class;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));
        ins.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        // end while
        ins.add(loopEnd);
        ins.add(new FrameNode(Opcodes.F_CHOP, 1, null, 0, null));
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));

        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryBlock, catchLabel, catchLabel, "java/lang/Throwable"));

        mn.maxStack = 4;
        mn.maxLocals = 4;
        cn.methods.add(mn);
    }

    /**
     * private static List<Object> neoecoae$adjacentMachines(Object host)
     *
     * fix12 重写：不再要求 host 是 IInterfaceHost（neoecoae 的 TileECOInterface
     * 只实现 IGridProxyable/IActionHost/IPriorityHost/ICraftingProvider，
     * instanceof IInterfaceHost 恒 false，导致 fix9~fix11 的相邻扫描从未生效），
     * 也不再依赖 getTargets()（AE2 接口默认可能未配置目标方向）。
     *
     * 新逻辑：按 TileEntity -> IInterfaceHost -> IInterfaceViewable ->
     * IPatternUploadProvider 四路取出 host 的 TileEntity，然后扫描其周围
     * 全部 6 个方向的 GT 机器，返回非空 IMetaTileEntity 列表。
     *
     * 不手写 FrameNode：ClassWriter(COMPUTE_FRAMES) 会自动重算全部栈帧。
     */
    private static void addAdjacentMachines(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$adjacentMachines",
                "(Ljava/lang/Object;)Ljava/util/List;",
                "(Ljava/lang/Object;)Ljava/util/List<Ljava/lang/Object;>;",
                null);
        InsnList ins = mn.instructions;

        LabelNode lTryIh = new LabelNode();
        LabelNode lTryIv = new LabelNode();
        LabelNode lTryPp = new LabelNode();
        LabelNode lHaveTile = new LabelNode();
        LabelNode lRet = new LabelNode();
        LabelNode lLoop = new LabelNode();
        LabelNode lNext = new LabelNode();

        // List<Object> result = new ArrayList<>();
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // TileEntity tile = null;
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // if (host instanceof TileEntity) { tile = (TileEntity) host; }
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, TILE_ENTITY));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lTryIh));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, TILE_ENTITY));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lHaveTile));

        // else if (host instanceof IInterfaceHost) { tile = ((IInterfaceHost) host).getTileEntity(); }
        ins.add(lTryIh);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_INTERFACE_HOST));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lTryIv));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_INTERFACE_HOST));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_INTERFACE_HOST, "getTileEntity",
                "()L" + TILE_ENTITY + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lHaveTile));

        // else if (host instanceof IInterfaceViewable) { tile = ((IInterfaceViewable) host).getTileEntity(); }
        ins.add(lTryIv);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_INTERFACE_VIEWABLE));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lTryPp));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_INTERFACE_VIEWABLE));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_INTERFACE_VIEWABLE, "getTileEntity",
                "()L" + TILE_ENTITY + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lHaveTile));

        // else if (host instanceof IPatternUploadProvider) { tile = ((IPatternUploadProvider) host).getTileEntity(); }
        ins.add(lTryPp);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, IPatternUploadProvider));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lHaveTile));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, IPatternUploadProvider));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, IPatternUploadProvider, "getTileEntity",
                "()L" + TILE_ENTITY + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // lHaveTile: if (tile == null) return result;
        ins.add(lHaveTile);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));

        // World world = tile.func_145831_w();  if (world == null) return result;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TILE_ENTITY, "func_145831_w", "()L" + WORLD + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lRet));

        // ForgeDirection[] dirs = ForgeDirection.values();
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE_DIRECTION, "values",
                "()[L" + FORGE_DIRECTION + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // int i = 0;
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 5));

        // lLoop: if (i >= dirs.length) return result;
        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new InsnNode(Opcodes.ARRAYLENGTH));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lRet));

        // ForgeDirection dir = dirs[i];
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new InsnNode(Opcodes.AALOAD));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));

        // if (dir == null) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNext));
        // if (dir == ForgeDirection.UNKNOWN) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, FORGE_DIRECTION, "UNKNOWN", "L" + FORGE_DIRECTION + ";"));
        ins.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, lNext));

        // TileEntity adj = world.func_147438_o(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TILE_ENTITY, "field_145851_c", "I"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, FORGE_DIRECTION, "offsetX", "I"));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TILE_ENTITY, "field_145848_d", "I"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, FORGE_DIRECTION, "offsetY", "I"));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TILE_ENTITY, "field_145849_e", "I"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, FORGE_DIRECTION, "offsetZ", "I"));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD, "func_147438_o",
                "(III)L" + TILE_ENTITY + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // if (!(adj instanceof IGregTechTileEntity)) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_GREG_TECH_TILE_ENTITY));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNext));

        // IMetaTileEntity machine = ((IGregTechTileEntity) adj).getMetaTileEntity();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_GREG_TECH_TILE_ENTITY));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_GREG_TECH_TILE_ENTITY, "getMetaTileEntity",
                "()L" + I_META_TILE_ENTITY + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 8));

        // if (machine != null) result.add(machine);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNext));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));

        // lNext: i++; goto lLoop;
        ins.add(lNext);
        ins.add(new IincInsnNode(5, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        // lRet: return result;
        ins.add(lRet);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 6;
        mn.maxLocals = 9;
        cn.methods.add(mn);
    }

    /**
     * private static ItemStack neoecoae$firstSpecialFromList(List inList, RecipeMap map, List outList)
     *
     * 遍历列表（可能含输入总线或主机），返回第一个不消耗物品；遇到主机则递归其 mInputBusses/mDualInputHatches。
     */
    private static void addFirstSpecialFromList(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$firstSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";",
                null, null);
        InsnList ins = mn.instructions;

        LabelNode retNull = new LabelNode();
        LabelNode retNull2 = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode lmb = new LabelNode();
        LabelNode lmb2 = new LabelNode();
        LabelNode linc = new LabelNode();

        // if (inList == null || !(inList instanceof List)) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, retNull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, LIST));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, retNull));

        // Iterator it = inList.iterator();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, LIST, "iterator", "()Ljava/util/Iterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // loop:
        ins.add(loop);
        ins.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/util/Iterator"}, 0, null));
        // while (it.hasNext())
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, retNull2));
        // Object m = it.next();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));
        // if (m instanceof MTEHatchInputBus) { s = firstSpecialFromHatch(...); if (s!=null) return s; }
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, MTE_HATCH));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lmb));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$firstSpecialFromHatch",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, linc));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new InsnNode(Opcodes.ARETURN));
        // lmb:
        ins.add(lmb);
        ins.add(new FrameNode(Opcodes.F_FULL, 5,
                new Object[]{LIST, RECIPE_MAP, LIST, "java/util/Iterator", "java/lang/Object"}, 0, null));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, MTE_MULTI_BLOCK_BASE));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, linc));
        // s = firstSpecialFromList(getFieldValue(m,"mInputBusses"), map, outList);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new LdcInsnNode("mInputBusses"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$firstSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lmb2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new InsnNode(Opcodes.ARETURN));
        // lmb2:
        ins.add(lmb2);
        ins.add(new FrameNode(Opcodes.F_FULL, 6,
                new Object[]{LIST, RECIPE_MAP, LIST, "java/util/Iterator", "java/lang/Object", ITEM_STACK}, 0, null));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new LdcInsnNode("mDualInputHatches"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$firstSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, linc));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new InsnNode(Opcodes.ARETURN));
        // linc:
        ins.add(linc);
        ins.add(new FrameNode(Opcodes.F_FULL, 5,
                new Object[]{LIST, RECIPE_MAP, LIST, "java/util/Iterator", "java/lang/Object"}, 0, null));
        ins.add(new JumpInsnNode(Opcodes.GOTO, loop));
        // retNull2:
        ins.add(retNull2);
        ins.add(new FrameNode(Opcodes.F_FULL, 4,
                new Object[]{LIST, RECIPE_MAP, LIST, "java/util/Iterator"}, 0, null));
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));
        // retNull:
        ins.add(retNull);
        ins.add(new FrameNode(Opcodes.F_FULL, 3,
                new Object[]{LIST, RECIPE_MAP, LIST}, 0, null));
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 4;
        mn.maxLocals = 6;
        cn.methods.add(mn);
    }

    /**
     * private static ItemStack neoecoae$firstSpecialFromAdjacentHost(Object host, RecipeMap map, List outList)
     *
     * 当 host 是 ME 接口（IInterfaceHost）时，返回相邻 GT 机器里的第一个不消耗物品。
     */
    private static void addFirstSpecialFromAdjacentHost(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$firstSpecialFromAdjacentHost",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";",
                null, null);
        InsnList ins = mn.instructions;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$adjacentMachines",
                "(Ljava/lang/Object;)Ljava/util/List;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$firstSpecialFromList",
                "(Ljava/util/List;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";", false));
        ins.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 3;
        mn.maxLocals = 3;
        cn.methods.add(mn);
    }

    private static void patchAppendNotConsumedContext(MethodNode mn) {
        InsnList ins = new InsnList();
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$contextHost", "Ljava/lang/Object;"));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$savedHost", "Ljava/lang/Object;"));
        mn.instructions.insert(ins);
    }

    private static void patchSpecialSlotFor(MethodNode mn) {
        AbstractInsnNode lastNull = null;
        for (AbstractInsnNode n : mn.instructions) {
            if (n.getOpcode() == Opcodes.ACONST_NULL) {
                AbstractInsnNode nx = n.getNext();
                if (nx != null && nx.getOpcode() == Opcodes.ARETURN) {
                    lastNull = n;
                }
            }
        }
        InsnList ins = new InsnList();
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$savedHost", "Ljava/lang/Object;"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$firstSpecialFromAdjacentHost",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";Ljava/util/List;)L" + ITEM_STACK + ";", false));
        ins.add(new InsnNode(Opcodes.ARETURN));
        AbstractInsnNode aret = lastNull.getNext();
        mn.instructions.insertBefore(lastNull, ins);
        mn.instructions.remove(lastNull);
        mn.instructions.remove(aret);
    }

    /**
     * 诊断版：把空的 neoecoae$log 变成真输出，但**严格限流**。
     *
     * fix13diag 灾难教训：不限流的 stderr 输出在 2 秒内把日志刷到 367MB / 222 万行，
     * 同步 I/O 阻塞主线程 → 上传界面打不开、游戏退不出。
     * 这里用静态计数器 neoecoae$diagN 封顶 3000 行。
     */
    /**
     * fix38diag：第二条日志通道 `[NEO2]`，独立计数器 `neoecoae$diagN2`（封顶 4000）。
     * 专供上传/显示探针（[D]/[E]）使用——否则会被 [M]/[S]/[H] 的高频噪声挤掉，看不到列表里每个目标的结果。
     */
    private static void addLog2(ClassNode cn) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "neoecoae$log2",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        InsnList ins = mn.instructions;
        LabelNode lRet = new LabelNode();

        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$diagN2", "I"));
        ins.add(new LdcInsnNode(4000));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lRet));
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$diagN2", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$diagN2", "I"));

        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        ins.add(new LdcInsnNode("[NEO2] "));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new LdcInsnNode(" | "));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false));

        ins.add(lRet);
        ins.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 4;
        mn.maxLocals = 2;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$log2");
    }

    private static void patchLog(MethodNode mn) {
        mn.instructions.clear();
        mn.tryCatchBlocks.clear();
        mn.localVariables = null;
        InsnList ins = mn.instructions;
        LabelNode lRet = new LabelNode();

        // if (neoecoae$diagN >= CAP) return;  // fix37diag：放开诊断日志（用完须改回 0）
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$diagN", "I"));
        ins.add(new LdcInsnNode(6000));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lRet));
        // neoecoae$diagN++;
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, MATCHER, "neoecoae$diagN", "I"));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATCHER, "neoecoae$diagN", "I"));

        // System.err.println("[NEO] " + a + " | " + b);
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        ins.add(new LdcInsnNode("[NEO] "));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new LdcInsnNode(" | "));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false));

        ins.add(lRet);
        ins.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 4;
        mn.maxLocals = 2;
    }

    /**
     * private static boolean neoecoae$diagMatch(RecipeMap map, ItemStack[] its, ItemStack mold, IAEStack[] outs)
     *
     * 诊断版匹配：做一次 findRecipeQuery + outputsMatch，并输出状态码便于定位：
     *   st=0 → 连配方都没找到（输入/模具不匹配）
     *   st=1 → 找到配方但产物对不上（outputsMatch 失败）
     *   st=2 → 完全命中
     */
    private static void addDiagMatch(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$diagMatch",
                "(L" + RECIPE_MAP + ";[L" + ITEM_STACK + ";L" + ITEM_STACK + ";[L" + IAE_STACK + ";)Z",
                null, null);
        InsnList ins = mn.instructions;
        LabelNode lNull = new LabelNode();
        LabelNode lNo = new LabelNode();
        LabelNode lOk = new LabelNode();

        // fix14: 先读 backend.properties.specialSlotSensitive，分支处理。
        // boolean sens = ((Boolean) getFieldValue(getFieldValue(map.getBackend(), "properties"), "specialSlotSensitive")).booleanValue();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getBackend",
                "()Lgregtech/api/recipe/RecipeMapBackend;", false));
        ins.add(new LdcInsnNode("properties"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new LdcInsnNode("specialSlotSensitive"));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$getFieldValue",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 5));

        // log("[S] sens=", sens)
        ins.add(new LdcInsnNode("[S] sens="));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        LabelNode lSensFalse = new LabelNode();
        LabelNode lAfterFind = new LabelNode();

        // if (!sens) goto lSensFalse;
        ins.add(new VarInsnNode(Opcodes.ILOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lSensFalse));

        // sens=true: r = map.findRecipeQuery().items(its).notUnificated(true).dontCheckStackSizes(true).specialSlot(mold).find();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "findRecipeQuery",
                "()Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "items",
                "([L" + ITEM_STACK + ";)Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "notUnificated",
                "(Z)Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "dontCheckStackSizes",
                "(Z)Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "specialSlot",
                "(L" + ITEM_STACK + ";)Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "find",
                "()Lgregtech/api/util/GTRecipe;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lAfterFind));

        // sens=false: 把模具加入 items 数组，用 items(newItems) 查找
        ins.add(lSensFalse);

        // int len = its.length;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new InsnNode(Opcodes.ARRAYLENGTH));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 6));

        // ItemStack[] newItems = new ItemStack[len + 1];
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IADD));
        ins.add(new TypeInsnNode(Opcodes.ANEWARRAY, ITEM_STACK));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // System.arraycopy(its, 0, newItems, 0, len);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));

        // newItems[len] = mold;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new InsnNode(Opcodes.AASTORE));

        // r = map.findRecipeQuery().items(newItems).notUnificated(true).dontCheckStackSizes(true).find();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "findRecipeQuery",
                "()Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "items",
                "([L" + ITEM_STACK + ";)Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "notUnificated",
                "(Z)Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "dontCheckStackSizes",
                "(Z)Lgregtech/api/recipe/FindRecipeQuery;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gregtech/api/recipe/FindRecipeQuery", "find",
                "()Lgregtech/api/util/GTRecipe;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        ins.add(lAfterFind);

        // log("  try=", mold)
        ins.add(new LdcInsnNode("  try="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));

        // if (r == null) { log st=0; return false; }
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));
        // if (outputsMatch(r, outs)) ok else no
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "outputsMatch",
                "(Lgregtech/api/util/GTRecipe;[L" + IAE_STACK + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNo));

        ins.add(lOk);
        ins.add(new LdcInsnNode("  st="));
        ins.add(new InsnNode(Opcodes.ICONST_2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IRETURN));

        ins.add(lNo);
        ins.add(new LdcInsnNode("  st="));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new InsnNode(Opcodes.IRETURN));

        ins.add(lNull);
        ins.add(new LdcInsnNode("  st="));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new InsnNode(Opcodes.IRETURN));

        mn.maxStack = 6;
        mn.maxLocals = 8;
        cn.methods.add(mn);
    }

    /**
     * private static Set neoecoae$specialItemIdSet(RecipeMap map)
     *
     * fix16 核心：GT 的「不消耗物品」（模具）存在 GTRecipe.mSpecialItems 里，
     * 而 RecipeMap.getNonConsumedInputItemIds() 统计的是「mInputs 里数量为 0 的物品」，
     * 对 extruder 这类把模具放 mSpecialItems 的配方恒为空集 —— 所以原版 mod 用
     * INonConsumedItemDisplay.isDisplayableItem 筛模具，对挤压机永远筛不出东西
     * （fix15diag 实测 extruder 的候选数 candN=0）。
     *
     * 正确判据与 RecipeMapBackend.filterFindRecipe 一致：直接取所有配方的
     * mSpecialItems 组成 ItemId 集合，仓里物品命中集合的才是真正的模具候选。
     */
    private static void addSpecialItemIdSet(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$specialItemIdSet",
                "(L" + RECIPE_MAP + ";)Ljava/util/Set;", null, null);
        InsnList ins = mn.instructions;
        LabelNode lLoop = new LabelNode();
        LabelNode lEnd = new LabelNode();
        LabelNode lSkipSi = new LabelNode();

        // Set ids = new HashSet();
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // Iterator it = map.getAllRecipes().iterator();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getAllRecipes",
                "()Ljava/util/Collection;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));

        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lEnd));

        // Object r = it.next();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // Object si = ((GTRecipe) r).mSpecialItems;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, "gregtech/api/util/GTRecipe"));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, "gregtech/api/util/GTRecipe", "mSpecialItems", "Ljava/lang/Object;"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // if (!(si instanceof ItemStack)) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, ITEM_STACK));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lLoop));

        // ItemStack st = (ItemStack) si; if (st == null) continue;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lSkipSi));

        // ids.add(GTUtility.ItemId.create(st));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY_ITEM_ID, "create",
                "(L" + ITEM_STACK + ";)L" + GT_UTILITY_ITEM_ID + ";", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
        ins.add(new InsnNode(Opcodes.POP));

        ins.add(lSkipSi);
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        ins.add(lEnd);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 3;
        mn.maxLocals = 6;
        cn.methods.add(mn);
    }
}
