# 致谢与数据来源

本项目的价值主要来自**别人公开的数据和工具**。这里逐一列出，并说明每一样具体用在了哪里——含混的"感谢所有开源项目"没有意义。

## 一、药代动力学与疗效数据（最重要）

本项目所有药物参数和疗效数字都**不是我自己估的**，全部取自公开说明书与已发表论文，并在代码里逐条保留了 `claim` / `url` / `quote` 三元组。核验结果存放在 `research/`。

### FDA 说明书

| 来源 | 用到的内容 | 代码位置 |
|---|---|---|
| **ZEPBOUND (tirzepatide) 处方信息**，NDA 217806，§12.3 药代动力学 | 半衰期约 5 天（120 h）、tmax 中位 24 h（范围 8–72）、生物利用度 80%、4 周达稳态、稳态蓄积比 1.75 | `glpmate/drugs.py` |
| **Ozempic (semaglutide) 处方信息** | 半衰期约 1 周、tmax 1–3 天 | `glpmate/drugs.py` |

说明书是公共领域的政府出版物（美国联邦政府作品不受版权保护），可以自由引用。

### III 期临床试验（SURMOUNT / STEP 系列）

用于"疗效参考"页面与教练的科普上下文，包含减重幅度、达标比例（≥5% / ≥10% / ≥15% / ≥20%）、胃肠道不良反应发生率：

- SURMOUNT-1 —— 替尔泊肽 15 mg 72 周 −20.9%（vs 安慰剂 −3.1%）
- SURMOUNT-2 —— 合并 2 型糖尿病人群
- SURMOUNT-3 / SURMOUNT-4 —— 强化生活方式后维持、停药后反弹
- 以及体成分分析（瘦体重约占减重量的 25%）

这些数字的**原文引用**（含 DOI / PMID）保存在 `research/glp1_weight_efficacy_verified.json`。论文的**数据与事实**可以引用，但论文正文受版权保护，所以本仓库只存引用与数字，不转载全文。

### 临床标准

- **中国成人超重与肥胖判定标准 WS/T 428-2013** —— BMI 分级切点（超重 ≥24、肥胖 ≥28）。采用中国标准而非 WHO 的 25/30，因为门诊和体检报告跟的是前者。

## 二、数据格式

- **华为运动健康 HiTrack 轨迹格式** —— `glpmate/hitrack.py` 解析的是该 App 导出的**明文轨迹文件**（`tp=h-r` / `tp=lbs` / `tp=sec` 三种记录）。格式是通过分析**用户自己导出的文件**得出的，不涉及任何逆向工程、注入或解密。

## 三、软件依赖

### 后端（Python）

核心**只用标准库**。唯一可选依赖：

| 依赖 | 许可证 | 用途 | 不用会怎样 |
|---|---|---|---|
| [Pillow](https://python-pillow.org/) | MIT-CMU | 拍照识餐前把图片压到 1024 px | 图片原样上传，功能不受影响 |

### Android 客户端

| 依赖 | 许可证 | 用途 |
|---|---|---|
| [Chaquopy](https://chaquo.com/chaquopy/) 17.0.0 | MIT（12.0.1 版起） | 在 APK 内嵌 CPython 3.12，使后端无需 Termux 即可运行 |
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) | Apache-2.0 | 界面与生命周期 |
| [Kotlin](https://kotlinlang.org/) 标准库 | Apache-2.0 | 语言运行时 |
| JUnit 4 | EPL-1.0 | 仅测试，不随发布物分发 |

以上许可证均与 GPL-3.0 兼容。

## 四、图标与吉祥物：Tux

应用图标与吉祥物使用 **Tux**（企鹅），由 **[Larry Ewing](https://en.wikipedia.org/wiki/Tux_(mascot))** 于 1996 年用 GIMP 创作，是 Linux 内核的品牌角色。

### 授权原文与我们的履行

Larry Ewing 的原始声明是：

> Permission to use and/or modify this image is granted provided you acknowledge me
> lewing@isc.tamu.edu and The GIMP if someone asks.

**这不是一个标准开源许可证**（不是 CC、不是 MIT、也不是 GPL），而是一段自定义的许可声明。它的条件是"**被问起时致谢**"，而不是要求随附许可证文本。我们据此履行：

- 本文件即为致谢，随仓库与发布物一同分发。
- 使用了 [Wikimedia Commons 上的原始素材](https://commons.wikimedia.org/wiki/File:Tux.svg)（`Tux.svg`），**没有改动图形本身**——只做了等比缩放。
  （原文写的是 "use and/or modify"，但保留原图更省事，也避免产生衍生作品带来的额外说明义务。）
- 图标背景层由本项目添加（浅色渐变）。Tux 本体未被重绘、改色或变形。

### 需要知道的两件事

1. **Tux 是 Linux 的象征。** 把它用在一个健康管理应用上，可能让部分用户以为这是 Linux 工具。选择它是刻意的（见下），但这是个真实的取舍。
2. **Tux 是 Linux 内核的"品牌角色"（brand character），不是官方 logo。** Tux 当年并没有赢得那三场 Linux logo 竞赛，因此 Linux 官方对它的定位是品牌角色。这反而使它的使用限制比正式商标宽松。

### 为什么选 Tux

Linus Torvalds 当年选企鹅的理由，和这个应用的场景意外地契合——他想要一个"**刚吃饱、心满意足地坐着**的胖企鹅"（原文见 Wikipedia 条目：*a slightly fat penguin sitting down after having eaten a great meal*）。

对一个 GLP-1 用药者的记录应用来说，这个意象本身就是个温和的提醒；而且 Tux 是自由软件社区最被认得的形象之一，与本项目的 GPL-3.0 定位一致。

## 五、设计与思路上的影响

- **Arris**（一个开源的音乐播放器 Android 项目）—— 本项目在**架构取向上**参考了它：原生 Kotlin + Compose 做界面、后端作为**独立进程/服务**通过 HTTP 通信，而不是把逻辑塞进 WebView。本项目**没有复制它的任何代码**，两者业务领域也完全不同。它是一个 GPL-3.0 兼容的开源项目。

## 六、需要明确排除的

以下**没有**在本项目中使用，列出来是为了避免误解：

- **GLPMate 原版 App 的代码或资源** —— 本项目是独立实现的，没有反编译、提取或复用其任何代码或资产。其中的药代算法是**根据公开说明书参数从零推导**的（一室模型 + 一阶吸收的 Bateman 方程），并非复制原版的实现。
- **任何逆向工程、注入或解密手段** —— 本项目不 hook、不注入、不绕过任何 App 的保护机制。

## 七、如果你发现了遗漏

如果这里漏掉了应当致谢的来源，请开 issue 指出——**这是本项目认真对待的事**。药代参数若与说明书不符，属于 bug，请一并附上出处。
