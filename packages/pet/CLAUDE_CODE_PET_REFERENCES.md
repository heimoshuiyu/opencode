# Claude Code 宠物系统（Buddy/Companion）设计参考

> 本文档是对 Claude Code 源码中宠物系统的完整逆向分析，供 OpenCode Pet 项目参考借鉴。
>
> 源码位置：Claude Code `src/buddy/` 目录（6 个文件）+ 分散在 REPL/PromptInput/attachments 等模块的集成代码。
>
> 注意：`src/buddy/observer.ts`（评论生成的核心逻辑）和 `commands/buddy/`（`/buddy` 命令实现）在源码快照中缺失，部分内容为基于上下文的推断。

---

## 1. 系统概述

Claude Code 的宠物系统是一个**终端内的虚拟宠物伴侣**，名为 **Buddy**（内部代号）/ **Companion**（代码命名）。它以 ASCII art 精灵的形式坐在用户输入框旁边，偶尔通过气泡发表评论。

**与 OpenCode Pet 的核心区别**：

| 维度 | Claude Code Buddy | OpenCode Pet |
|---|---|---|
| 形态 | ASCII art 文字精灵（12×5 像素级） | Live2D 模型（桌面悬浮窗） |
| 位置 | 终端输入框右侧 | 桌面独立窗口 |
| 交互深度 | 观察对话 + 偶尔评论 | SSE 事件驱动状态动画 |
| 个性化 | 确定性生成 + AI 命名/性格 | 固定模型行为 |
| AI 对话 | 有（独立观察者模型） | 暂无 |
| 养成机制 | 无（纯装饰） | 暂无 |

---

## 2. 宠物生成：确定性 + AI 混合

每个用户的宠物由两部分组成，这是 Claude Code 最值得借鉴的设计之一：

### 2.1 Bones（骨架）—— 确定性生成，不持久化

所有外观属性从 `hash(userId + SALT)` 确定性推导，**每次启动重新计算，不存储**。

```typescript
// src/buddy/companion.ts
const SALT = 'friend-2026-401'

function roll(userId: string): Roll {
  const key = userId + SALT
  return rollFrom(mulberry32(hashString(key)))
}
```

**设计意图**：
- 用户无法通过编辑配置伪造稀有度
- 物种列表变更不会破坏已有宠物
- 无需服务器端状态同步
- 同一用户每次看到的都是同一个宠物

**Bones 包含**：
- `rarity` — 稀有度
- `species` — 物种
- `eye` — 眼睛样式
- `hat` — 帽子
- `shiny` — 是否闪亮（1% 概率）
- `stats` — 5 个属性值

**种子随机数生成器**：使用 Mulberry32（一个极小的 PRNG，种子来自字符串哈希）：

```typescript
function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return function () {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
```

> **OpenCode Pet 可借鉴**：可以为每个 OpenCode 用户 ID 确定性地生成宠物个性参数（表情偏好、活跃度、评论风格等），无需后端存储。

### 2.2 Soul（灵魂）—— AI 生成，持久化

宠物的名字和性格由模型在孵化时一次性生成，存储在用户配置中：

```typescript
// src/buddy/types.ts
type CompanionSoul = {
  name: string        // AI 生成的名字
  personality: string // AI 生成的性格描述
}

type StoredCompanion = CompanionSoul & { hatchedAt: number }

// 全局配置中存储
// config.companion: StoredCompanion
```

**运行时合并**：

```typescript
// src/buddy/companion.ts
function getCompanion(): Companion | undefined {
  const stored = getGlobalConfig().companion  // 从配置读 soul
  if (!stored) return undefined
  const { bones } = roll(companionUserId())   // 从 ID 重新算 bones
  return { ...stored, ...bones }              // 合并，bones 覆盖
}
```

> **OpenCode Pet 可借鉴**：可以引入"宠物命名/性格"概念——首次启动时让 AI 根据用户偏好生成一个名字和简短性格描述，影响后续评论风格。

---

## 3. 物种系统（18 种）

每个物种有 3 帧 ASCII art 动画（5 行高 × 12 字符宽），用于 idle 时的 fidget 动画。

### 3.1 完整物种列表

```
duck（鸭子）    goose（鹅）      blob
cat（猫）       dragon（龙）     octopus（章鱼）
owl（猫头鹰）   penguin（企鹅）  turtle（乌龟）
snail（蜗牛）   ghost（幽灵）    axolotl（蝾螈）
capybara（水豚） cactus（仙人掌） robot（机器人）
rabbit（兔子）   mushroom（蘑菇） chonk（胖墩）
```

### 3.2 精灵渲染示例

每个物种有 3 帧动画。以鸭子为例：

```
帧 0（静止）:           帧 1（尾巴摇）:        帧 2（张嘴）:
                            __                      __
    __                      <({E} )___              <({E} )___
  <({E} )___                 (  ._>                  (  .__>
   (  ._>                    `--´~                    `--´
    `--´
```

`{E}` 在渲染时替换为宠物实际的眼睛字符。

### 3.3 窄屏折叠：文字表情

终端宽度 < 100 列时，精灵折叠为一行文字表情：

```typescript
// src/buddy/sprites.ts — renderFace()
switch (bones.species) {
  case 'cat':     return `=${eye}ω${eye}=`
  case 'dragon':  return `<${eye}~${eye}>`
  case 'ghost':   return `/${eye}${eye}\\`
  case 'axolotl': return `}${eye}.${eye}{`
  case 'robot':   return `[${eye}${eye}]`
  // ... 每个物种都有独特的文字表情
}
```

> **OpenCode Pet 可借鉴**：虽然 OpenCode Pet 使用 Live2D 不需要 ASCII art，但"窄屏降级"的思路可以借鉴——比如当窗口缩小到最小尺寸时，切换为简化的表情/图标模式。

---

## 4. 稀有度系统

类似游戏的抽卡机制，加权随机从用户 ID 哈希中抽取：

### 4.1 稀有度分布

```typescript
const RARITY_WEIGHTS = {
  common:    60,  // 60% — ★
  uncommon:  25,  // 25% — ★★
  rare:      10,  // 10% — ★★★
  epic:       4,  // 4%  — ★★★★
  legendary:  1,  // 1%  — ★★★★★
}
```

### 4.2 稀有度的影响

**属性值下限**：

```typescript
const RARITY_FLOOR = {
  common:    5,
  uncommon: 15,
  rare:     25,
  epic:     35,
  legendary: 50,
}
```

**帽子**：普通品质无帽子，其他品质随机分配。

**UI 颜色**（对应终端主题色）：

```typescript
const RARITY_COLORS = {
  common:    'inactive',    // 灰色
  uncommon:  'success',     // 绿色
  rare:      'permission',  // 黄色
  epic:      'autoAccept',  // 蓝色
  legendary: 'warning',     // 橙色
}
```

### 4.3 抽取逻辑

```typescript
function rollRarity(rng: () => number): Rarity {
  const total = Object.values(RARITY_WEIGHTS).reduce((a, b) => a + b, 0) // 100
  let roll = rng() * total
  for (const rarity of RARITIES) {
    roll -= RARITY_WEIGHTS[rarity]
    if (roll < 0) return rarity
  }
  return 'common'
}
```

> **OpenCode Pet 可借鉴**：可以为宠物引入稀有度概念，影响模型选择（稀有宠物解锁更多动画/皮肤/评论风格），增加趣味性和收集动力。

---

## 5. 属性系统（Stats）

### 5.1 五大属性

```typescript
const STAT_NAMES = [
  'DEBUGGING',   // 调试能力
  'PATIENCE',    // 耐心
  'CHAOS',       // 混乱度
  'WISDOM',      // 智慧
  'SNARK',       // 毒舌程度
] as const
```

### 5.2 生成规则

- 选一个**主属性**（高值）：`floor + 50 + rand(30)`，上限 100
- 选一个**废属性**（低值）：`floor - 10 + rand(15)`，下限 1
- 其余属性：`floor + rand(40)`
- 稀有度决定 `floor`（普通 5 → 传说 50）

**示例**：一只 Rare（稀有）的猫，STAT_NAMES = [WISDOM(主), CHAOS(废), ...]
- WISDOM ≈ 25 + 50 + 20 = 95
- CHAOS ≈ max(1, 25 - 10 + 5) = 20
- 其余 ≈ 25 + 20 = 45

> **OpenCode Pet 可借鉴**：属性值可以影响宠物行为——高 PATIENCE 的宠物等待用户操作时更平静，高 SNARK 的宠物评论更毒舌，高 CHAOS 的宠物偶尔做出出人意料的动作。

---

## 6. 外观定制

### 6.1 眼睛（6 种）

```typescript
const EYES = ['·', '✦', '×', '◉', '@', '°']
```

### 6.2 帽子（8 种）

```typescript
const HATS = [
  'none',       // 无
  'crown',      // \^^^/
  'tophat',     // [___]
  'propeller',  // -+-
  'halo',       // (   )
  'wizard',     // /^\
  'beanie',     // (___)
  'tinyduck',   // ,>  (一只小鸭子坐在头上)
]
```

帽子只出现在非静止帧的精灵顶部。普通品质的宠物强制 `hat: 'none'`。

### 6.3 闪亮标记

```typescript
shiny: rng() < 0.01  // 1% 概率
```

闪亮状态的具体视觉效果在源码中未明确体现（可能影响动画速度或颜色闪烁）。

> **OpenCode Pet 可借鉴**：可以为 Live2D 模型添加装饰品叠加层（帽子、光环、翅膀等），闪亮宠物可以有特殊的粒子效果/光晕。

---

## 7. 动画系统

### 7.1 时间控制

```typescript
const TICK_MS = 500  // 500ms 一次 tick
```

### 7.2 Idle 序列

```typescript
// 15 步循环，大部分时间静止（帧 0），偶尔 fidget（帧 1-2），稀有眨眼（-1）
const IDLE_SEQUENCE = [0, 0, 0, 0, 1, 0, 0, 0, -1, 0, 0, 2, 0, 0, 0]
```

- 帧 0：静止（8/15 概率）
- 帧 1：fidget 1（1/15 概率）
- 帧 2：fidget 2（1/15 概率）
- -1：在帧 0 上眨眼（将眼睛字符替换为 `-`）

### 7.3 兴奋状态

当宠物有评论（reaction）或被抚摸（petting）时，切换为快速循环所有帧：

```typescript
if (reaction || petting) {
  spriteFrame = tick % frameCount  // 每帧都切换，不再遵循 IDLE_SEQUENCE
}
```

### 7.4 眨眼效果

```typescript
// 眨眼时将所有眼睛字符替换为 '-'
const body = renderSprite(companion, spriteFrame)
  .map(line => blink ? line.replaceAll(companion.eye, '-') : line)
```

> **OpenCode Pet 可借鉴**：idle 动画不应该只是单一循环，而应该有"大部分时间静止 + 偶尔小动作 + 极稀有特殊动作"的节奏，让宠物看起来更自然。

---

## 8. 气泡评论系统（Companion Observer）⭐

这是 Claude Code 宠物系统最核心也最值得借鉴的部分。

### 8.1 触发机制

每轮主对话**完全结束后**，异步触发观察者：

```typescript
// src/screens/REPL.tsx 第 2804-2809 行
// 在 query() 循环结束后：
if (feature('BUDDY')) {
  void fireCompanionObserver(messagesRef.current, reaction => setAppState(prev =>
    prev.companionReaction === reaction ? prev : {
      ...prev,
      companionReaction: reaction
    }
  ));
}
```

**关键设计**：
- `void`（不等待）—— 异步执行，不阻塞主流程
- 输入是**完整的对话消息历史** `messagesRef.current`
- 回调写入全局状态 `companionReaction`（一个纯字符串）

### 8.2 Observer 的实现（推断）

`src/buddy/observer.ts` 在源码快照中缺失，但可从上下文推断：

**使用 `sideQuery()` 工具**（Claude Code 的轻量 API 调用封装）：

```typescript
// src/utils/sideQuery.ts — 旁路 API 调用工具
// 特点：默认 max_tokens: 1024，支持指定任意 model
// 被用于：权限分类器、会话搜索、记忆检索等场景
```

**推断的 Observer 工作流**：

```
1. 读取宠物属性
   └─ getCompanion() → { name, species, personality, stats }

2. 从对话历史中提取最近上下文
   └─ 取最后几条消息（或摘要）

3. 构造 prompt
   ├─ system: 宠物人设 + 评论指令（受 stats 影响）
   ├─ messages: 对话摘要
   └─ max_tokens: ~100（评论很短）

4. 通过 sideQuery() 调用小模型（推测为 Haiku 级别）
   └─ 成本低、延迟低，适合每轮都触发

5. 返回纯文本评论字符串
```

### 8.3 评论的 UI 展示

```typescript
const BUBBLE_SHOW = 20  // 20 ticks → 约 10 秒
const FADE_WINDOW = 6   // 最后 6 ticks（约 3 秒）逐渐变暗

// 气泡显示流程：
// 1. 评论出现 → SpeechBubble 组件渲染
// 2. 正常显示约 7 秒（文字斜体，带边框颜色=稀有度颜色）
// 3. 最后 3 秒逐渐变暗（颜色切换为 'inactive'）
// 4. 自动消失
```

**气泡布局自适应**：

| 终端宽度 | 表现 |
|---|---|
| ≥ 100 列 | 完整精灵 + 圆角气泡在精灵左侧，`──` 尾巴指向精灵 |
| < 100 列 | 折叠为一行文字表情 + 截断到 24 字符的短评论 |
| 全屏模式 | 气泡以浮动 overlay 叠加在聊天区域上方（不受 overflow:hidden 裁剪） |

### 8.4 用户滚动时自动消失

```typescript
// 滚动 = 用户在读气泡下方的内容
if (feature('BUDDY')) {
  setAppState(prev => prev.companionReaction === undefined ? prev : {
    ...prev,
    companionReaction: undefined  // 立即清除
  });
}
```

### 8.5 主模型的 System Prompt 注入

为了让主模型（Claude）知道宠物的存在，且正确处理用户与宠物对话的场景：

```typescript
// src/buddy/prompt.ts
function companionIntroText(name: string, species: string): string {
  return `# Companion

A small ${species} named ${name} sits beside the user's input box
and occasionally comments in a speech bubble. You're not ${name} —
it's a separate watcher.

When the user addresses ${name} directly (by name), its bubble will answer.
Your job in that moment is to stay out of the way: respond in ONE line or less,
or just answer any part of the message meant for you. Don't explain that
you're not ${name} — they know. Don't narrate what ${name} might say —
the bubble handles that.`
}
```

这段文字作为 `companion_intro` 附件，在每轮对话的首次出现时注入到系统提示中（同一宠物名只注入一次，避免重复）。

> **OpenCode Pet 可借鉴**：这是最有价值的参考点。OpenCode Pet 可以：
> 1. 在 OpenCode 主模型的 system prompt 中注入宠物信息
> 2. 实现一个独立的"宠物评论"旁路调用（小模型、短输出）
> 3. 用户可以直接和宠物说话（通过名字），主模型主动让路
> 4. 评论显示为桌面宠物上方的浮动气泡，定时消失

---

## 9. 用户交互

### 9.1 `/buddy` 命令

通过 `commands/buddy/` 实现（源码缺失），包含：
- `/buddy` — 孵化/管理宠物
- `/buddy pet` — 抚摸宠物

### 9.2 抚摸效果

```typescript
const PET_BURST_MS = 2500  // 爱心飘浮 2.5 秒

// 爱心动画序列（5 帧，向上飘散）
const PET_HEARTS = [
  '   ♥    ♥   ',
  '  ♥  ♥   ♥  ',
  ' ♥   ♥  ♥   ',
  '♥  ♥      ♥ ',
  '·    ·   ·  ',
]
```

抚摸时：
- 爱心从宠物上方飘出（5 帧，每帧 500ms）
- 宠物切换到兴奋动画（快速循环所有帧，而非 idle 序列）
- 2.5 秒后恢复正常

### 9.3 输入框彩虹高亮

用户在终端输入 `/buddy` 时，文字以彩虹色渲染（其他命令无此效果）：

```typescript
// src/components/PromptInput/PromptInput.tsx
// Rainbow for /buddy
for (const trigger of buddyTriggers) {
  for (let i = trigger.start; i < trigger.end; i++) {
    highlights.push({
      start: i, end: i + 1,
      color: getRainbowColor(i - trigger.start),
      shimmerColor: getRainbowColor(i - trigger.start, true),
      priority: 10,
    })
  }
}
```

### 9.4 底部导航栏集成

宠物在输入框底部的 footer 导航栏中占一个位置（`companion`），用户可以用方向键选中后按 Enter 触发 `/buddy` 命令：

```typescript
// PromptInput.tsx
case 'companion':
  if (feature('BUDDY')) {
    selectFooterItem(null);
    void onSubmit('/buddy');
  }
  break
```

### 9.5 静音控制

```typescript
// src/utils/config.ts
companionMuted?: boolean  // 全局配置项

// 所有宠物显示逻辑都检查此标志
if (!companion || getGlobalConfig().companionMuted) return null
```

> **OpenCode Pet 可借鉴**：抚摸互动（点击宠物触发爱心 + 特殊动画）已经在 PLAN.md 的 P2 计划中。彩虹效果可用于 OpenCode 的终端 UI 中。

---

## 10. UI 布局集成

### 10.1 宽度计算

```typescript
const MIN_COLS_FOR_FULL_SPRITE = 100  // 完整精灵需要的最小列数
const SPRITE_BODY_WIDTH = 12          // 精灵宽度

// 宠物占用的列数（输入框需要相应缩窄）
function companionReservedColumns(
  terminalColumns: number,
  speaking: boolean
): number {
  if (!feature('BUDDY')) return 0
  const companion = getCompanion()
  if (!companion || getGlobalConfig().companionMuted) return 0
  if (terminalColumns < MIN_COLS_FOR_FULL_SPRITE) return 0

  const nameWidth = stringWidth(companion.name)
  const bubble = speaking && !isFullscreenActive() ? BUBBLE_WIDTH : 0
  return spriteColWidth(nameWidth) + SPRITE_PADDING_X + bubble
}
```

### 10.2 全屏 vs 非全屏

| 模式 | 气泡位置 | 实现方式 |
|---|---|---|
| 非全屏 | 内联在输入框右侧 | 输入框宽度自动缩窄 |
| 全屏 | 浮动 overlay 在聊天区上方 | `CompanionFloatingBubble` 组件，挂载在 `FullscreenLayout` 的 `bottomFloat` slot |

```typescript
// 全屏模式下，气泡和精灵分别渲染
// CompanionSprite 只渲染精灵（不含气泡）
// CompanionFloatingBubble 单独渲染气泡（不受 overflowY:hidden 裁剪）
```

---

## 11. 预告与发布机制

### 11.1 彩虹预告通知

```typescript
// src/buddy/useBuddyNotification.tsx

// 仅在 2026 年 4 月 1-7 日显示（预告期）
function isBuddyTeaserWindow(): boolean {
  const d = new Date()
  return d.getFullYear() === 2026 && d.getMonth() === 3 && d.getDate() <= 7
}

// 启动时，如果用户还没有宠物，显示彩虹 /buddy 提示
useEffect(() => {
  if (!feature('BUDDY')) return
  const config = getGlobalConfig()
  if (config.companion || !isBuddyTeaserWindow()) return

  addNotification({
    key: 'buddy-teaser',
    jsx: <RainbowText text="/buddy" />,
    priority: 'immediate',
    timeoutMs: 15000,  // 15 秒后自动消失
  })
}, [addNotification, removeNotification])
```

**设计要点**：
- 使用**本地时间**而非 UTC，实现 24 小时滚动波浪（不同时区用户在不同时间看到预告）
- "gentler on soul-gen load"——避免全球用户同时在 UTC 午夜触发 AI 命名请求

> **OpenCode Pet 可借鉴**：首次启动时的引导体验可以借鉴这个"预告 → 孵化"的两步流程，增加仪式感。

---

## 12. 安全与防作弊设计

### 12.1 物种名称混淆

Claude Code 将物种名称用 charCode 编码，避免触发内部构建检查（有一个物种名与内部 canary 字符串冲突）：

```typescript
// src/buddy/types.ts
const c = String.fromCharCode

export const duck = c(0x64, 0x75, 0x63, 0x6b) as 'duck'
export const goose = c(0x67, 0x6f, 0x6f, 0x73, 0x65) as 'goose'
// ... 18 个物种全部编码
```

### 12.2 Bones 不持久化

```typescript
// 配置中只存储 soul（name + personality + hatchedAt）
// bones 每次从 userId 重新计算
// → 编辑配置文件无法伪造稀有度/物种
type StoredCompanion = CompanionSoul & { hatchedAt: number }
// 注意：不包含 rarity、species、stats 等字段
```

### 12.3 Roll 缓存

```typescript
// 多个热路径（500ms tick、每次按键、每轮 observer）都用同一个 userId
// → 缓存结果避免重复计算
let rollCache: { key: string; value: Roll } | undefined

export function roll(userId: string): Roll {
  const key = userId + SALT
  if (rollCache?.key === key) return rollCache.value
  const value = rollFrom(mulberry32(hashString(key)))
  rollCache = { key, value }
  return value
}
```

> **OpenCode Pet 可借鉴**：如果引入稀有度/属性系统，务必将"可被用户修改的值"和"从用户 ID 推导的值"分离存储。

---

## 13. Feature Flag 控制

整个宠物系统由 `BUDDY` feature flag 守护，所有入口都有编译时消除：

```typescript
// 编译时检查——feature() 只在 if 条件中有效
if (!feature('BUDDY')) return null

// 条件导入
const buddy = feature('BUDDY')
  ? require('./commands/buddy/index.js').default
  : null

// 命令注册时条件展开
...(buddy ? [buddy] : []),
```

> **OpenCode Pet 可借鉴**：宠物功能应该可以通过配置开关轻松禁用（当前已有 `companionMuted` 类似设计），未来如果添加 AI 评论功能也需要 feature flag。

---

## 14. 对 OpenCode Pet 的借鉴建议

### 高价值（推荐优先实现）

| 功能 | 借鉴点 | 实现难度 |
|---|---|---|
| **AI 评论气泡** | 使用旁路小模型调用，每轮对话后生成短评论，显示为浮动气泡 | 中 |
| **宠物个性化命名** | 首次启动时 AI 生成名字 + 性格描述 | 低 |
| **确定性属性生成** | 从用户 ID 推导宠物个性参数（影响行为/评论风格） | 低 |
| **主 prompt 注入** | 在 OpenCode system prompt 中告知 AI 宠物的存在 | 低 |

### 中价值（增强体验）

| 功能 | 借鉴点 | 实现难度 |
|---|---|---|
| **稀有度系统** | 影响模型选择/动画丰富度/评论风格 | 中 |
| **属性影响行为** | PATIENCE 高→等待时更平静，SNARK 高→评论更毒舌 | 中 |
| **点击/抚摸互动** | 点击宠物触发特殊动画（已在 P2 计划中） | 低 |
| **自动消失气泡** | 评论 10 秒后淡出，用户滚动时立即消失 | 低 |

### 低价值（锦上添花）

| 功能 | 借鉴点 | 实现难度 |
|---|---|---|
| **闪亮标记** | 1% 概率获得特殊粒子效果 | 低 |
| **帽子/装饰品** | Live2D 模型上的装饰叠加层 | 高 |
| **预告/孵化仪式** | 首次启动的两步引导流程 | 中 |

---

## 15. 文件索引

| Claude Code 文件 | 内容 | 是否缺失 |
|---|---|---|
| `src/buddy/types.ts` | 类型定义：稀有度、物种、眼睛、帽子、属性、Companion 类型 | ✅ 有 |
| `src/buddy/companion.ts` | 确定性生成、PRNG、哈希、Roll 缓存、getCompanion() | ✅ 有 |
| `src/buddy/sprites.ts` | 18 个物种的 ASCII art（各 3 帧）+ 渲染函数 | ✅ 有 |
| `src/buddy/CompanionSprite.tsx` | React/Ink 精灵组件 + 气泡 + 浮动 overlay + 动画逻辑 | ✅ 有 |
| `src/buddy/prompt.ts` | companion intro 文本生成 + 附件注入 | ✅ 有 |
| `src/buddy/useBuddyNotification.tsx` | 彩虹预告通知 + `/buddy` 触发位置检测 | ✅ 有 |
| `src/buddy/observer.ts` | 评论生成核心逻辑（fireCompanionObserver） | ❌ 缺失 |
| `src/commands/buddy/` | `/buddy` 命令实现（孵化、pet、管理等） | ❌ 缺失 |
