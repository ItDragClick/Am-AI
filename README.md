# 🤖 am-ai mod (v26.2) — System Architecture & Developer Guide

> *A Minecraft Fabric client-side mod bridging an in-game avatar with a locally hosted Large Language Model (default `deepseek-r1:8b`) via Ollama, orchestrating real-time movement, pathfinding, and world interactions using Baritone.*

## ✨ Key Features
- **Player-Grade Combat**: Critical hits (sprint-cancel, jump, swing on the way down), the axe→mace stun slam, the mace jump + wind-charge air combo, predictive bow/crossbow leading, shield timing that never swings through its own block, mounted spear charge passes, creeper backpedals, low-health retreats, and MLG water clutches.
- **Lifelike Idle Behaviors**: Bot gets bored when idle and will autonomously explore, touch grass, pick flowers, stare at animals, start mini-games with nearby players, or complain about being tired at night and seek out a bed to sleep in.
- **Affinity & Grudges**: Remembers how you treat it. Scores run from Hatred to Love (everyone starts neutral). It will brutally mock players it dislikes when they die, hold a grudge if you kill it, forgive you for a dropped flower, and drop spontaneous gifts at the feet of players it loves.
- **Survival Instincts**: Automatically eats when hungry or low on health (lowering its shield first). Eats Golden Apples in critical combat. Swims up for air and breaks ceilings if drowning.
- **Autonomous Crafting & Gathering**: Recursive craft planning, ore-chain mining with tool gating, stock counting, and delivery to you or a chest.
- **Advanced Control Dashboard**: Modern Java Swing UI (Luna theme, FlatLaf) with animated page sliding, live task tracker, real session stats, combat tuning sliders, saveable presets, quick toggles, and Hold-to-Confirm safety buttons.

👉 **Check out the [Full Bot Capabilities Guide (what_bot_can_do.md)](what_bot_can_do.md) for a comprehensive list of all supported commands and behaviors!**

👉 **Developers:** [CLAUDE.md](CLAUDE.md) carries the full class-by-class code map and architecture notes.

---

## ✅ Build Status

```bash
gradlew build → build/libs/am-ai-a0.6.jar
```

### 📋 Version 26.2 Baritone Note

`baritone-standalone-fabric-*.jar` is **ProGuard-obfuscated** — every `baritone.api` method is renamed to `a`/`b`, causing both reflection lookups and direct API calls to fail (`NoSuchMethodException` / `NoSuchMethodError`).

✅ **Always use the API build:**
- Compiling: `libs/baritone-api-fabric-26.2-SNAPSHOT.jar`
- Runtime: Same api jar in `run/mods` / game's mods folder

📚 Source: https://github.com/IzumiiKonata/baritone (branch `26.2`) → `./gradlew build` → `dist/`

> **Note:** `gradlew build` passes; LLM response parser smoke-tested against clean JSON, markdown-fenced JSON, prose-wrapped JSON, malformed output, garbage envelopes, and missing keys — all fall back safely.

---

## 🏗️ System Overview

The **am-ai mod** operates entirely inside the standard Minecraft Java Edition game client as a Fabric client-side mod — no headless TCP clients.

### Key Advantages:

- **⚙️ Physics Offloading:** The official Minecraft client manages gravity, collision boxes, packet updates, and rendering natively
- **🧭 Complex Movement:** Baritone's advanced A-star algorithms for pathfinding, obstacle jumping, and block mining
- **💻 Zero-Cloud Dependency:** Local HTTP communication with Ollama (no cloud APIs or latency concerns)

---

## 🔄 Core Architecture & Multi-Threading Model

Minecraft runs physics, tick updates, and rendering on a **single primary loop: The Main Game Thread**. Even microsecond blocks cause stuttering or freezes.

The mod distributes work across three isolated execution layers:

```
┌─────────────────────────────────────────────────────────┐
│  Swing Event Dispatch Thread                            │
│  → Spawns async Settings & Monitoring Dashboard JFrame  │
├─────────────────────────────────────────────────────────┤
│  Ollama Network Thread                                  │
│  → Async HTTP requests to Local LLM (java.net.http)    │
├─────────────────────────────────────────────────────────┤
│  Main Game Thread                                       │
│  → Thread-safe Baritone command scheduling              │
└─────────────────────────────────────────────────────────┘
```

### 📌 Thread Concurrency Rules

1. **🔗 Network Offloading:** Every HTTP POST to Ollama must execute on an async worker thread (spawned by HTTP Client or Swing Worker).
2. **🎮 Main Thread Bridge:** Any LLM-returned action (chat, jump, walk, targeting) must schedule back to the main game thread:

```java
Minecraft.getInstance().execute(() -> {
    // Safe to interact with world & Baritone API here
});
```

---

## 🖥️ The Swing GUI Control Dashboard

When Minecraft boots, a native Java Swing JFrame launches independently, running outside the main game client as an interactive telemetry station.

| Component | Function |
|---|---|
| **⚙️ Settings Panel** | Endpoint URL, model, command prefix, weapon priority; toggle switches for every combat/idle behavior; live-applied combat tuning sliders (scrollable page) |
| **🎛️ Presets** | Save / load / delete named snapshots of the whole configuration (`config/am-ai/presets/<name>.json`) |
| **⚡ Quick Toggles** | The most-used switches, applied the moment you click them (no Save needed) |
| **📌 Task Tracker** | Live view of the active task and the paused LIFO queue |
| **📈 System Status & Activity Overview** | Real uptime, endpoint/model, plus session counters (tasks, play time, LLM requests, fights) |
| **📊 Telemetry Console** | Custom scroll-pane with detailed text logs |
| **💬 Direct Console** | Send LLM prompts directly from host PC (no in-game chat needed) |
| **🛑 Safety** | Hold-to-Confirm EMERGENCY STOP, Reset Memory, Reset Feelings |

---

## 📋 JSON Configuration & Persistence

All mod data lives under `.minecraft/config/am-ai/` using Minecraft's bundled **GSON** parser:
`ai_companion_settings.json`, `am_ai_memory.json`, `am_ai_vectors.json`, `relationships.json`, `am_ai_whitelist.json`, plus `presets/`.

```json
{
  "endpointUrl": "http://localhost:11434/api/generate",
  "modelId": "deepseek-r1:8b",
  "embeddingModelId": "nomic-embed-text",
  "commandPrefix": "!AI",
  "active": true,
  "weaponPriority": "Swords",
  "useShieldWhileFighting": true,
  "useMaceAttack": false,
  "useCritAttack": true,
  "spamSwingDelayTicks": 4,
  "macePunishMaxHits": 5,
  "shieldBreakRangeTenths": 32,
  "lanceChargeDistance": 8,
  "lowHealthThreshold": 8
}
```

Missing keys fall back to Java defaults, so older config files keep working. No external dependencies needed — GSON is native to Minecraft.

---

## 💬 In-Game Event Interception & Chat Hooks

A Fabric event listener intercepts incoming server chat packets at the networking protocol layer.

### 📥 Chat Processing Pipeline

| Step | Action |
|---|---|
| 1️⃣ **Network Packet Arrival** | Game processes client-bound chat message packet (0x41 in 26.2) |
| 2️⃣ **String Extraction** | Mixin parses chat component for sender ID and plain text |
| 3️⃣ **Prefix Filtering** | Validates: prefix match, not self-echo, system enabled |
| 4️⃣ **Queue & Dispatch** | Queues to background network thread for Ollama query |

---

## 🧠 Ollama API Integration & System Prompt

The API payload wraps all requests with dual-persona system (`OllamaNetworkClient.Source`):

### 🎭 Personas

| Mode | Persona | Tone |
|---|---|---|
| **IN_GAME** | `PERSONA_PROMPT` | Witty, self-aware, Neuro-sama-inspired (jokes allowed) |
| **DASHBOARD / SYSTEM** | `STRICT_PROMPT` | Rigid command terminal (no flair) |

Both inject the memory bank (`AIMemoryStore.promptContext()`), the recovered long-term memories (VectorDB/RAG), live telemetry, and a per-sender relationship instruction into every request.

### 📤 Output Format

```json
{
  "chat": "Your response text here",
  "action": "goto 100 64 -200",
  "target": "diamond_ore",
  "quantity": 16,
  "chest_coords": "100 64 -200",
  "duration_seconds": 60
}
```

**Allowed actions:**
```
goto <X> <Y> <Z>
mine <block_id>
mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>
farm
follow <player_name>
follow_protect <player_name>
attack <singular_mob_or_player_name>
mount / dismount
eat
sleep / leave_bed
drop_items <item_id>
deposit_chest <X> <Y> <Z>
equip / equip_offhand / unequip
sneak / unsneak
click_respawn
cancel
stop
'' (empty = just chatting, no action cancel)
```

### ✅ CRITICAL OPERATIONAL RULES

- ✔️ "follow me" → `follow <name>` (never guess `goto` coordinates)
- ✔️ "stop" → `stop` action (immediate)
- ✔️ Animal drops (porkchop/beef) → `attack <singular mob>` (never `mine_pigs`)
- ✔️ In-inventory gifts → `drop_items` (not `deposit_chest` unless chest specified)

> **Memory Encoding Note:** Raw LLM mistakes recorded in memory are re-injected, teaching the model its own errors. `canonicalizeAction` normalizes ALL actions before memory storage; `AIMemoryStore.load` scrubs poisoned entries on disk.

---

## 🔌 Thread-Safe Baritone API Bridge

The action string is parsed and translated into game commands on the main loop via `Minecraft.getInstance().execute()`.

### 🎮 Parsing & Dispatch Mapping

#### 💬 Chat Parameter
```java
Minecraft.getInstance().player.networkHandler.sendChatMessage(chatText);
```

#### 🧭 goto Action
```java
GoalBlock targetGoal = new GoalBlock(targetX, targetY, targetZ);
BaritoneAPI.getProvider().getPrimaryBaritone()
    .getCustomGoalProcess()
    .setGoalAndPath(targetGoal);
```

#### ⛏️ mine Action
```java
Block targetBlock = Registries.BLOCK.get(Identifier.of("minecraft", blockId));
BaritoneAPI.getProvider().getPrimaryBaritone()
    .getMineProcess()
    .mine(targetBlock);
```

#### 🛑 stop Action
```java
BaritoneAPI.getProvider().getPrimaryBaritone()
    .getPathingBehavior()
    .cancelEverything();
```

---

## 🚀 Installation, Compilation & Run Instructions

### 1️⃣ Configure the Build Environment

Minecraft 26.2, Fabric API, Baritone API, and FlatLaf (bundled into the mod jar) are declared in `build.gradle`:

```gradle
dependencies {
    // Minecraft 26.2, Fabric Loader, Fabric API
    // Configure for your environment (unobfuscated Mojang names)

    // Local Baritone API JAR
    compileOnly files("libs/baritone-api-fabric-26.2-SNAPSHOT.jar")

    // Dashboard look & feel — jar-in-jar so no extra install step
    implementation "com.formdev:flatlaf:3.5.1"
    include "com.formdev:flatlaf:3.5.1"
}
```

> **Note:** Use the Baritone API build, NOT standalone (ProGuard-obfuscated).

### 2️⃣ Verify Your Local LLM Engine

Ensure Ollama is running with the model you configured (default `deepseek-r1:8b`, embeddings `nomic-embed-text`):

```bash
ollama run deepseek-r1:8b
ollama pull nomic-embed-text
```

Test the endpoint:

```bash
curl http://localhost:11434/api/generate -d '{
  "model": "deepseek-r1:8b",
  "prompt": "Say hello!"
}'
```

### 3️⃣ Compile the Mod

```bash
./gradlew build
```

Output: `build/libs/am-ai-a0.6.jar`

Move to mods folder (alongside the Baritone **api** jar and Fabric API):
```bash
cp build/libs/am-ai-a0.6.jar ~/.minecraft/mods/
```

### 4️⃣ Run the Game

Launch Minecraft 26.2 via Fabric. The mod automatically:
- Loads configuration from `config/am-ai/ai_companion_settings.json`
- Launches the Swing dashboard window
- Registers chat event listeners
- Connects to local Ollama instance

---

## 📝 Minecraft 26.2 API Reference

This version uses **unobfuscated Mojang names** (not Yarn mappings):

| Legacy | MC 26.2 | Notes |
|---|---|---|
| `MinecraftClient` | `net.minecraft.client.Minecraft` | Extends `ReentrantBlockableEventLoop` |
| `ClientPlayNetworkHandler` | `ClientPacketListener` | Chat/packet handling |
| `ClientPlayerEntity` | `LocalPlayer` | Main player entity |
| `Text.getString()` | `Component.getString()` | Text conversion |
| `ItemStack.isFood()` | `stack.has(DataComponents.FOOD)` | Data-driven food detection |
| `SwordItem` | Data-driven weapons | Detect via `DataComponents.WEAPON` |
| `ItemStack.getAttackDamage()` | `DataComponents.ATTRIBUTE_MODIFIERS` | Match `Attributes.ATTACK_DAMAGE` |
| `setSelectedSlot(int)` | `Inventory.setSelectedSlot(int)` | Hotbar selection |
| `handleInventoryClick()` | `handleContainerInput()` | Container input API |
| `ClickType` | `ContainerInput` enum | Input types: `QUICK_MOVE`, `SWAP`, etc. |
| `ResourceKey.location()` | `.identifier()` | Get resource location |
| `player.respawn()` | `LocalPlayer.respawn()` | Respawn logic |
| `isDead()` | `isDeadOrDying()` | Death state check |

Also gone in 26.2: `Level.getDayTime()` (use `getOverworldClockTime() % 24000`), `SwordItem` (weapons are data-driven), `isEdible()`. Spears carry the `KINETIC_WEAPON` component; `MaceItem.canSmashAttack` needs `fallDistance > 1.5`.

**Default Model:** `deepseek-r1:8b` (embeddings `nomic-embed-text`) — both fully configurable from the dashboard. The in-game persona is named **'grape'**.

---

## 🐛 Troubleshooting & Common Failure Modes

### ❌ ConcurrentModificationException

**Problem:** Minecraft/Baritone task triggered directly from background HTTP network thread.

**Fix:** Wrap all actions in `Minecraft.getInstance().execute(() -> { ... })` to queue safely on main thread.

---

### ❌ Swing UI Freezes and Crashes

**Problem:** HTTP network task tried to run on Event Dispatch Thread (EDT).

**Fix:** Ensure all OllamaClient requests run asynchronously (CompletableFuture, external executor pools).

---

### ❌ Baritone Errors on Coordinate Parsing

**Problem:** LLM returned empty tokens or invalid coordinate ranges.

**Fix:** Wrap coordinate parsing in try-catch in `AIActionBridge.java`. On parse failure, fall back to `stop` action for entity stability.

---

## 📚 Additional Resources

- **Baritone Docs:** https://github.com/IzumiiKonata/baritone (branch `26.2`)
- **Fabric Wiki:** https://fabricmc.net/develop
- **Minecraft Wiki:** https://minecraft.wiki

---

> **Last updated:** 2026-07-17 | **Version:** am-ai v26.2 (a0.6) | **Status:** ✅ Fully Implemented & Tested
