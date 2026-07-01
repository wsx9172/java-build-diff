# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

**JavaBuildDiff** — 审计级 Java 产物批量比较工具。输入两份产物目录（class + 资源文件），通过五阶段过滤器流水线自动区分"字节码相同"、"仅空白/编译器差异"、"真正逻辑不同"。资源文件（XML/HTML/JS/CSS/properties 等）和 class 文件统一覆盖。

## 运行方式

**首选：IDE 直接运行 main 方法**（右键 → Run），无需命令行参数。

**配置方式**：`config.properties` 配置文件（可覆盖代码默认值），代码中类变量作为默认值兜底：

```properties
# config.properties（仅需配置与默认值不同的项）
old.dir=project/old
new.dir=project/new
threads=0                # 0=自动检测
decompiler=cfr           # cfr(线程安全) / procyon(需全局锁)
ai.enabled=false         # 启用 AI 审查
ai.api.key=sk-xxx        # AI 启用时必须配置，代码中无默认值
ai.sample.rate=100%      # 支持百分数(15%)或小数(0.15)
ai.concurrency=64        # AI 异步并发数
```

**备选：命令行打包运行**：
```bash
mvn -s settings.xml clean package -DskipTests
java -jar target/class-comparator-1.0.0.jar
```

产物 `target/class-comparator-1.0.0.jar` 是 fat jar，包含所有依赖。`config.properties` 放在 jar 同级目录。

## 架构

### 目录结构

```
src/main/java/
├── JavaBuildDiff.java          ← 启动类（默认包），main() + 5 阶段流水线
└── classcomparator/            ← 库包（7 个支持类）
    ├── Config.java
    ├── PhaseResults.java
    ├── Util.java
    ├── Fingerprint.java
    ├── Decompiler.java
    ├── AiReviewer.java
    └── Reporter.java
```

### 五阶段流水线

| Phase | 说明 | 滤网 | 输出 |
|-------|------|------|------|
| 1 | 资源文件比对 | Layer 1: 原始 SHA-256 → Layer 2: 归一化空白（`\s+→空格→去空行`） | `01-resource-{match,diff,normalized-match,error}.txt` |
| 2 | class SHA-256 | O(1) 字节哈希，排除完全一致的 class | `02-sha256-{match,diff,error}.txt` |
| 3 | ASM 结构指纹 | 跳过 Debug/Frame/槽位号，排序字段/方法/try-catch/switch-keys | `03-asm-{match,diff,error}.txt` |
| 4 | CFR 反编译+归一化 | 并行反编译→去注释/导包/空白/enum 隐式方法→逐行比对。失败回退 ASM | `04-decompiled-{match,diff,error}.txt` |
| 5 | AI 审查(DeepSeek) | 异步并发·增量续跑·抽检·结果缓存。class + 资源差异统一送审 | `05-ai-{match,diff}.txt` |

### 分类标签

`SHA-256一致` / `ASM指纹一致` / `反编译文本一致` / `AI验证一致` / `逻辑不同` / `仅新版存在(新增)` / `仅旧版存在(删除)` / `工具异常`

信任度排序：SHA-256一致 > ASM指纹一致 > 反编译文本一致 > AI验证一致 > 逻辑不同（需人工）。

### 报告产物

summary.txt / report.html（搜索/筛选/排序/全屏逐块对比/AI报告折叠/懒加载）/ report.csv（UTF-8 BOM）/ details/*.txt + *.js / ai/*.ai.txt

## 关键设计决策

- **配置加载**：`config.properties` 双级加载——classpath 内置模板 (`src/main/resources/`) → 外部文件覆盖。成员变量是默认值，配置文件是覆盖值。API Key 无默认值，启用 AI 必须配置。`Config.class.getResourceAsStream("/config.properties")` 读取 classpath 资源
- **每次运行清空 report**：确保结果干净，无旧缓存残留
- **JDK 8 编译 / JDK 8+ 运行**：pom.xml source/target=8
- **依赖**：ASM 9.7（字节码解析）、CFR 0.152（默认反编译，线程安全）、Procyon 0.6.0（备选反编译，需全局锁）、openai-java 4.41.0（AI API 调用）
- **资源归一化**：`normalizeResourceText()` 每行 `\s+→单空格→trim→删空行`，过滤仅空白差异
- **class 归一化**：`normalizeSource()` 去注释/导包/空白/enum隐式方法，保留方法体逻辑骨架。正则预编译为 `static final Pattern`
- **ASM 指纹**：跳过 Debug/Frame/Label 信息，剥离变量槽位号，排序字段/方法/try-catch/switch-keys。含方法级 SHA-256
- **方法级差异定位**：`diffMethodNames()` 比较新旧指纹中的方法级哈希，生成"变更方法/新增方法/删除方法"清单，注入 Phase 5 AI 提示词
- **AI 提示词**：分离 `AI_SYSTEM_PROMPT` 和用户提示词（class / 资源文件各自独立模板）。class 提示词强调"寻找行为变化的确凿证据"。temperature=0.0，关闭思考模式
- **AI 抽检**：`parseSampleRate()` 支持百分数和小数格式，[0,1] 截断保留两位小数。固定种子 seed=42
- **AI 并发**：Semaphore 控制并发数，`supplyAsync` 用专用 `ExecutorService`（避免 ForkJoinPool.commonPool 瓶颈），OkHttp 异步 IO
- **AI 续跑**：每 50 条 YES 批量 flush `05-ai-match.txt`，中断后重跑自动从文件恢复 `alreadyDone` set
- **AI 缓存**：`ConcurrentHashMap` 以 `sha256(old+"\0"+new)` 为 key，相同比对秒级返回
- **AI 结果解析**：`parseAiVerdict()` 首行检查 + 全文 `\bYES\b` `\bNO\b` 单词边界匹配兜底
- **AI 超时**：`CompletableFuture.allOf().get(timeout)` + 真正的 `future.cancel(true)` 取消
- **HTML 懒加载**：每个差异文件生成独立 `.js`，点击时动态 `<script>` 注入（绕过 file:// CORS）
- **HTML 差异块**：Beyond Compare 风格，连续同类型行合并为 diff block。`collapseBlanks()` 折叠连续空行
- **HTML AI 报告**：仅 NO 时展示折叠面板，首行 "NO" 标识自动截除
- **HTML 搜索**：`data-name` 格式 `包路径/ 类名.java`，支持按目录搜索
- **内联 SVG favicon**：Data URI 嵌入，无外部文件依赖
- **控制台日志对齐**：零补齐计数器 + 固定宽度文件名列
- **原子写入**：AI 日志 `.tmp` → `Files.move(ATOMIC_MOVE)`
- **信号量防泄漏**：`supplyAsync` 外包 `try-catch(RejectedExecutionException)`

## 注意事项

- `project/` 目录是测试数据，不是源码
- 不要在 pom.xml 声明 `maven-site/deploy/install-plugin`——Maven 启动时就会解析，没网就报错
- 不要给 AI 相关配置加 `final` 修饰符——javac 编译期常量折叠，产出死代码警告
- Phase 5 的 `supplyAsync` 必须传专用 `ExecutorService` 作为第二参数，否则走 `ForkJoinPool.commonPool()`
- Phase 5 AI 日志写资源文件时用 `toResFileName()`（保留扩展名），class 用 `toFileName()`（替换 `.` 为 `_`）。两者不能混用，否则 Reporter 读不到
- 启动类 `JavaBuildDiff.java` 在默认包，不在 `classcomparator` 包中，引用库类通过 `import classcomparator.*`
- `PhaseResults` 所有字段为 `public static`，跨阶段共享。计数器在 Phase 中写入，Reporter 中读取
- `FINGERPRINTS` 是 `ConcurrentHashMap`（Phase 4 多线程读，Phase 3 单线程写）
- `pom.xml` mainClass 为 `JavaBuildDiff`（无包名）
