# JavaBuildDiff — 审计级 Java 产物批量比较工具

[![Java](https://img.shields.io/badge/Java-8%2B-blue)](https://www.oracle.com/java/)

## 简介

输入两份产物目录（class + 资源文件），自动分层过滤，区分**编译器版本差异**和**源码逻辑变更**。典型场景：编译器升级、构建环境迁移、源码追溯审计、CI 产物一致性校验。

## 核心思路 —— 五层滤网流水线

```
                                    ┌─────────────────────────┐
                                    │     扫描 class + 资源    │
                                    │   旧版 N 个 | 新版 M 个   │
                                    └───────────┬─────────────┘
                                                │
                          ┌─────────────────────┴─────────────────────┐
                          │                                           │
               ┌──────────▼──────────┐                   ┌────────────▼────────────┐
               │   Phase 1: 资源文件   │                   │  Phase 2: class SHA-256   │
               │                     │                   │                          │
               │  Layer 1: 原始 SHA-256│                   │  O(1) 排除字节完全一致      │
               │   一致 → 跳过         │                   │  一致 → 02-*-match.txt    │
               │   差异 ↓              │                   │  差异 ↓                   │
               │  Layer 2: 归一化空白   │                   └────────────┬────────────┘
               │   \s+→空格,去空行     │                                │
               │   一致 → 跳过(空白差异) │                   ┌────────────▼────────────┐
               │   差异 → 送 Phase 5   │                   │   Phase 3: ASM 结构指纹   │
               └──────────────────────┘                   │                          │
                                                          │  Debug/槽位号/声明顺序     │
                                                          │  一致 → 03-*-match.txt    │
                                                          │  差异 ↓ + 方法级定位       │
                                                          └────────────┬────────────┘
                                                                       │
                                                          ┌────────────▼────────────┐
                                                          │ Phase 4: CFR 反编译+归一化│
                                                          │                          │
                                                          │  并行反编译→去注释/导包    │
                                                          │  一致 → 04-*-match.txt    │
                                                          │  差异 ↓ (失败回退ASM)      │
                                                          └────────────┬────────────┘
                                                                       │
                                                          ┌────────────▼────────────┐
                                                          │ Phase 5: AI 审查(DeepSeek)│
                                                          │                          │
                                                          │  异步并发·增量续跑·抽检    │
                                                          │  class差异+资源差异统一送审  │
                                                          │  一致 → 05-ai-match.txt   │
                                                          │  不同 → 05-ai-diff.txt    │
                                                          │  AI日志 → report/ai/      │
                                                          └────────────┬────────────┘
                                                                       │
                                                          ┌────────────▼────────────┐
                                                          │       汇总报告           │
                                                          │  summary.txt report.html │
                                                          │  report.csv  details/    │
                                                          └─────────────────────────┘
```

**信任度排序**：SHA-256 一致 > ASM 指纹一致 > 反编译文本一致 > AI 验证一致 > 逻辑不同（需人工审查）

## 输出

```
report/
├── report.html                        # 交互式报告（搜索/筛选/排序/全屏逐块对比/AI报告折叠）
├── report.csv                         # Excel 可直接打开（UTF-8 BOM）
├── summary.txt                        # 文字汇总
├── 01-resource-{match,diff,normalized-match,error}.txt
├── 02-sha256-{match,diff,error}.txt
├── 03-asm-{match,diff,error}.txt
├── 04-decompiled-{match,diff,error}.txt
├── 05-ai-{match,diff}.txt
├── details/                           # 每个差异文件的反编译源码逐行对比 + JS 懒加载数据
└── ai/                                # AI 每轮对话原始日志
```

## 快速开始

### 1. 准备产物目录

```
project/old/   ← 旧版产物（class + 资源文件）
project/new/   ← 新版产物（class + 资源文件）
```

### 2. 配置

在运行目录放置 `config.properties`（仅需覆盖默认值）：

```properties
# 目录配置（均有默认值，省略亦可）
#old.dir=project/old
#new.dir=project/new
#report.dir=report
#threads=0                 # 0=自动检测
#decompiler=cfr            # cfr(推荐，线程安全) / procyon(需全局锁)

# AI 审查（可选）
ai.enabled=false
#ai.api.key=sk-xxx
#ai.base.url=https://api.deepseek.com/v1
#ai.model=deepseek-v4-flash
#ai.concurrency=32
#ai.sample.rate=5%         # 支持百分数(5%)或小数(0.05)
```

### 3. 运行

```bash
# 方式 A：IDE 中打开 JavaBuildDiff.java，右键 Run main()
# 方式 B：命令行
mvn -s settings.xml clean package -DskipTests
java -jar target/class-comparator-1.0.0.jar
```

产物 `target/class-comparator-1.0.0.jar` 是 fat jar（含 ASM + CFR + Procyon + OkHttp），可直接拷贝到任意机器运行。`config.properties` 放在 jar 同级目录即可。

## 核心能力

### 多层滤网自动过滤

| 滤网 | 目标 | 过滤内容 |
|------|------|----------|
| SHA-256 | class + 资源 | 字节完全一致（无条件可信） |
| 资源归一化 | 资源文件 | 仅换行/空格/缩进差异 |
| ASM 指纹 | class | Debug 信息、槽位号、声明顺序、synthetic/bridge 方法 |
| 反编译归一化 | class | 注释、导包、空白、enum 隐式方法 |
| AI 审查 | class + 资源 | 最终判定：等价 / 逻辑不同 |

### 方法级差异定位

Phase 3 的 ASM 指纹包含每个方法的独立 SHA-256，能精确到哪些方法变更、哪些新增/删除。这些信息注入 Phase 5 的 AI 提示词中，让 LLM 聚焦变更方法而非整个 class。

### AI 增强特性

- **异步并发**：Semaphore + CompletableFuture + OkHttp 非阻塞 IO，可配置并发数
- **增量续跑**：Phase 5 每验证一个类即落盘，中断后自动跳过已完成项
- **抽检模式**：支持百分数或小数配置抽检比例，固定种子保证结果可复现
- **结果缓存**：按 `SHA-256(旧+新)` 缓存 AI 响应，相同比对秒级返回
- **原子写入**：AI 日志 temp + atomic move，进程 crash 不产生半截文件

### HTML 交互式报告

- 搜索：按文件名或目录路径搜索（`data-name` 支持 `/` 分隔符）
- 筛选：按分类标签过滤
- 排序：点击表头排序
- 全屏对比：点击文件名打开双栏逐行对比面板
- 差异块导航：参照 Beyond Compare，连续同类型差异行合并为块，Shift+↑↓ 跳转
- 空白折叠：`collapseBlanks()` 压缩连续空行，忽略空行数量差异
- AI 报告折叠：仅 AI 判定为 NO 时展示，首行 "NO" 标识自动截除
- 懒加载详情：每个类独立 `.js` 文件，点击时动态注入（绕过 file:// CORS）

## 架构

`JavaBuildDiff.java` 为启动类（默认包），7 个支持类在 `classcomparator` 包中：

```
src/main/java/
├── JavaBuildDiff.java          ← 启动类 main()
└── classcomparator/
    ├── Config.java              ← 配置加载 + 常量
    ├── PhaseResults.java        ← 跨阶段共享状态
    ├── Util.java                ← SHA-256/文件/转义工具
    ├── Fingerprint.java         ← ASM 指纹 + 方法级差异
    ├── Decompiler.java          ← CFR/Procyon + 源码归一化
    ├── AiReviewer.java          ← AI 异步客户端
    └── Reporter.java            ← summary/CSV/HTML 报告
```

## 自动忽略的差异

- JDK 版本间的编译器优化差异
- Debug 信息（行号表、局部变量表、栈帧）
- 变量槽位分配差异
- 字段 / 方法 / try-catch 声明顺序差异
- switch 分支排序差异
- 编译器生成的 synthetic / bridge 方法
- enum 隐式方法差异
- 非 ASCII 字符转义差异
- 资源文件仅换行/空格/缩进差异（归一化过滤）

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| ASM | 9.7 | 字节码解析、结构指纹提取 |
| CFR | 0.152 | 默认反编译引擎，线程安全 |
| Procyon | 0.6.0 | 备选反编译引擎 |
| openai-java | 4.41.0 | DeepSeek API 调用 |
| OkHttp | 4.12.0 | AI 异步 HTTP 通信 |
| Maven Shade | 3.2.4 | Fat jar 打包 |
