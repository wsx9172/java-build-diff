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
            AbstractInsnNode instruction = (AbstractInsnNode) method.instructions.get(i);
            if (instruction instanceof LabelNode || instruction instanceof FrameNode
                    || instruction instanceof LineNumberNode)
                continue;

            builder.append(instruction.getOpcode());

            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode methodNode = (MethodInsnNode) instruction;
                builder.append('|').append(methodNode.owner).append('|')
                        .append(methodNode.name).append('|').append(methodNode.desc);
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode fieldNode = (FieldInsnNode) instruction;
                builder.append('|').append(fieldNode.owner).append('|')
                        .append(fieldNode.name).append('|').append(fieldNode.desc);
            } else if (instruction instanceof TypeInsnNode) {
                builder.append('|').append(((TypeInsnNode) instruction).desc);
            } else if (instruction instanceof LdcInsnNode) {
                Object constant = ((LdcInsnNode) instruction).cst;
                if (constant instanceof org.objectweb.asm.Type) {
                    builder.append('|').append(((org.objectweb.asm.Type) constant).getDescriptor());
                } else if (constant instanceof org.objectweb.asm.Handle) {
                    org.objectweb.asm.Handle handle = (org.objectweb.asm.Handle) constant;
                    builder.append('|').append(handle.getOwner()).append('|')
                            .append(handle.getName()).append('|').append(handle.getDesc());
                } else {
                    builder.append('|').append(String.valueOf(constant));
                }
            } else if (instruction instanceof IntInsnNode) {
                builder.append('|').append(((IntInsnNode) instruction).operand);
            } else if (instruction instanceof VarInsnNode) {
                // 不记录 var 槽位号 —— 不同 JDK 分配策略不同
            } else if (instruction instanceof IincInsnNode) {
                IincInsnNode iincNode = (IincInsnNode) instruction;
                builder.append('|').append(iincNode.incr);
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode indyNode = (InvokeDynamicInsnNode) instruction;
                builder.append('|').append(indyNode.name).append('|').append(indyNode.desc);
            } else if (instruction instanceof JumpInsnNode) {
                builder.append("|JUMP");
            } else if (instruction instanceof LookupSwitchInsnNode) {
                List<Integer> sortedKeys = new ArrayList<>(((LookupSwitchInsnNode) instruction).keys);
                Collections.sort(sortedKeys);
                builder.append("|LOOKUPSWITCH|").append(sortedKeys);
            } else if (instruction instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode switchNode = (TableSwitchInsnNode) instruction;
                builder.append("|TABLESWITCH|").append(switchNode.min).append('|').append(switchNode.max);
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
            for (String methodSig : changed)
                sb.append(methodSig).append(", ");
            sb.setLength(sb.length() - 2);
            sb.append('\n');
        }
        if (!added.isEmpty()) {
            sb.append("  新增方法: ");
            for (String methodSig : added)
                sb.append(methodSig).append(", ");
            sb.setLength(sb.length() - 2);
            sb.append('\n');
        }
        if (!removed.isEmpty()) {
            sb.append("  删除方法: ");
            for (String methodSig : removed)
                sb.append(methodSig).append(", ");
            sb.setLength(sb.length() - 2);
            sb.append('\n');
        }
        sb.append("请重点审查以上方法的逻辑差异，其他方法无需关注。\n");
        return sb.toString();
    }
}
