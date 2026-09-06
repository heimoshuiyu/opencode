import type { PetState } from "../types"

/**
 * v4 动作映射表 — PJSK 初音未来模型（412 个 w- 全身动作）
 *
 * 两层架构：
 *   Activity（持久状态）— 循环播放，由事件驱动切换
 *   Reaction（瞬态反应）— 单次播放，超时后回退到当前 Activity
 *
 * 命名规则：`w-{服装}{编号}-{动作}{变体}`
 *
 * 服装类型（来自 PJSK 游戏卡面）：
 *   normal = 默认校服, cute = 可爱风, cool = 酷帅风
 *   happy = 明亮风, adult = 成熟风, pure = 纯净风
 *   animal = 动物装, special = 特殊演出服, wonder = 奇想风格
 *   band = 乐队装, idol = 偶像装, night = 夜间装, street = 街头装
 *
 * 动作关键词（日语罗马音 + 英语混合）：
 *   tilthead     = 歪头（好奇、疑问）
 *   nod          = 点头（认同、确认）
 *   pose         = 摆姿势（站定、展示）
 *   shakehead    = 摇头（否定、不赞同）
 *   forward      = 前倾（专注、靠近）
 *   blushed      = 脸红（害羞、不好意思）
 *   shy          = 害羞（扭捏、闪躲）
 *   glad         = 开心（喜笑颜开）
 *   angry        = 生气（不满、愤怒）
 *   sad          = 难过（低落、沮丧）
 *   sigh         = 叹气（无奈、疲惫）
 *   relief       = 松了口气（如释重负）
 *   trouble      = 困扰（烦恼、不知所措）
 *   think        = 思考（认真想事情）
 *   delicious    = 好吃（满足、享受）
 *   wink         = 眨眼（调皮、暗示）
 *   piece        = 比耶✌️（剪刀手、开心手势）
 *   guts         = 加油💪（握拳、斗志昂扬）
 *   thumb        = 竖大拇指👍（赞、鼓励）
 *   shakehand    = 握手（打招呼、达成共识）
 *   greeting     = 打招呼（挥手问候）
 *   fidget       = 坐立不安（烦躁、紧张）
 *   smug         = 得意（小骄傲、自信满满）
 *   sleep        = 睡觉（打盹）
 *   purpose      = 决意（下定决心、坚定的眼神）
 *   lookaround   = 东张西望（好奇张望）
 *   lookleft     = 向左看
 *   lookright    = 向右看
 *   lookaway     = 看向别处（移开视线）
 *   cheek        = 鼓腮帮（可爱、假装生气）
 *   catch        = 接住（抓住东西）
 *   musical      = 唱歌🎵（音符、音乐表演）
 *   wandahoi     = 惊喜欢呼（"わんだほい！" — Miku 的 PJSK 口头禅）
 *   armopen      = 张开双臂（欢迎、开放姿态）
 *   hug          = 拥抱🤗
 *   yurayura     = 摇摇晃晃（晃来晃去、轻松摇摆）
 *   guruguru     = 晕眩转圈（眼冒金星、头晕）
 *   nbtilthead   = 无身体歪头（仅头部倾斜，身体不动）
 *   nbforward    = 无身体前倾
 *   nbglad       = 无身体开心
 *   nbshy        = 无身体害羞
 *   nbnod        = 无身体点头
 */

const motionMap: Record<PetState, string[]> = {
  // ═══════════════════════════════════════════════════════════
  // Activity 状态 — 持久，循环播放，事件驱动切换
  // ═══════════════════════════════════════════════════════════

  /**
   * idle 空闲 — 轻松随意，东张西望，偶尔歪头好奇
   * 进入：session.status=idle · Reaction 回退
   * 循环：5-8s
   */
  idle: [
    // 歪头 — 好奇、放松
    "w-normal01-tilthead",
    "w-normal03-tilthead",
    "w-normal15-tilthead",
    "w-cute01-tilthead",
    "w-cute02-tilthead",
    "w-cool06-tilthead",
    "w-happy14-tilthead",
    // 摆姿势 — 站定、自然站立
    "w-normal01-pose",
    "w-normal03-pose",
    "w-cute01-pose",
    "w-cute02-pose",
    "w-happy11-pose",
    // 东张西望 — 观察周围
    "w-normal15-lookaround",
    "w-normal03-lookright",
    "w-normal15-lookright",
    "w-normal03-lookleft",
    "w-normal15-lookleft",
    // 眨眼 — 调皮
    "w-cute02-wink",
    "w-cute11-wink",
    // 轻微歪头 — 极度放松
    "w-cute01-nbtilthead04",
    "w-cute11-nbtilthead",
    // 比耶 — 随意的开心
    "w-normal01-piece",
    // 点头 — 轻微认同
    "w-normal15-nod",
    // 害羞 — 放松时的害羞
    "w-cute01-shy",
    "w-normal15-shy",
    // 动物装歪头 — 新鲜感
    "w-animal02-tilthead",
    // 微微歪头 — 极度放松的细微变化
    "w-cute-nbtilthead09",
    "w-cute01-tilthead02",
    "w-cute11-tilthead02",
    // 自然站定变体
    "w-normal15-pose",
    // 随意比耶变体
    "w-normal02-piece",
  ],

  /**
   * thinking AI思考中 — 专注前倾、认真思索
   * 进入：session.status=busy（等待 LLM 响应）
   * 循环：4-6s
   */
  thinking: [
    // 思考 — 明确的思考动画（手托下巴等）
    "w-adult01-think",
    "w-adult05-think",
    // 前倾 — 靠近、专注
    "w-normal03-forward",
    "w-normal04-forward",
    "w-cool10-forward",
    "w-cute11-forward",
    "w-cute02-forward",
    "w-happy09-forward",
    // 歪头 — 疑问、思索
    "w-normal03-tilthead",
    "w-normal04-tilthead",
    "w-adult01-tilthead",
    "w-cute01-tilthead",
    // 点头 — 在思考中微微点头
    "w-normal03-nod",
    "w-normal04-nod",
    "w-cool10-nod",
    // 摆姿势 — 认真站定
    "w-cool02-pose",
    "w-normal03-pose",
    // 脸红 — 思考到面红耳赤
    "w-normal03-blushed",
    // 更多前倾变体
    "w-happy14-forward",
    "w-cool13-forward",
    // 歪头变体
    "w-adult05-tilthead",
    "w-cool09-tilthead",
  ],

  /**
   * generating AI输出中 — 有节奏地点头/说话，比 thinking 更活跃
   * 进入：message.part.delta（首次收到 LLM 流式输出）
   * 循环：3-5s
   */
  generating: [
    // 点头 — 有节奏地确认输出
    "w-normal01-nod",
    "w-normal03-nod",
    "w-normal04-nod",
    "w-cute02-nod",
    "w-happy02-nod",
    // 开心 — 输出内容时的满足感
    "w-cute01-glad",
    "w-cute11-glad",
    "w-normal01-glad",
    // 比耶 — 输出段落完成的小手势
    "w-normal01-piece",
    "w-cute11-piece",
    "w-happy11-piece",
    // 摆姿势 — 自信地输出
    "w-normal03-pose",
    "w-cool02-pose",
    "w-cute01-pose",
    // 好吃 — 输出的内容很好
    "w-cute11-delicious",
    "w-adult02-delicious",
    // 微微动作 — 轻微变化，不打断输出节奏
    "w-cute11-nbforward",
    "w-cute11-nbglad02",
    "w-cute12-nbnod",
    // 开心变体
    "w-cute11-glad02",
    "w-cute01-glad03",
    // 前倾变体
    "w-cute13-forward",
    // 动物装点头 — 可爱节奏感
    "w-animal01-nod",
    "w-animal12-nod",
  ],

  /**
   * acting 工具执行 — 认真工作，有决意
   * 进入：message.part.updated (tool, status=running) · permission.replied
   * 循环：4-6s
   */
  acting: [
    // 决意 — 下定决心执行工具
    "w-normal01-purpose",
    "w-happy02-purpose",
    // 点头 — 确认进度、持续推进
    "w-normal01-nod",
    "w-normal03-nod",
    "w-normal04-nod",
    "w-cool06-nod",
    "w-adult01-nod",
    "w-cute02-nod",
    // 摆姿势 — 认真站定工作
    "w-normal03-pose",
    "w-cool02-pose",
    "w-cool06-pose",
    // 前倾 — 专注操作
    "w-normal03-forward",
    "w-cool10-forward",
    // 歪头 — 思考工具输出
    "w-normal01-tilthead",
    "w-cool06-tilthead",
    // 更多站定变体 — 认真工作姿态
    "w-normal15-pose",
    "w-normal08-pose",
    "w-normal17-pose",
    "w-adult01-pose",
    "w-adult02-pose",
    "w-cool10-pose",
    "w-cool13-pose",
    "w-cute11-pose",
    "w-cute12-pose",
    // 前倾变体 — 专注操作
    "w-normal16-forward",
    "w-normal17-forward",
    // 动物装站定
    "w-animal12-pose",
  ],

  /**
   * waiting-user 等待用户 — 抬头看向用户，歪头期待
   * 进入：permission.asked · question.asked
   * 循环：5-8s
   */
  "waiting-user": [
    // 歪头 — 期待地看着用户
    "w-normal01-tilthead",
    "w-normal03-tilthead",
    "w-cute01-tilthead",
    "w-cute11-tilthead",
    // 前倾 — 靠近用户等待回应
    "w-normal03-forward",
    "w-cute11-forward",
    // 东张西望 — 等待中的不安
    "w-normal15-lookaround",
    "w-normal03-lookleft",
    "w-normal03-lookright",
    // 无身体歪头 — 微妙的期待
    "w-cute01-nbtilthead04",
    "w-cute11-nbtilthead",
    // 眨眼 — 期待地眨眼
    "w-cute02-wink",
    "w-cute11-wink",
    // 害羞 — 被注视的害羞反应
    "w-cute01-shy",
    "w-cute11-shy02",
    "w-pure12-shy",
    "w-normal15-shy",
    "w-cute11-nbshy02",
    "w-animal12-shy02",
    // 脸红 — 等待中的害羞
    "w-normal03-blushed",
    "w-normal04-blushed",
    // 移开视线 — 假装不在意
    "w-happy14-lookaway",
    // 歪头变体
    "w-adult05-tilthead",
    "w-cute12-tilthead",
    "w-animal02-tilthead",
  ],

  /**
   * retrying 重试中 — 焦急，坐立不安
   * 进入：session.status=retry
   * 循环：5-8s
   */
  retrying: [
    // 坐立不安 — 烦躁地等待重试
    "w-normal17-fidget",
    "w-cute11-fidget02",
    "w-pure12-fidget",
    // 困扰 — 不知所措
    "w-normal03-trouble",
    "w-normal04-trouble",
    "w-adult02-trouble",
    "w-adult05-trouble",
    // 叹气 — 无奈等待
    "w-normal07-sigh",
    "w-cool13-sigh",
    // 摇头 — 失望但还在努力
    "w-normal01-shakehead",
    "w-normal03-shakehead",
    // 生气 — 重试中的烦躁
    "w-happy09-angry",
    "w-happy14-angry",
    // 更多摇头变体
    "w-normal02-shakehead",
    "w-normal07-shakehead",
    "w-normal15-shakehead",
    "w-normal03-shakeheadB",
    // 动物装烦躁
    "w-animal12-fidget02",
    "w-animal12-fidget02B",
  ],

  /**
   * sleeping 休眠 — 打盹
   * 进入：30s 无任何事件
   * 循环：8-12s
   */
  sleeping: [
    // 睡觉 — 打盹动画
    "w-cute01-sleep05",
    "w-cute01-sleep05B",
    "w-adult15-sleep",
    "w-adult15-sleepB",
  ],

  /**
   * disconnected 断开连接 — 焦急寻找，四处张望等待主人回来
   * 进入：SSE 连接断开
   * 循环：6-10s
   */
  disconnected: [
    // 困扰 — 不知所措、焦急等待
    "w-normal03-trouble",
    "w-normal04-trouble",
    "w-adult02-trouble",
    "w-adult05-trouble",
    "w-cool10-trouble",
    // 坐立不安 — 烦躁地等待
    "w-normal17-fidget",
    "w-cute11-fidget02",
    "w-pure12-fidget",
    // 东张西望 — 寻找主人
    "w-normal15-lookaround",
    "w-normal03-lookleft",
    "w-normal03-lookright",
    "w-normal15-lookleft",
    "w-normal15-lookright",
    // 叹气 — 无奈等待
    "w-normal07-sigh",
    "w-cool13-sigh",
    // 摇头 — 失望
    "w-normal01-shakehead",
    // 歪头 — 疑惑发生了什么
    "w-normal03-tilthead",
    "w-cute01-tilthead",
    // 动物装张望 — 寻找主人
    "w-animal12-lookright02",
    "w-animal02-tilthead",
    // 动物装烦躁
    "w-animal12-fidget02",
    "w-animal12-fidget02B",
    // 更多站定等待
    "w-normal15-pose",
    "w-normal08-pose",
    // 脸红 — 焦急又窘迫
    "w-cool06-blushed",
    // 歪头变体
    "w-adult05-tilthead",
    "w-normal07-tilthead",
  ],

  // ═══════════════════════════════════════════════════════════
  // Reaction 状态 — 瞬态，单次播放，超时后回退到当前 Activity
  // ═══════════════════════════════════════════════════════════

  /**
   * greeting 打招呼 — 握手、挥手、比耶
   * 触发：SSE 连接成功
   * 回退：2s → idle
   */
  greeting: [
    // 打招呼 — 挥手问候
    "w-normal15-greeting",
    // 握手 — 达成连接
    "w-happy01-shakehand",
    "w-happy02-shakehand",
    "w-cool01-shakehand",
    "w-adult01-shakehand",
    "w-happy11-shakehand",
    // 比耶 — 友好手势
    "w-happy11-piece",
    "w-cute11-piece",
    // 点头 — 认可
    "w-normal15-nod",
    "w-cute02-nod",
    // 脸红 — 见到主人害羞
    "w-adult01-blushed",
    "w-adult02-blushed",
    "w-normal03-blushed",
    // 害羞 — 羞涩打招呼
    "w-cute01-shy",
    "w-cute11-shy02",
    // 比耶变体
    "w-adult11-piece",
    "w-normal02-piece",
    // 额外握手
    "w-adult-shakehand01-additional",
  ],

  /**
   * chatting 被直接对话 — 注意力集中，前倾听用户说话
   * 触发：用户通过聊天输入框直接和宠物对话
   * 回退：30s → current activity（会被 pet session 事件覆盖）
   */
  chatting: [
    // 前倾 — 靠近听用户说话
    "w-normal03-forward",
    "w-normal04-forward",
    "w-cute11-forward",
    "w-cute02-forward",
    "w-happy09-forward",
    // 歪头 — 好奇对方在说什么
    "w-normal01-tilthead",
    "w-cute01-tilthead",
    "w-cute11-tilthead",
    "w-adult01-tilthead",
    "w-cute02-tilthead",
    // 点头 — 在听，认同
    "w-normal03-nod",
    "w-normal04-nod",
    "w-cute02-nod",
    "w-normal01-nod",
    // 眨眼 — 友好回应
    "w-cute02-wink",
    "w-cute11-wink",
    // 无身体歪头 — 微妙地关注
    "w-cute01-nbtilthead04",
    "w-cute11-nbtilthead",
    // 无身体前倾 — 专注倾听
    "w-cute11-nbforward",
    // 脸红 — 被搭话害羞
    "w-normal03-blushed",
    "w-adult01-blushed",
    // 害羞 — 有点不好意思被直接对话
    "w-cute01-shy",
    "w-cute11-shy02",
  ],

  /**
   * file-written 文件已保存 — 开心，比耶
   * 触发：file.edited · session.diff · worktree.ready
   * 回退：2s → current activity
   */
  "file-written": [
    // 开心 — 喜笑颜开
    "w-cute01-glad",
    "w-cute02-glad",
    "w-cute11-glad",
    "w-normal01-glad",
    "w-adult02-glad",
    // 比耶 — 开心手势
    "w-normal01-piece",
    "w-cute11-piece",
    "w-happy11-piece",
    // 好吃 — 满足
    "w-cute11-delicious",
    // 得意 — 小骄傲
    "w-cute14-smug",
    // 脸红 — 被认可后害羞
    "w-adult02-blushed",
    "w-cool06-blushed",
    "w-normal04-blushed",
    // 更多开心变体
    "w-adult11-glad02",
    "w-adult12-glad",
    "w-cute11-glad02",
    // 好吃变体
    "w-cute11-deliciousB",
    "w-cute01-delicious02",
  ],

  /**
   * completed 任务完成 — 大庆祝！唱歌、欢呼、拥抱
   * 触发：todo.updated(全部完成) · workspace.ready
   * 回退：4s → current activity
   */
  completed: [
    // 加油/握拳 — 斗志昂扬
    "w-cute02-guts",
    "w-cute11-guts",
    "w-happy11-guts",
    "w-normal01-guts",
    "w-cool02-guts",
    // 唱歌 — 音乐表演
    "w-special11-musical",
    "w-special11-musicalL",
    "w-special11-musicalR",
    // 惊喜欢呼 — "わんだほい！"
    "w-special11-wandahoi",
    // 张开双臂 — 热情拥抱
    "w-special15-armopen",
    // 拥抱
    "w-special15-hug",
    // 摇摇晃晃 — 开心地摇摆
    "w-special15-yurayura",
    // 竖大拇指
    "w-cool01-thumb",
    "w-cool02-thumb",
    // 比耶
    "w-cute11-piece",
    "w-happy11-piece",
    // 脸红 — 大庆祝中的害羞
    "w-adult01-blushed",
    // 更多开心变体
    "w-adult11-glad02",
    "w-adult12-glad",
    "w-happy14-glad",
    "w-cute01-glad03",
    "w-cute11-glad02",
    // 更多比耶
    "w-adult11-piece",
    "w-normal02-piece",
    // 动物装摆姿势
    "w-animal12-pose",
  ],

  /**
   * upgraded 已升级 — 惊喜，转圈
   * 触发：installation.updated
   * 回退：3s → current activity
   */
  upgraded: [
    // 惊喜欢呼 — "わんだほい！"
    "w-special11-wandahoi",
    // 鼓腮帮 — 可爱的惊喜
    "w-special11-cheek",
    "w-special11-cheekB",
    // 开心
    "w-cute01-glad",
    "w-cute11-glad",
    // 比耶
    "w-cute11-piece",
    "w-happy11-piece",
    // 脸红 — 惊喜后害羞
    "w-normal04-blushed",
    "w-adult01-blushed",
    // 更多开心
    "w-adult11-glad02",
    "w-adult12-glad",
    "w-happy14-glad",
    // 满足变体
    "w-cute11-deliciousB",
  ],

  /**
   * error 出错 — 难过，叹气
   * 触发：session.error · workspace.failed · worktree.failed · mcp.browser.open.failed
   * 回退：3s → current activity
   */
  error: [
    // 难过 — 明确的悲伤
    "w-normal07-sad",
    "w-normal08-sad",
    "w-cool16-sad",
    "w-happy16-sad",
    // 叹气 — 沮丧
    "w-normal07-sigh",
    "w-happy09-sigh",
    "w-cool13-sigh",
    // 摇头 — 失望
    "w-normal07-shakehead",
    "w-happy16-shakehead",
    // 困扰 — 不知所措
    "w-normal03-trouble",
    "w-normal04-trouble",
    "w-adult02-trouble",
    // 晕眩
    "w-special02-guruguru",
    // 生气 — 明确的愤怒
    "w-cool10-angry",
    "w-cute13-angry",
    "w-normal16-angry",
    // 脸红 — 窘迫
    "w-normal03-blushed",
    "w-normal04-blushed",
    // 移开视线 — 心虚
    "w-happy14-lookaway",
    // 更多摇头
    "w-normal02-shakehead",
    "w-normal07-shakehead",
    "w-normal15-shakehead",
    "w-normal03-shakeheadB",
  ],

  /**
   * pty-exited 终端退出 — exitCode=0 松口气 / exitCode≠0 摇头叹气
   * 触发：pty.exited
   * 回退：2.5s → current activity
   */
  "pty-exited": [
    // 成功退出（松口气、开心、点头）
    "w-normal03-relief",
    "w-normal04-relief",
    "w-adult12-relief",
    "w-cute01-glad",
    "w-normal01-glad",
    "w-normal03-nod",
    // 失败退出（叹气、摇头、难过）
    "w-normal07-sigh",
    "w-cool13-sigh",
    "w-normal01-shakehead",
    "w-normal03-shakehead",
    "w-normal07-sad",
    // 更多失败摇头
    "w-adult12-shakehead",
    "w-cute01-shakehead04",
    "w-cute12-shakehead",
    // 更多成功点头
    "w-normal08-nod",
    "w-adult02-nod",
    // 成功站定
    "w-normal04-pose",
    "w-cute12-pose",
  ],

  /**
   * compacted 已压缩 — 松口气后继续
   * 触发：session.compacted
   * 回退：2.5s → current activity
   */
  compacted: [
    // 松口气 — 明确的放松
    "w-normal03-relief",
    "w-normal04-relief",
    "w-adult12-relief",
    // 叹气 — 终于压缩完了
    "w-normal07-sigh",
    // 点头 — 确认继续
    "w-normal03-nod",
    "w-normal07-nod",
    // 更多站定
    "w-adult02-pose",
    "w-normal07-pose",
    // 更多点头变体
    "w-adult02-nod",
    "w-adult05-nod",
    "w-cute12-nod",
    "w-normal08-nod",
    "w-cute14-nod",
  ],

  /**
   * env-changed 环境变化 — 惊讶，歪头
   * 触发：vcs.branch.updated · mcp.tools.changed · project.updated ·
   *       installation.update-available · pty.created
   * 回退：2s → current activity
   */
  "env-changed": [
    // 鼓腮帮 — 可爱的惊讶
    "w-special11-cheek",
    "w-special11-cheekB",
    "w-special11-cheekC",
    // 接住 — 被意外东西吓到
    "w-special12-catch",
    "w-special12-catchB",
    // 歪头 — 短暂惊讶反应
    "w-normal01-tilthead",
    "w-cute01-tilthead",
    "w-cute11-tilthead",
    // 晕眩转圈 — 太突然了
    "w-special02-guruguru",
    // 更多歪头 — 惊讶反应
    "w-cool02-tilthead",
    "w-cool09-tilthead",
    "w-cool13-tilthead",
    "w-normal07-tilthead",
    "w-normal08-tilthead",
    "w-cute01-tilthead02",
  ],
}

export default motionMap
