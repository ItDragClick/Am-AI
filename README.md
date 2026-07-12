# 🤖 am-ai mod (v26.2) — System Architecture & Developer Guide

> *A Minecraft Fabric client-side mod bridging an in-game avatar with a locally hosted Large Language Model (llama3.1:8b) via Ollama, orchestrating real-time movement, pathfinding, and world interactions using Baritone.*

---

## ✅ Implementation Status & Code Map (updated 2026-07-12)

The full architecture described below is now implemented and building:
```bash
gradlew build → build/libs/am-ai-a0.1.jar
```

All AI code lives in the **client source set**:

| Class | Path | Role |
|---|---|---|
| `AmAIClient` | `AmAIClient.java` | Client entrypoint: loads config, forces `java.awt.headless=false` and launches the Swing dashboard explicitly on the EDT via `SwingUtilities.invokeLater` (never on the render thread), registers chat hooks, disposes the dashboard on `ClientLifecycleEvents.CLIENT_STOPPING`. |
| `AIModSettings` | `config/AIModSettings.java` | GSON-serializable settings (endpoint URL, model ID, prefix, active flag). |
| `SettingsPersistenceManager` | `config/SettingsPersistenceManager.java` | Thread-safe load/save to `config/ai_companion_settings.json` (via `FabricLoader.getConfigDir()`). |
| `AIDashboardFrame` | `ui/AIDashboardFrame.java` | GridBagLayout JFrame: settings panel (Save Config / Test Connection / Active radio indicator), thread-safe static `appendSystemLog` console, manual prompt panel. Manual prompts starting with a known action verb (`goto`, `mine`, `deposit_chest`, …) bypass the LLM entirely and execute as direct structural overrides; anything else queries the LLM with the strict terminal persona. `HIDE_ON_CLOSE` so closing it never kills the game. |
| `ChatEventListener` | `chat/ChatEventListener.java` | Inbound chat interceptor using Fabric's `ClientReceiveMessageEvents` (`CHAT` + `GAME`) rather than a hand-written `ClientPacketListener` mixin. Prefix filtering, self-echo suppression, active-flag gating. **Absolute cancellation override**: "!AI stop" / "!AI stop follow" (case-insensitive) from a whitelisted player bypasses the LLM entirely — `BaritoneBridge.hardStop()` (cancelEverything + `getFollowProcess().cancel()`), `HarvestManager.cancel()`, `SurvivalMonitor.clearAllOrders()` on the main thread. Stop requests must never round-trip through the model. |
| `InventoryHelper` | `ai/InventoryHelper.java` | Pre-action inventory prep (main thread only). Hotbar conventions: slot 0 = weapon, slot 1 = food. `equipBestWeapon` scans slots 0-35, ranks by WEAPON component + ATTACK_DAMAGE modifiers, SWAP-clicks the winner into slot 0 and selects it. `stageFoodInHotbar` deep-scans all 36 slots for `DataComponents.FOOD` and stages into slot 1. `dropAllOf(itemId)` = drop_items system: selects each matching stack (swapping backpack stacks up first) and `player.drop(true)` drops whole stacks at the bot's feet. InventoryMenu slot map: hotbar H → menu slot 36+H, main inventory N → menu slot N. |
| `OllamaNetworkClient` | `net/OllamaNetworkClient.java` | Async `java.net.http.HttpClient` POST to `/api/generate` (`stream:false`, `format:"json"`). Split-persona system via `Source` enum: `IN_GAME` gets the witty Neuro-sama-style `PERSONA_PROMPT`, `DASHBOARD`/`SYSTEM` get the rigid `STRICT_PROMPT`; both get the memory bank (`AIMemoryStore.promptContext()`) appended to the system window. Balanced-brace JSON extraction, fallback `{"chat": "My thoughts got jumbled!", "action": "stop"}` (missing `action` key = `""` — just chatting, must not cancel harvest plans), in-flight guard, `testConnection` GET probe. Records each in-game interaction into short-term memory. |
| `AIMemoryStore` | `memory/AIMemoryStore.java` | Persistent memory engine at `config/am_ai_memory.json` (GSON, synchronized): `short_term_history` (rolling 15 interactions player-input→action), `long_term_declarative` facts, `known_chest` drop-off coordinates. `promptContext()` renders the bank for LLM injection every request. |
| `HarvestManager` | `ai/HarvestManager.java` | Unified multi-step state machine on `END_CLIENT_TICK`. **Block chain**: MINING (Baritone mines, count watched vs 16-item target) → RETURN_TO_PLAYER (repath to requester's live position every 2s) → within 2 blocks `drop_items` at their feet → IDLE. **Porkchop Workflow (hunt chain)**: `MOB_DROPS` map (pig→porkchop, cow→beef, chicken→chicken, sheep→mutton, rabbit→rabbit); HUNTING: between kills, walks over the nearest matching ground `ItemEntity` (24-block scan) to vacuum drops — kills alone never raise the inventory count — then re-feeds `requestAttack(mob)` until 3 drops collected, then return + drop. `attack <farm animal>` auto-routes into the hunt chain (singular-ized, requires a requester). GOTO_CHEST/DEPOSITING remain the explicit `deposit_chest` container path (`useItemOn` + `QUICK_MOVE` + `closeContainer`). Combat interrupts resume via `reissueCurrentStep()`. |
| `AIActionBridge` | `ai/AIActionBridge.java` | Re-schedules onto the game thread via `Minecraft.getInstance().execute(...)`, sends the `chat` value with `getConnection().sendChat(...)` (≤100 chars), parses the full action grammar: `goto X Y Z`, `mine <block>`, `mine_area X1 Y1 Z1 X2 Y2 Z2`, `follow <player>`, `attack <name>`, `eat`, `click_respawn`, `stop`. Shared safe integer parsing (floors decimals); any malformed coordinates trigger a safety `stop`. Placeholder sanitizer (`cleanTarget`): small models echo the instruction card literally (`follow <player_name>`, `attack player/Steve`) — strips angle brackets/quotes, keeps text after `/`, and resolves placeholders or self-references ("me") to the requesting player's name. **`canonicalizeAction` (pure, any-thread)**: normalizes raw LLM actions before BOTH execution and memory recording — attack aliases (kill/hunt/slay/fight/hit), fused hallucinations (`mine_pigs`/`kill_pig` → `attack pig`), mob args given to `mine` rerouted to `attack`, plurals singularized. Critical: memory must only ever store canonical actions — recording raw output like `mine_pigs` re-injects it via the prompt's memory bank and teaches the model to repeat its own mistakes forever (observed 2026-07-12); `AIMemoryStore.load` also scrubs poisoned entries from disk. |
| `BaritoneBridge` | `ai/BaritoneBridge.java` | Direct (no reflection) calls into the official Baritone API: `getCustomGoalProcess().setGoalAndPath(new GoalBlock(...))`, `getMineProcess().mineByName(id)`, `getBuilderProcess().clearArea(corner1, corner2)` (mine_area), `getFollowProcess().follow(predicate)` (follow), `getPathingBehavior().cancelEverything()`. Combat interrupt support: `pauseForCombat()` saves the current custom goal + cancels everything; `resumeRememberedGoal()` re-issues it. Compiles against `libs/baritone-api-fabric-26.2-SNAPSHOT.jar`. Guards: mod-presence check (`baritone` / `baritone-meteor` ids) and `LinkageError` handler that diagnoses the obfuscated-standalone-jar case. |
| `SurvivalMonitor` | `ai/SurvivalMonitor.java` | Per-tick common-sense layer on `ClientTickEvents.END_CLIENT_TICK` (main thread). **Friendly whitelist** (`FRIENDLY_PLAYERS`: Golden_Allay, taffer2630): never targeted — attack orders refused, threat scans skip them, final `engage()` guard. **Combat interceptor**: on damage taken or health < 12.0f — attacker heuristic (client `getLastHurtByMob()` NOT reliably synced for player attackers: synced attacker → nearest `Monster` → nearest other `Player`; passive mobs never qualify) within 5 blocks; pauses Baritone (goal cached), locks onto the threat entity across ticks. **PvP movement engine**: >8 blocks Baritone-chases moving coordinates (repath throttled to 1/sec), 3–8 blocks manual `keyUp` + `setSprinting(true)` chase, strikes ≤3.5 blocks gated by `getAttackStrengthScale`; gives up beyond 20-block tracking radius; post-combat restores the saved goal AND re-issues the harvest plan step. **Auto-eat**: hunger <14 triggers `InventoryHelper.stageFoodInHotbar` (full 0-35 scan, food staged in hotbar slot 1), `keyUse` held until fed, previous slot restored; hunger <6 mid-combat = retreat 5 blocks (Baritone goal away from threat), eat, re-engage above 12. Weapon staging delegates to `InventoryHelper.equipBestWeapon` (slot 0). **Death policy** (user decision 2026-07-12): NEVER return to the death point — always re-gear fresh (logs "Items lost permanently. Re-gearing strategy activated.", starts oak_log harvest). **Delayed respawn chat**: the server rejects chat from dead players, so LLM chat produced on the death screen is queued in `pendingRespawnChat` (via `queueRespawnChat`, fed by `AIActionBridge.sendChat`'s isDeadOrDying check) and flushed by the tick loop only once fully revived (alive + health > 0). `clearAllOrders()` = combat/attack wipe for the stop override. |

### 📋 Version 26.2 Naming Notes `baritone-standalone-fabric-*.jar` is **ProGuard-obfuscated** — every `baritone.api` method is renamed to `a`/`b`, causing both reflection lookups and direct API calls to fail (`NoSuchMethodException` / `NoSuchMethodError`).

✅ **Always use the API build:**
- Compiling: `libs/baritone-api-fabric-26.2-SNAPSHOT.jar`
- Runtime: Same api jar in `run/mods` / game's mods folder

📚 Source: https://github.com/IzumiiKonata/baritone (branch `26.2`) → `./gradlew build` → `dist/`

> **Note:** `gradlew build` passes; LLM response parser smoke-tested against clean JSON, markdown-fenced JSON, prose-wrapped JSON, malformed output, garbage envelopes, and missing keys — all fall back safely.

---

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
| **⚙️ Settings Panel** | Modify endpoint URL, model config, command prefix; display LLM parser state |
| **📊 Telemetry Console** | Custom scroll-pane with detailed text logs |
| **💬 Direct Console** | Send LLM prompts directly from host PC (no in-game chat needed) |

---

## 📋 JSON Configuration & Persistence

Settings persist locally inside `.minecraft/config/ai_companion_settings.json` using Minecraft's bundled **GSON** parser.

```json
{
  "endpointUrl": "http://localhost:11434",
  "modelId": "llama3.1:8b",
  "commandPrefix": "!ai",
  "active": true
}
```

No external dependencies needed — GSON is native to Minecraft.

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

## 🧠 Ollama API Integration & Llama3.1:8b System Prompt

The API payload wraps all requests with dual-persona system (`OllamaNetworkClient.Source`):

### 🎭 Personas

| Mode | Persona | Tone |
|---|---|---|
| **IN_GAME** | `PERSONA_PROMPT` | Witty, self-aware, Neuro-sama-inspired (jokes allowed) |
| **DASHBOARD / SYSTEM** | `STRICT_PROMPT` | Rigid command terminal (no flair) |

Both inject the memory bank (`AIMemoryStore.promptContext()`) into every request.

### 📤 Output Format

```json
{
  "chat": "Your response text here",
  "action": "goto 100 64 -200"
}
```

**Allowed actions:**
```
goto <X> <Y> <Z>
mine <block_id>
mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>
follow <player_name>
attack <singular_mob_or_player_name>
eat
drop_items <item_id>
deposit_chest <X> <Y> <Z>
sneak / unsneak
click_respawn
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

---

## 🚀 Installation, Compilation & Run Instructions

### 1️⃣ Configure the Build Environment

Add Minecraft 26.2, Fabric API, and Baritone API dependencies in `build.gradle`:

```gradle
dependencies {
    // Minecraft 26.2, Fabric Loader, Fabric API
    // Configure for your environment (unobfuscated Mojang names)
    
    // Local Baritone API JAR
    compileOnly files("libs/baritone-api-fabric-26.2-SNAPSHOT.jar")
}
```

> **Note:** Use the API build, NOT standalone (ProGuard-obfuscated).

### 2️⃣ Verify Your Local LLM Engine

Ensure Ollama is running with `llama3.1:8b`:

```bash
ollama run llama3.1:8b
```

Test the endpoint:

```bash
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.1:8b",
  "prompt": "Say hello!"
}'
```

### 3️⃣ Compile the Mod

```bash
./gradlew build
```

Output: `build/libs/am-ai-a0.1.jar`

Move to mods folder:
```bash
cp build/libs/am-ai-a0.1.jar ~/.minecraft/mods/
```

### 4️⃣ Run the Game

Launch Minecraft 26.2 via Fabric. The mod automatically:
- Loads configuration from `config/ai_companion_settings.json`
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

**Default Model:** `llama3.1:8b` (fully configurable from dashboard)

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

> **Last updated:** 2026-07-12 | **Version:** am-ai v26.2 | **Status:** ✅ Fully Implemented & Tested
