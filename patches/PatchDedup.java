import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Fixes the "network already has this recipe" check.
 *
 * Root cause, proven from the in-game dump: an encoded pattern's NBT carries a top level
 * {@code encodedAt} timestamp, e.g.
 *   {... in:[...], encodedAt:1792075500985L, out:[...], author:"xiaoYang820" ...}
 * so two encodings of the same recipe always differ and
 * {@code ItemStack.areItemStackTagsEqual()} returns false. That is why
 * {@code firstEmptySlot()} never reported -2 and duplicates were never blocked.
 *
 * Fix: add a recipe comparison that drops {@code encodedAt} before comparing, plus a
 * PatternUploadTarget helper that scans the target's slots with it, and point
 * {@code neoecoae$recycleDuplicate()} at the new helper.
 *
 * SRG names verified by descriptor against appliedenergistics2-rv3 + gregtech jars:
 *   NBTTagCompound.func_74737_b()Lnet/minecraft/nbt/NBTBase;     // copy()
 *   NBTTagCompound.func_82580_o(Ljava/lang/String;)V             // removeTag(String)
 *
 * usage: PatchDedup <classes dir> <out dir>
 */
public class PatchDedup {

    private static final String TARGET = "cn/dancingsnow/neoecoae/crafting/upload/PatternUploadTarget";
    private static final String SESSION = "cn/dancingsnow/neoecoae/crafting/upload/PatternUploadSession";
    private static final String ITEMSTACK = "net/minecraft/item/ItemStack";
    private static final String NBT = "net/minecraft/nbt/NBTTagCompound";
    private static final String NBTBASE = "net/minecraft/nbt/NBTBase";
    private static final String IINVENTORY = "net/minecraft/inventory/IInventory";

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        // ---- PatternUploadTarget: sameRecipe + holdsRecipe ----
        ClassNode tn = read(dir, TARGET);
        if (find(tn, "neoecoae$sameRecipe") != null) throw new IllegalStateException("already patched");
        tn.methods.add(sameRecipe());
        tn.methods.add(holdsRecipe());
        write(out, TARGET, tn);
        System.out.println("added " + TARGET + ".neoecoae$sameRecipe / neoecoae$holdsRecipe");

        // ---- PatternUploadSession: recycleDuplicate uses holdsRecipe ----
        ClassNode sn = read(dir, SESSION);
        MethodNode rec = find(sn, "neoecoae$recycleDuplicate");
        if (rec == null) throw new IllegalStateException("neoecoae$recycleDuplicate not found");
        int swapped = 0;
        for (AbstractInsnNode insn : rec.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (TARGET.equals(mi.owner) && "hasPattern".equals(mi.name)
                    && ("(L" + ITEMSTACK + ";)Z").equals(mi.desc)) {
                    mi.name = "neoecoae$holdsRecipe";
                    swapped++;
                }
            }
        }
        if (swapped != 1) throw new IllegalStateException("expected one hasPattern call, found " + swapped);
        write(out, SESSION, sn);
        System.out.println("recycleDuplicate: hasPattern -> neoecoae$holdsRecipe");
    }

    /**
     * <pre>
     * public static boolean neoecoae$sameRecipe(ItemStack a, ItemStack b) {
     *     if (a == null || b == null || !a.isItemEqual(b)) return false;
     *     NBTTagCompound ta = a.getStackTagCompound();
     *     NBTTagCompound tb = b.getStackTagCompound();
     *     if (ta == null || tb == null) return ta == tb;
     *     NBTTagCompound ca = (NBTTagCompound) ta.copy();
     *     NBTTagCompound cb = (NBTTagCompound) tb.copy();
     *     ca.removeTag("encodedAt");
     *     cb.removeTag("encodedAt");
     *     return ca.equals(cb);
     * }
     * </pre>
     * locals 0 a, 1 b, 2 ta, 3 tb, 4 ca, 5 cb. Every frame is absolute (F_FULL).
     */
    static MethodNode sameRecipe() {
        MethodNode m = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "neoecoae$sameRecipe", "(L" + ITEMSTACK + ";L" + ITEMSTACK + ";)Z", null, null);
        InsnList c = m.instructions;
        LabelNode l1 = new LabelNode(), l2 = new LabelNode(), l3 = new LabelNode();
        LabelNode lnull = new LabelNode(), lret0 = new LabelNode(), l4 = new LabelNode();

        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, l1));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));
        c.add(l1);
        c.add(full(ITEMSTACK, ITEMSTACK));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, l2));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));
        c.add(l2);
        c.add(full(ITEMSTACK, ITEMSTACK));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEMSTACK, "func_77969_a",
            "(L" + ITEMSTACK + ";)Z", false));
        c.add(new JumpInsnNode(Opcodes.IFNE, l3));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));

        c.add(l3);
        c.add(full(ITEMSTACK, ITEMSTACK));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEMSTACK, "func_77978_p", "()L" + NBT + ";", false));
        c.add(new VarInsnNode(Opcodes.ASTORE, 2));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEMSTACK, "func_77978_p", "()L" + NBT + ";", false));
        c.add(new VarInsnNode(Opcodes.ASTORE, 3));
        c.add(new VarInsnNode(Opcodes.ALOAD, 2));
        c.add(new JumpInsnNode(Opcodes.IFNULL, lnull));
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, l4));

        c.add(lnull);
        c.add(full(ITEMSTACK, ITEMSTACK, NBT, NBT));
        c.add(new VarInsnNode(Opcodes.ALOAD, 2));
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new JumpInsnNode(Opcodes.IF_ACMPNE, lret0));
        c.add(new InsnNode(Opcodes.ICONST_1));
        c.add(new InsnNode(Opcodes.IRETURN));
        c.add(lret0);
        c.add(full(ITEMSTACK, ITEMSTACK, NBT, NBT));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));

        c.add(l4);
        c.add(full(ITEMSTACK, ITEMSTACK, NBT, NBT));
        c.add(new VarInsnNode(Opcodes.ALOAD, 2));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NBT, "func_74737_b", "()L" + NBTBASE + ";", false));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, NBT));
        c.add(new VarInsnNode(Opcodes.ASTORE, 4));
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NBT, "func_74737_b", "()L" + NBTBASE + ";", false));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, NBT));
        c.add(new VarInsnNode(Opcodes.ASTORE, 5));
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new LdcInsnNode("encodedAt"));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NBT, "func_82580_o", "(Ljava/lang/String;)V", false));
        c.add(new VarInsnNode(Opcodes.ALOAD, 5));
        c.add(new LdcInsnNode("encodedAt"));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NBT, "func_82580_o", "(Ljava/lang/String;)V", false));
        c.add(new VarInsnNode(Opcodes.ALOAD, 4));
        c.add(new VarInsnNode(Opcodes.ALOAD, 5));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NBT, "equals", "(Ljava/lang/Object;)Z", false));
        c.add(new InsnNode(Opcodes.IRETURN));

        m.maxStack = 3;
        m.maxLocals = 6;
        return m;
    }

    /**
     * <pre>
     * public boolean neoecoae$holdsRecipe(ItemStack pattern) {
     *     if (pattern == null || this.inventory == null) return false;
     *     for (int i = 0; i &lt; this.slotCount; i++) {
     *         ItemStack existing = this.inventory.getStackInSlot(i);
     *         if (existing != null &amp;&amp; neoecoae$sameRecipe(existing, pattern)) return true;
     *     }
     *     return false;
     * }
     * </pre>
     * locals 0 this, 1 pattern, 2 i, 3 existing. The loop head declares slot 3 as TOP because
     * on the entry edge it is still unassigned -- exactly what javac emits.
     */
    static MethodNode holdsRecipe() {
        MethodNode m = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "neoecoae$holdsRecipe",
            "(L" + ITEMSTACK + ";)Z", null, null);
        InsnList c = m.instructions;
        LabelNode l1 = new LabelNode(), l2 = new LabelNode();
        LabelNode head = new LabelNode(), next = new LabelNode(), lfalse = new LabelNode();

        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, l1));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));
        c.add(l1);
        c.add(full(TARGET, ITEMSTACK));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "inventory", "L" + IINVENTORY + ";"));
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, l2));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));

        c.add(l2);
        c.add(full(TARGET, ITEMSTACK));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new VarInsnNode(Opcodes.ISTORE, 2));

        c.add(head);
        c.add(full(TARGET, ITEMSTACK, Opcodes.INTEGER, Opcodes.TOP));
        c.add(new VarInsnNode(Opcodes.ILOAD, 2));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "slotCount", "I"));
        c.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lfalse));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "inventory", "L" + IINVENTORY + ";"));
        c.add(new VarInsnNode(Opcodes.ILOAD, 2));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, IINVENTORY, "func_70301_a",
            "(I)L" + ITEMSTACK + ";", true));
        c.add(new VarInsnNode(Opcodes.ASTORE, 3));
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new JumpInsnNode(Opcodes.IFNULL, next));
        c.add(new VarInsnNode(Opcodes.ALOAD, 3));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, "neoecoae$sameRecipe",
            "(L" + ITEMSTACK + ";L" + ITEMSTACK + ";)Z", false));
        c.add(new JumpInsnNode(Opcodes.IFEQ, next));
        c.add(new InsnNode(Opcodes.ICONST_1));
        c.add(new InsnNode(Opcodes.IRETURN));

        c.add(next);
        c.add(full(TARGET, ITEMSTACK, Opcodes.INTEGER, ITEMSTACK));
        c.add(new IincInsnNode(2, 1));
        c.add(new JumpInsnNode(Opcodes.GOTO, head));

        c.add(lfalse);
        c.add(full(TARGET, ITEMSTACK, Opcodes.INTEGER, Opcodes.TOP));
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));

        m.maxStack = 3;
        m.maxLocals = 4;
        return m;
    }

    /** F_FULL frame with an empty operand stack. */
    static FrameNode full(Object... locals) {
        Object[] l = locals.clone();
        return new FrameNode(Opcodes.F_FULL, l.length, l, 0, new Object[0]);
    }

    static MethodNode find(ClassNode cn, String name) {
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        return null;
    }

    static ClassNode read(Path dir, String internal) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(dir.resolve(internal + ".class"))).accept(cn, 0);
        return cn;
    }

    static void write(Path out, String internal, ClassNode cn) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        Path p = out.resolve(internal + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, cw.toByteArray());
    }
}
