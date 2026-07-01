package classcomparator;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** ASM 字节码结构指纹 + 数据类 + 方法级差异定位。 */
public class Fingerprint {

    public static class ClassFingerprint {
        public String logicHash; // 归一化文本的 SHA-256（用于比对）
        public String normalized; // 归一化后的文本（用于详情报告）
        /** 方法签名 → 方法体指令 SHA-256，用于方法级差异定位 */
        public Map<String, String> methodHashes = new LinkedHashMap<>();
    }

    public static class FingerprintPair {
        public ClassFingerprint oldFp, newFp;

        public FingerprintPair(ClassFingerprint o, ClassFingerprint n) {
            oldFp = o;
            newFp = n;
        }
    }

    /**
     * 构建 class 字节码结构指纹。
     * 跳过 Debug/Frame 信息，剥离变量槽位号，排序消除编译器顺序差异。
     */
    public static ClassFingerprint fingerprintClass(byte[] classData) throws Exception {
        ClassNode classNode = new ClassNode();
        new ClassReader(classData).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        StringBuilder builder = new StringBuilder();
        builder.append("CLASS=").append(classNode.name).append('\n');
        builder.append("SUPER=").append(classNode.superName).append('\n');

        List<String> interfaces = new ArrayList<>(classNode.interfaces);
        Collections.sort(interfaces);
        for (String iface : interfaces)
            builder.append("IF=").append(iface).append('\n');

        List<FieldNode> fields = new ArrayList<>(classNode.fields);
        fields.sort((a, b) -> (a.name + ":" + a.desc).compareTo(b.name + ":" + b.desc));
        for (FieldNode field : fields)
            builder.append("FIELD=").append(field.name).append(':').append(field.desc).append('\n');

        ClassFingerprint fingerprint = new ClassFingerprint();

        List<MethodNode> methods = new ArrayList<>(classNode.methods);
        methods.sort((a, b) -> (a.name + a.desc).compareTo(b.name + b.desc));
        for (MethodNode method : methods) {
            if ((method.access & Opcodes.ACC_SYNTHETIC) != 0)
                continue;
            if ((method.access & Opcodes.ACC_BRIDGE) != 0)
                continue;
            String methodKey = method.name + method.desc;
            // 先构建方法块，避免两次遍历指令序列
            StringBuilder methodBlock = new StringBuilder();
            appendTryCatchBlocks(methodBlock, method);
            appendInstructions(methodBlock, method);
            String methodText = methodBlock.toString();
            fingerprint.methodHashes.put(methodKey,
                    Util.sha256(methodText.getBytes(StandardCharsets.UTF_8)));

            builder.append("METHOD=").append(methodKey).append('\n');
            builder.append(methodText);
        }

        fingerprint.normalized = builder.toString();
        fingerprint.logicHash = Util.sha256(fingerprint.normalized.getBytes(StandardCharsets.UTF_8));
        return fingerprint;
    }

    static void appendTryCatchBlocks(StringBuilder builder, MethodNode method) {
        if (method.tryCatchBlocks == null)
            return;
        List<String> sorted = new ArrayList<>();
        for (Object obj : method.tryCatchBlocks) {
            TryCatchBlockNode block = (TryCatchBlockNode) obj;
            sorted.add(block.type != null ? block.type : "(catch-all)");
        }
        Collections.sort(sorted);
        for (String type : sorted)
            builder.append("TRYCATCH=").append(type).append('\n');
    }

    static void appendInstructions(StringBuilder builder, MethodNode method) {
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode insn = (AbstractInsnNode) method.instructions.get(i);
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode)
                continue;

            builder.append(insn.getOpcode());

            if (insn instanceof MethodInsnNode) {
                MethodInsnNode x = (MethodInsnNode) insn;
                builder.append('|').append(x.owner).append('|').append(x.name).append('|').append(x.desc);
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode x = (FieldInsnNode) insn;
                builder.append('|').append(x.owner).append('|').append(x.name).append('|').append(x.desc);
            } else if (insn instanceof TypeInsnNode) {
                builder.append('|').append(((TypeInsnNode) insn).desc);
            } else if (insn instanceof LdcInsnNode) {
                builder.append('|').append(String.valueOf(((LdcInsnNode) insn).cst));
            } else if (insn instanceof IntInsnNode) {
                builder.append('|').append(((IntInsnNode) insn).operand);
            } else if (insn instanceof VarInsnNode) {
                // 不记录 var 槽位号 —— 不同 JDK 分配策略不同
            } else if (insn instanceof IincInsnNode) {
                IincInsnNode x = (IincInsnNode) insn;
                builder.append('|').append(x.incr);
            } else if (insn instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode x = (InvokeDynamicInsnNode) insn;
                builder.append('|').append(x.name).append('|').append(x.desc);
            } else if (insn instanceof JumpInsnNode) {
                builder.append("|JUMP");
            } else if (insn instanceof LookupSwitchInsnNode) {
                List<Integer> sortedKeys = new ArrayList<>(((LookupSwitchInsnNode) insn).keys);
                Collections.sort(sortedKeys);
                builder.append("|LOOKUPSWITCH|").append(sortedKeys);
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode x = (TableSwitchInsnNode) insn;
                builder.append("|TABLESWITCH|").append(x.min).append('|').append(x.max);
            }
            builder.append('\n');
        }
    }

    /**
     * 比较两个指纹中的方法级别差异，返回人类可读的差异描述。
     * 供 Phase 5 AI 提示词使用。
     */
    public static String diffMethodNames(FingerprintPair pair) {
        Map<String, String> oldMethods = pair.oldFp.methodHashes;
        Map<String, String> newMethods = pair.newFp.methodHashes;
        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(oldMethods.keySet());
        allNames.addAll(newMethods.keySet());

        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (String name : allNames) {
            String oldHash = oldMethods.get(name);
            String newHash = newMethods.get(name);
            if (oldHash == null) {
                added.add(name);
            } else if (newHash == null) {
                removed.add(name);
            } else if (!oldHash.equals(newHash)) {
                changed.add(name);
            }
        }

        if (changed.isEmpty() && added.isEmpty() && removed.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【方法级差异定位】以下方法在字节码层面存在差异（其他方法已确认为完全相同）：\n");
        if (!changed.isEmpty()) {
            sb.append("  变更方法: ");
            for (String m : changed)
                sb.append(m).append(", ");
            sb.setLength(sb.length() - 2);
            sb.append('\n');
        }
        if (!added.isEmpty()) {
            sb.append("  新增方法: ");
            for (String m : added)
                sb.append(m).append(", ");
            sb.setLength(sb.length() - 2);
            sb.append('\n');
        }
        if (!removed.isEmpty()) {
            sb.append("  删除方法: ");
            for (String m : removed)
                sb.append(m).append(", ");
            sb.setLength(sb.length() - 2);
            sb.append('\n');
        }
        sb.append("请重点审查以上方法的逻辑差异，其他方法无需关注。\n");
        return sb.toString();
    }
}
