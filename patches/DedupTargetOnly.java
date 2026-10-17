import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Target-only variant of PatchDedup: adds sameRecipe + holdsRecipe to PatternUploadTarget only.
 *
 * PatchDedup.main() also rewrites PatternUploadSession, and that part throws when the input
 * session is not the exact intermediate revision (it expects one hasPattern call to swap).
 * When only the target needs (re)building, use this tool instead - it is deterministic and
 * leaves the session untouched.
 *
 * usage: DedupTargetOnly <classes dir> <out dir>
 */
public class DedupTargetOnly {

    static final String TARGET = "cn/dancingsnow/neoecoae/crafting/upload/PatternUploadTarget";

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        ClassNode tn = PatchDedup.read(dir, TARGET);
        if (PatchDedup.find(tn, "neoecoae$sameRecipe") != null) {
            throw new IllegalStateException("already patched");
        }
        tn.methods.add(PatchDedup.sameRecipe());
        tn.methods.add(PatchDedup.holdsRecipe());
        PatchDedup.write(out, TARGET, tn);
        System.out.println("added " + TARGET + ".neoecoae$sameRecipe / neoecoae$holdsRecipe (target only)");
    }
}
