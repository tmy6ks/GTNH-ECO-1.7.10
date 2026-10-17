import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * neoecoae 上传目标补丁（第二类补丁，作用于 PatternUploadTarget）。
 *
 * fix36 显示增强：上传候选列表每个目标名后显示磨具。
 * fix38diag：探针走 [NEO2]（PatternRecipeMatcher.neoecoae$log2，独立计数），打印最终名字。
 * fix39：自己不消耗物品槽里有对应磨具的总成排序优先（compatibilityRank -100）。
 * fix40：ownMold 判定改为「与整机 moldForHost 磨具比对（GTUtility.areStacksEqual）」——
 *        之前用 diagMatch(空输入,...) 验证，但配方还需要铁锭等真实输入，空输入恒 false（own 恒 null 的根因）。
 *        并按用户要求做严格过滤：样板总成类目标（host instanceof IDualInputHatch），
 *        自己不消耗物品槽里没有匹配磨具的，从上传列表与上传白名单里剔除：
 *        - isRecipeCompatible(Z,details,routeKey) 入口插 gate → false（列表不显示）
 *        - isExactMatch(Z,details,routeKey)        入口插 gate → false（手动/自动都不上传）
 */
public class PatchUploadTarget {
    static final String TARGET = "cn/dancingsnow/neoecoae/crafting/upload/PatternUploadTarget";
    static final String ROUTE = "cn/dancingsnow/neoecoae/crafting/upload/PatternUploadTarget$Route";
    static final String KIND = "cn/dancingsnow/neoecoae/crafting/upload/PatternUploadTarget$Kind";
    static final String ROUTE_KEY = "cn/dancingsnow/neoecoae/crafting/upload/PatternRouteKey";
    static final String DETAILS = "appeng/api/networking/crafting/ICraftingPatternDetails";
    static final String MATCHER = "cn/dancingsnow/neoecoae/crafting/upload/PatternRecipeMatcher";
    static final String RECIPE_MAP = "gregtech/api/recipe/RecipeMap";
    static final String ITEM_STACK = "net/minecraft/item/ItemStack";
    static final String IAE_STACK = "appeng/api/storage/data/IAEStack";
    static final String DUAL_HATCH = "gregtech/common/tileentities/machines/IDualInputHatch";
    static final String I_MTE = "gregtech/api/interfaces/metatileentity/IMetaTileEntity";
    static final String I_VIEWABLE = "appeng/api/util/IInterfaceViewable";
    static final String I_GT_TE = "gregtech/api/interfaces/tileentity/IGregTechTileEntity";
    static final String TILE_ENTITY = "net/minecraft/tileentity/TileEntity";
    static final String GT_UTILITY = "gregtech/api/util/GTUtility";
    static final String GT_LANG_MGR = "gregtech/api/util/GTLanguageManager";
    static final String LOG2 = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    static final String OWN_MOLD = "(L" + TARGET + ";L" + ROUTE_KEY + ";L" + DETAILS + ";)L" + ITEM_STACK + ";";

    public static void main(String[] args) throws Exception {
        Path src = Paths.get(args[0]);
        Path dst = Paths.get(args[1]);
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(src)).accept(cn, ClassReader.EXPAND_FRAMES);
        addMoldSuffix(cn);
        addMachineMold(cn);
        addOwnMold(cn);
        addRecipeNeedsMold(cn);
        addIsIntegratedCircuit(cn);
        addOwnGate(cn);
        addRankBonus(cn);
        addLogI(cn);
        patchDisplayName(cn);
        patchEntryGate(cn, "isExactMatch", "(ZL" + DETAILS + ";L" + ROUTE_KEY + ";)Z");
        patchExactMatch(cn);
        patchRecipeCompatible(cn);
        patchCompatibilityRank(cn);
        patchInterfaceRoutesDualHatch(cn);
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
        Path out = dst.resolve(cn.name + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, cw.toByteArray());
        System.out.println("patched " + cn.name + " methods=" + cn.methods.size());
    }

    // ------------------------------------------------------------------ 显示

    private static void addMoldSuffix(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "neoecoae$getMoldSuffix",
                "(L" + ROUTE + ";L" + ROUTE_KEY + ";L" + DETAILS + ";)Ljava/lang/String;", null, null);
        InsnList ins = mn.instructions;
        LabelNode lEmpty = new LabelNode();
        LabelNode lCircuitOk = new LabelNode();
        LabelNode lCheckKind = new LabelNode();
        LabelNode lMachine = new LabelNode();

        ins.add(new LdcInsnNode("[D] tgt="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET, "getName", "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));

        // if (route == null) return "";
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEmpty));
        // RecipeMap map = effectiveRecipeMap(route, routeKey);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "effectiveRecipeMap",
                "(L" + ROUTE + ";L" + ROUTE_KEY + ";)L" + RECIPE_MAP + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));

        LabelNode lMapNull = new LabelNode();
        LabelNode lMapEnd = new LabelNode();
        ins.add(new LdcInsnNode("[D] map="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lMapNull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, RECIPE_MAP, "unlocalizedName", "Ljava/lang/String;"));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lMapEnd));
        ins.add(lMapNull);
        ins.add(new LdcInsnNode("null"));
        ins.add(lMapEnd);
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));

        // if (map == null) return "";
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEmpty));

        // fix39/40: ItemStack own = neoecoae$ownMold(this, routeKey, details);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$ownMold", OWN_MOLD, false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));

        LabelNode lOwnNull = new LabelNode();
        LabelNode lOwnEnd = new LabelNode();
        ins.add(new LdcInsnNode("[D2] own="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lOwnNull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lOwnEnd));
        ins.add(lOwnNull);
        ins.add(new LdcInsnNode("null"));
        ins.add(lOwnEnd);
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));

        // fix64: 样板里带编程电路、但目标自己没有电路 → 该目标跑不了这个配方，保持原名。
        // 典型：动力加工阵列的样板用「编程工具箱 MK.II」当电路，没插电路的样板总成不应显示模头。
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "circuit", "L" + ITEM_STACK + ";"));
        ins.add(new JumpInsnNode(Opcodes.IFNONNULL, lCircuitOk));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "patternCircuit",
                "(L" + DETAILS + ";)L" + ITEM_STACK + ";", false));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lCircuitOk));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lEmpty));
        ins.add(lCircuitOk);

        // fix63：目标自己槽里有正确模头 → 显示模头名。
        // 自己槽没有时：只有 AE2 接口类目标（AE2_INTERFACE / AE2_DUAL_INTERFACE，host 解析不出
        // MTE、ownMold 恒 null）才回退到整机/相邻模具；GT 样板总成目标一律原名，
        // 否则同一台机器上的多个总成会全部同名（fix60 事故）。
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lCheckKind));
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        ins.add(new LdcInsnNode(" [\u53ef\u4e0a\u4f20<"));   // " [可上传<"  —— 内部放未本地化注册名，客户端按 <> 提取后本地化
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "func_77977_a",
                "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new LdcInsnNode(">\u7684\u914d\u65b9]"));   // ">的配方]"
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        ins.add(new InsnNode(Opcodes.ARETURN));

        // lCheckKind: 自己槽没有模头。仅 AE2 接口类目标回退整机/相邻模具。
        ins.add(lCheckKind);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "kind", "L" + KIND + ";"));
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, KIND, "AE2_INTERFACE", "L" + KIND + ";"));
        ins.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, lMachine));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "kind", "L" + KIND + ";"));
        ins.add(new FieldInsnNode(Opcodes.GETSTATIC, KIND, "AE2_DUAL_INTERFACE", "L" + KIND + ";"));
        ins.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, lMachine));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lEmpty));

        // lMachine: 对 host 调 moldForHost（会扫相邻/所属机器的输入总成、巨型输入总成）。
        ins.add(lMachine);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, DETAILS, "getCondensedAEOutputs",
                "()[L" + IAE_STACK + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "host", "Ljava/lang/Object;"));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$moldForHost",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";[L" + IAE_STACK + ";)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEmpty));
        ins.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        ins.add(new InsnNode(Opcodes.DUP));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        ins.add(new LdcInsnNode(" [\u53ef\u4e0a\u4f20<"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "func_77977_a",
                "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new LdcInsnNode(">\u7684\u914d\u65b9]"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        ins.add(new InsnNode(Opcodes.ARETURN));

        ins.add(lEmpty);
        ins.add(new LdcInsnNode(""));
        ins.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 5;
        mn.maxLocals = 8;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$getMoldSuffix (own-slot first)");
    }

    // ------------------------------------------------- fix40：自己槽里的磨具

    /**
     * private static ItemStack neoecoae$machineMold(PatternUploadTarget self, PatternRouteKey routeKey,
     *                                               ICraftingPatternDetails details)
     *
     * 整机级磨具（moldForHost）：null 表示该配方/机器根本不需要磨具（搅拌机、组装机等）。
     */
    private static void addMachineMold(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$machineMold", OWN_MOLD, null, null);
        InsnList ins = mn.instructions;
        LabelNode lNull = new LabelNode();

        // if (self == null || details == null) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));

        // Route route = self.selectRoute(routeKey, details);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, "selectRoute",
                "(L" + ROUTE_KEY + ";L" + DETAILS + ";)L" + ROUTE + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));

        // RecipeMap map = effectiveRecipeMap(route, routeKey);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "effectiveRecipeMap",
                "(L" + ROUTE + ";L" + ROUTE_KEY + ";)L" + RECIPE_MAP + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));

        // Object host = self.host;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "host", "Ljava/lang/Object;"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));

        // IAEStack[] outputs = details.getCondensedAEOutputs();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, DETAILS, "getCondensedAEOutputs",
                "()[L" + IAE_STACK + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        // ItemStack r = PatternRecipeMatcher.neoecoae$moldForHost(host, map, outputs);
        // moldForHost 内部已自行按 host 解析 IMetaTileEntity / IInterfaceViewable，
        // 故不再限制 IDualInputHatch —— 普通机器（挤出机本体等）也能取磨具。
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$moldForHost",
                "(Ljava/lang/Object;L" + RECIPE_MAP + ";[L" + IAE_STACK + ";)L" + ITEM_STACK + ";", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));
        // if (r == null) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));
        // if (neoecoae$isIntegratedCircuit(r)) return null;   // 集成电路不是磨具，不能当模头显示
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$isIntegratedCircuit",
                "(L" + ITEM_STACK + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lNull));
        // log2("[D4] mm=", r)
        ins.add(new LdcInsnNode("[D4] mm="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new InsnNode(Opcodes.ARETURN));

        ins.add(lNull);
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 4;
        mn.maxLocals = 8;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$machineMold");
    }

    /**
     * private static ItemStack neoecoae$ownMold(PatternUploadTarget self, PatternRouteKey routeKey,
     *                                           ICraftingPatternDetails details)
     *
     * fix41：machineMold（整机需要的磨具）与总成自己不消耗物品槽逐个 GTUtility.areStacksEqual 比对。
     */
    private static void addOwnMold(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$ownMold", OWN_MOLD, null, null);
        InsnList ins = mn.instructions;
        LabelNode lNull = new LabelNode();
        LabelNode lLoop = new LabelNode();
        LabelNode lNext = new LabelNode();
        LabelNode lEnd = new LabelNode();
        LabelNode lNotMte = new LabelNode();
        LabelNode lViewEnd = new LabelNode();

        // if (self == null || details == null) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));

        // Object host = self.host;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "host", "Ljava/lang/Object;"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        // 把 host 解析成 IMetaTileEntity：既可以是直接的 MTE，也可以是包着 TileEntity 的 IInterfaceViewable。
        // 不再限制 IDualInputHatch —— 普通机器（挤出机本体等）也能识别自身不消耗物品槽里的模头。
        // IMetaTileEntity mte = null;
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        // if (host instanceof IMetaTileEntity) mte = (IMetaTileEntity) host;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_MTE));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNotMte));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_MTE));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        ins.add(lNotMte);
        // else if (host instanceof IInterfaceViewable) { TileEntity t = host.getTileEntity();
        //          if (t instanceof IGregTechTileEntity) mte = t.getMetaTileEntity(); }
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNONNULL, lViewEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_VIEWABLE));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lViewEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_VIEWABLE));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_VIEWABLE, "getTileEntity",
                "()L" + TILE_ENTITY + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 11));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 11));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, I_GT_TE));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lViewEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 11));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, I_GT_TE));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_GT_TE, "getMetaTileEntity",
                "()L" + I_MTE + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 7));
        ins.add(lViewEnd);
        // if (mte == null) return null;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));
        // fix43：物品实际在 IMetaTileEntity.getRealInventory() 里（getSharedItems 读的 mInventory 对这些总成恒空）。
        // 真实物品栏同时包含共享的"不消耗物品"槽和每个样板各自的"不消耗物品"槽，全覆盖。
        // ItemStack[] inv = mte.getRealInventory();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 7));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, I_MTE, "getRealInventory",
                "()[L" + ITEM_STACK + ";", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 6));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));
        // log2("[D4] invN=", inv.length)
        ins.add(new LdcInsnNode("[D4] invN="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new InsnNode(Opcodes.ARRAYLENGTH));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));

        // log2("[D4] ownAt=", self.getName())
        ins.add(new LdcInsnNode("[D4] ownAt="));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET, "getName", "()Ljava/lang/String;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$machineMold", OWN_MOLD, false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 8));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));

        // for (int i = 0; i < shared.length; i++)
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 9));
        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ILOAD, 9));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new InsnNode(Opcodes.ARRAYLENGTH));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 6));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 9));
        ins.add(new InsnNode(Opcodes.AALOAD));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 10));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNext));
        // if (neoecoae$isIntegratedCircuit(s)) continue;   // 集成电路不是模头
        ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$isIntegratedCircuit",
                "(L" + ITEM_STACK + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lNext));
        // if (GTUtility.areStacksEqual(mold, s)) return s;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 8));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GT_UTILITY, "areStacksEqual",
                "(L" + ITEM_STACK + ";L" + ITEM_STACK + ";)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lNext));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
        ins.add(new InsnNode(Opcodes.ARETURN));
        ins.add(lNext);
        ins.add(new IincInsnNode(9, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

        ins.add(lEnd);
        ins.add(lNull);
        ins.add(new InsnNode(Opcodes.ACONST_NULL));
        ins.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 4;
        mn.maxLocals = 12;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$ownMold (compare with machineMold)");
    }

    /**
     * private static boolean neoecoae$isIntegratedCircuit(ItemStack s)
     *
     * 判断物品是否是「电路载体」而不是模头：
     *   - GT 集成电路 gt.integrated_circuit
     *   - 编程工具箱 MK.II  prog_toolkit（fix65）
     *   - 编程器电路       programming_circuit（fix65）
     * 用 toString()（双端安全，含 unlocalizedName）做宽松匹配。命中则不当作模头显示/匹配。
     */
    private static void addIsIntegratedCircuit(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$isIntegratedCircuit",
                "(L" + ITEM_STACK + ";)Z", null, null);
        InsnList ins = mn.instructions;
        LabelNode lTrue = new LabelNode();
        LabelNode lEnd = new LabelNode();
        // if (s == null) return false;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEnd));
        // String t = s.toString();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "toString", "()Ljava/lang/String;", false));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 1));
        // if (t != null && t.contains("integrated_circuit")) return true;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new LdcInsnNode("integrated_circuit"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                "(Ljava/lang/CharSequence;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lTrue));
        // fix65: 电路的其它载体也不算模头
        //   - 编程工具箱 MK.II  -> reobf.proghatches.item.ItemProgrammingToolkit (prog_toolkit)
        //   - 编程器电路        -> reobf.proghatches.item.ItemProgrammingCircuit (programming_circuit)
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new LdcInsnNode("prog_toolkit"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                "(Ljava/lang/CharSequence;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lTrue));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new LdcInsnNode("programming_circuit"));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                "(Ljava/lang/CharSequence;)Z", false));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lTrue));
        ins.add(lEnd);
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        ins.add(lTrue);
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 2;
        mn.maxLocals = 2;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$isIntegratedCircuit");
    }

    /**
     * private static boolean neoecoae$ownGate(PatternUploadTarget self, boolean processing,
     *                                         ICraftingPatternDetails details, PatternRouteKey routeKey)
     *
     * 返回 true = 该目标应被剔除。
     * fix55：仅当「该配方确实需要磨具/不消耗物品」且「目标自己槽 AND 输入总线都没有对应磨具」时才剔除。
     * 这样挤出机磨具放在输入总线的目标不会被误杀；同时没有磨具的空槽目标不会再被扫出来。
     */
    private static void addOwnGate(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$ownGate",
                "(L" + TARGET + ";ZL" + DETAILS + ";L" + ROUTE_KEY + ";)Z", null, null);
        InsnList ins = mn.instructions;
        // fix56：关闭过滤门。用户要求：空不消耗物品槽的总成显示原名（不过滤），
        // 有正确模头的总成显示模头名。是否上传由 isExactMatch / routeMatchesRecipe 自行判定。
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 1;
        mn.maxLocals = 4;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$ownGate (DISABLED: never filter)");
    }

    /**
     * private static boolean neoecoae$recipeNeedsMold(RecipeMap map)
     *
     * 判断该 RecipeMap 的配方是否含有不消耗输入（mInputs stackSize==0）或 mSpecialItems。
     * 用于 ownGate：只有"需要磨具/不消耗物品"的配方才要求目标必须有对应物品，否则不过滤。
     */
    private static void addRecipeNeedsMold(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$recipeNeedsMold",
                "(L" + RECIPE_MAP + ";)Z", null, null);
        InsnList ins = mn.instructions;
        LabelNode lNull = new LabelNode();
        LabelNode lLoop = new LabelNode();
        LabelNode lEnd = new LabelNode();
        LabelNode lArrNext = new LabelNode();
        LabelNode lArrLoop = new LabelNode();
        LabelNode lArrEnd = new LabelNode();

        // if (map == null) return false;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lNull));

        // Iterator it = map.getAllRecipes().iterator();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RECIPE_MAP, "getAllRecipes",
                "()Ljava/util/Collection;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;", true));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 1));

        ins.add(lLoop);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        ins.add(new TypeInsnNode(Opcodes.CHECKCAST, "gregtech/api/util/GTRecipe"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // for (ItemStack s : r.mInputs) if (s != null && s.stackSize == 0) return true;
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, "gregtech/api/util/GTRecipe", "mInputs", "[L" + ITEM_STACK + ";"));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 3));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new VarInsnNode(Opcodes.ISTORE, 4));
        ins.add(lArrLoop);
        ins.add(new VarInsnNode(Opcodes.ILOAD, 4));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new InsnNode(Opcodes.ARRAYLENGTH));
        ins.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lArrEnd));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 4));
        ins.add(new InsnNode(Opcodes.AALOAD));
        ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lArrNext));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, ITEM_STACK, "field_77994_a", "I")); // stackSize
        ins.add(new JumpInsnNode(Opcodes.IFNE, lArrNext));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IRETURN));
        ins.add(lArrNext);
        ins.add(new IincInsnNode(4, 1));
        ins.add(new JumpInsnNode(Opcodes.GOTO, lArrLoop));
        ins.add(lArrEnd);

        // if (r.mSpecialItems instanceof ItemStack) return true; else continue outer loop
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, "gregtech/api/util/GTRecipe", "mSpecialItems", "Ljava/lang/Object;"));
        ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, ITEM_STACK));
        ins.add(new JumpInsnNode(Opcodes.IFEQ, lLoop));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.IRETURN));

        ins.add(lEnd);
        ins.add(lNull);
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 3;
        mn.maxLocals = 6;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$recipeNeedsMold");
    }

    private static void patchEntryGate(ClassNode cn, String methodName, String desc) {
        for (MethodNode mn : cn.methods) {
            if (!methodName.equals(mn.name) || !desc.equals(mn.desc)) continue;
            LabelNode lCont = new LabelNode();
            InsnList ins = new InsnList();
            // if (neoecoae$ownGate(this, processing, details, routeKey)) return false;
            ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ins.add(new VarInsnNode(Opcodes.ILOAD, 1));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$ownGate",
                    "(L" + TARGET + ";ZL" + DETAILS + ";L" + ROUTE_KEY + ";)Z", false));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, lCont));
            ins.add(new InsnNode(Opcodes.ICONST_0));
            ins.add(new InsnNode(Opcodes.IRETURN));
            ins.add(lCont);
            mn.instructions.insert(ins);
            mn.maxStack = Math.max(mn.maxStack, 4);
            System.out.println("  patchEntryGate: gate inserted into " + methodName);
            return;
        }
        System.out.println("  patchEntryGate: " + methodName + " not found");
    }

    private static void patchExactMatch(ClassNode cn) {
        String desc = "(ZL" + DETAILS + ";L" + ROUTE_KEY + ";)Z";
        for (MethodNode mn : cn.methods) {
            if (!"isExactMatch".equals(mn.name) || !desc.equals(mn.desc)) continue;

            InsnList head = new InsnList();
            head.add(new LdcInsnNode("[E] tgt="));
            head.add(new VarInsnNode(Opcodes.ALOAD, 0));
            head.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET, "getName", "()Ljava/lang/String;", false));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));
            head.add(new LdcInsnNode("[E] kind="));
            head.add(new VarInsnNode(Opcodes.ALOAD, 0));
            head.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "kind", "L" + KIND + ";"));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));
            head.add(new LdcInsnNode("[E] rk="));
            head.add(new VarInsnNode(Opcodes.ALOAD, 3));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));
            mn.instructions.insert(head);

            List<AbstractInsnNode> rets = new ArrayList<>();
            for (AbstractInsnNode in : mn.instructions) {
                if (in.getOpcode() == Opcodes.IRETURN) rets.add(in);
            }
            for (AbstractInsnNode ret : rets) {
                mn.instructions.insertBefore(ret, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, TARGET, "neoecoae$logI", "(I)I", false));
            }
            mn.maxStack = Math.max(mn.maxStack, 4);
            System.out.println("  patchExactMatch: logged entry + " + rets.size() + " returns");
            return;
        }
        System.out.println("  patchExactMatch: isExactMatch(Z,details,routeKey) not found");
    }

    private static void patchRecipeCompatible(ClassNode cn) {
        String desc = "(ZL" + DETAILS + ";L" + ROUTE_KEY + ";)Z";
        patchEntryGate(cn, "isRecipeCompatible", desc);
    }

    // ------------------------------------------------- fix39：排序加成

    private static void addRankBonus(ClassNode cn) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "neoecoae$rankBonus",
                "(IL" + TARGET + ";L" + DETAILS + ";L" + ROUTE_KEY + ";Z)I", null, null);
        InsnList ins = mn.instructions;
        LabelNode lCheck = new LabelNode();
        LabelNode lRankOk = new LabelNode();
        LabelNode lOwn = new LabelNode();

        ins.add(new VarInsnNode(Opcodes.ILOAD, 4));
        ins.add(new JumpInsnNode(Opcodes.IFNE, lCheck));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        ins.add(lCheck);
        ins.add(new VarInsnNode(Opcodes.ILOAD, 0));
        ins.add(new JumpInsnNode(Opcodes.IFGE, lRankOk));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        ins.add(lRankOk);
        ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$ownMold", OWN_MOLD, false));
        ins.add(new JumpInsnNode(Opcodes.IFNULL, lOwn));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 0));
        ins.add(new LdcInsnNode(100));
        ins.add(new InsnNode(Opcodes.ISUB));
        ins.add(new InsnNode(Opcodes.IRETURN));
        ins.add(lOwn);
        ins.add(new VarInsnNode(Opcodes.ILOAD, 0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 3;
        mn.maxLocals = 5;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$rankBonus");
    }

    private static void patchCompatibilityRank(ClassNode cn) {
        // fix59: the boost is applied inside compatibilityRankWithRecipeType -- the single common
        // source of both ranks. The upload LIST is sorted by the value built in
        // PatternUploadSession.addRecipeCompatibleTarget (compatibilityRankWithRecipeType), while
        // auto-upload calls compatibilityRank() which delegates here. Patching only
        // compatibilityRank left the list order without the bonus, so the target holding the mold
        // in its own non-consumed slot showed up LAST and the first (empty) row got picked.
        String desc = "(ZL" + DETAILS + ";L" + ROUTE_KEY + ";I)I";
        for (MethodNode mn : cn.methods) {
            if (!"compatibilityRankWithRecipeType".equals(mn.name) || !desc.equals(mn.desc)) continue;
            List<AbstractInsnNode> rets = new ArrayList<>();
            for (AbstractInsnNode in : mn.instructions) {
                if (in.getOpcode() == Opcodes.IRETURN) rets.add(in);
            }
            for (AbstractInsnNode ret : rets) {
                InsnList ins = new InsnList();
                ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
                ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
                ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
                ins.add(new VarInsnNode(Opcodes.ILOAD, 1));
                ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$rankBonus",
                        "(IL" + TARGET + ";L" + DETAILS + ";L" + ROUTE_KEY + ";Z)I", false));
                mn.instructions.insertBefore(ret, ins);
            }
            mn.maxStack = Math.max(mn.maxStack, 5);
            System.out.println("  patchCompatibilityRank: bonus applied to " + rets.size()
                    + " returns of compatibilityRankWithRecipeType");
            return;
        }
        System.out.println("  patchCompatibilityRank: compatibilityRankWithRecipeType(Z,details,routeKey,I) not found");
    }

    // ------------------------------------------------- fix61：ME 接口贴着双输入总成

    /**
     * fix61: ME 接口贴着「GT 输入总成 / 巨型输入总成」时也要能出现在上传列表里。
     *
     * interfaceRoutes 的「相邻机器」分支只认 MTEHatchInputBus.mRecipeMap：
     *   if (!isRecipeMapRoute(machine)) continue;
     *   RecipeMap map = recipeMap(machine);          // DualInputHatch 的 mRecipeMap 为 null
     *   ItemStack circuit = circuitForMachine(machine);
     *   if (map != null || circuit != null) { ...add route... }   // 两者皆 null -> 不加
     * DualInputHatch / HumongousDualInputHatch 虽继承 MTEHatchInputBus，却没有 mRecipeMap，
     * 所以这条路被跳过、目标不显示。类里已有 addCraftingInputControllerRoutes：它扫描已成型
     * 多方块控制器的 mDualInputHatches 反查 recipeMap，正好补上这一路。
     * 在 circuitForMachine 的 ASTORE 之后插入：若 machine 是 IDualInputHatch 就调用它。
     */
    private static void patchInterfaceRoutesDualHatch(ClassNode cn) {
        String desc = "(L" + I_VIEWABLE + ";L" + ITEM_STACK + ";)Ljava/util/List;";
        String cdesc = "(L" + I_MTE + ";)L" + ITEM_STACK + ";";
        String ctrl = "(Ljava/util/List;L" + I_MTE + ";L" + ITEM_STACK + ";)V";
        for (MethodNode mn : cn.methods) {
            if (!"interfaceRoutes".equals(mn.name) || !desc.equals(mn.desc)) continue;
            AbstractInsnNode anchor = null;
            for (AbstractInsnNode in : mn.instructions) {
                if (in instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    if (TARGET.equals(mi.owner) && "circuitForMachine".equals(mi.name) && cdesc.equals(mi.desc)) {
                        // 方法里有两处：第一处在 viewable instanceof IMetaTileEntity 分支，
                        // 第二处（最后）才是「ME 接口 → 相邻机器」分支，必须取后者。
                        anchor = in;
                    }
                }
            }
            if (anchor == null) {
                System.out.println("  patchInterfaceRoutesDualHatch: circuitForMachine call not found");
                return;
            }
            AbstractInsnNode store = anchor.getNext();
            while (store != null && !(store instanceof VarInsnNode && store.getOpcode() == Opcodes.ASTORE)) {
                store = store.getNext();
            }
            if (store == null) {
                System.out.println("  patchInterfaceRoutesDualHatch: ASTORE not found");
                return;
            }
            int circuitLocal = ((VarInsnNode) store).var;
            AbstractInsnNode prev = anchor.getPrevious();
            int machineLocal = 8;
            if (prev instanceof VarInsnNode && prev.getOpcode() == Opcodes.ALOAD) {
                machineLocal = ((VarInsnNode) prev).var;
            }
            int routesLocal = 2;
            LabelNode lSkip = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, machineLocal));
            ins.add(new TypeInsnNode(Opcodes.INSTANCEOF, DUAL_HATCH));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, lSkip));
            ins.add(new VarInsnNode(Opcodes.ALOAD, routesLocal));
            ins.add(new VarInsnNode(Opcodes.ALOAD, machineLocal));
            ins.add(new VarInsnNode(Opcodes.ALOAD, circuitLocal));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET,
                    "addCraftingInputControllerRoutes", ctrl, false));
            ins.add(lSkip);
            mn.instructions.insert(store, ins);
            mn.maxStack = Math.max(mn.maxStack, 3);
            System.out.println("  patchInterfaceRoutesDualHatch: inserted (machine=" + machineLocal
                    + " circuit=" + circuitLocal + " routes=" + routesLocal + ")");
            return;
        }
        System.out.println("  patchInterfaceRoutesDualHatch: interfaceRoutes(IInterfaceViewable,ItemStack) not found");
    }

    // ------------------------------------------------------------------ 诊断

    private static void addLogI(ClassNode cn) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "neoecoae$logI", "(I)I", null, null);
        InsnList ins = mn.instructions;
        ins.add(new LdcInsnNode("[E] out="));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 0));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false));
        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));
        ins.add(new VarInsnNode(Opcodes.ILOAD, 0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 2;
        mn.maxLocals = 1;
        cn.methods.add(mn);
        System.out.println("  added neoecoae$logI");
    }

    private static void patchDisplayName(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (!"getDisplayName".equals(mn.name)) continue;
            if (!("(L" + ROUTE_KEY + ";L" + DETAILS + ";)Ljava/lang/String;").equals(mn.desc)) continue;
            AbstractInsnNode ret = null;
            for (AbstractInsnNode in : mn.instructions) {
                if (in.getOpcode() == Opcodes.ARETURN) ret = in;
            }
            if (ret == null) {
                System.out.println("  patchDisplayName: areturn not found");
                return;
            }
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ASTORE, 4));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 3));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 2));
            ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, "neoecoae$getMoldSuffix",
                    "(L" + ROUTE + ";L" + ROUTE_KEY + ";L" + DETAILS + ";)Ljava/lang/String;", false));
            ins.add(new VarInsnNode(Opcodes.ASTORE, 5));
            ins.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
            ins.add(new InsnNode(Opcodes.DUP));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 4));
            ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 5));
            ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
            ins.add(new InsnNode(Opcodes.DUP));
            ins.add(new LdcInsnNode("[D] name="));
            ins.add(new InsnNode(Opcodes.SWAP));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MATCHER, "neoecoae$log2", LOG2, false));
            ins.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions.insertBefore(ret, ins);
            mn.instructions.remove(ret);
            mn.maxLocals = Math.max(mn.maxLocals, 6);
            mn.maxStack = Math.max(mn.maxStack, 6);
            System.out.println("  patchDisplayName: appended mold suffix before areturn");
            return;
        }
        System.out.println("  patchDisplayName: getDisplayName(routeKey,details) not found");
    }
}
