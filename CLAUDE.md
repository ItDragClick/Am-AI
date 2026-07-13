# am-ai mod (v26.2) — System Architecture & Developer Guide

## Implementation Status & Code Map (updated 2026-07-12)

The full architecture described below is now implemented and building (`gradlew build` → `build/libs/am-ai-a0.1.jar`). All AI code lives in the client source set:

| Class | Path (under `src/client/java/com/itdragclick/client/`) | Role |
|---|---|---|
| `AmAIClient` | `AmAIClient.java` | Client entrypoint: loads config, forces `java.awt.headless=false` and launches the Swing dashboard explicitly on the EDT via `SwingUtilities.invokeLater` (never on the render thread), registers chat hooks, disposes the dashboard on `ClientLifecycleEvents.CLIENT_STOPPING`. |
| `AIModSettings` | `config/AIModSettings.java` | GSON-serializable settings (endpoint URL, model ID, prefix, active flag, weapon priority, combatAllowBlocks). |
| `SettingsPersistenceManager` | `config/SettingsPersistenceManager.java` | Thread-safe load/save to `config/ai_companion_settings.json` (via `FabricLoader.getConfigDir()`). |
| `AIDashboardFrame` | `ui/AIDashboardFrame.java` | Main dashboard window. Replaced `JTabbedPane` with a custom top navigation bar utilizing `SlidingPanel` for smooth page transitions. Includes a live Task Tracker sidebar, massive emergency STOP and CANCEL buttons, settings panel, and a system console. Destructive actions use `HoldButton` to prevent accidental clicks. `HIDE_ON_CLOSE` prevents the game from dying on exit. |
| `HoldButton` | `ui/HoldButton.java` | Custom Swing button that requires the user to hold the mouse down for a specified duration (e.g. 1000ms) to fire an action, displaying a fluid progress bar overlay. |
| `SlidingPanel` | `ui/SlidingPanel.java` | Custom container that buffers child components into `BufferedImage`s to execute a smooth horizontal slide animation when switching between pages. |
| `ChatEventListener` | `chat/ChatEventListener.java` | Inbound chat interceptor using Fabric's `ClientReceiveMessageEvents` (`CHAT` + `GAME`) rather than a hand-written `ClientPacketListener` mixin. Prefix filtering, self-echo suppression, active-flag gating. **Absolute cancellation override**: "!AI stop" / "!AI stop follow" (case-insensitive) from a whitelisted player bypasses the LLM entirely — `BaritoneBridge.hardStop()` (cancelEverything + `getFollowProcess().cancel()`), `HarvestManager.cancel()`, `SurvivalMonitor.clearAllOrders()` on the main thread. Stop requests must never round-trip through the model. |
| `InventoryHelper` | `ai/InventoryHelper.java` | Pre-action inventory prep (main thread only). Hotbar conventions: slot 0 = weapon, slot 1 = food. `equipBestWeapon` scans slots 0-35, ranks by WEAPON component + ATTACK_DAMAGE modifiers + Weapon Priority bias, SWAP-clicks the winner into slot 0 and selects it. `stageFoodInHotbar` deep-scans all 36 slots for `DataComponents.FOOD` and stages into slot 1. `dropAllOf(itemId)` = drop_items system: moves items to hotbar slot 1, selects it, and triggers `player.drop(true)` to prevent server inventory desyncs. `equipArmor(mc, player)` shift-clicks armor directly into equipment slots via `QUICK_MOVE`. InventoryMenu slot map: hotbar H → menu slot 36+H, main inventory N → menu slot N. |
| `OllamaNetworkClient` | `net/OllamaNetworkClient.java` | Async `java.net.http.HttpClient` POST to `/api/generate` (`stream:false`, `format:"json"`). Split-persona system via `Source` enum: `IN_GAME` gets the witty Neuro-sama-style `PERSONA_PROMPT`, `DASHBOARD`/`SYSTEM` get the rigid `STRICT_PROMPT`; both get the memory bank (`AIMemoryStore.promptContext()`) appended to the system window. JSON extraction uses six fields (chat, action, target, quantity, chest_coords, duration_seconds), falls back gracefully. Prompts explicitly warn about item aliases (enchant gapple -> enchanted_golden_apple), provide multiline inventory lists, and route 'go to the top'/'surface' to Y=320. Records each in-game interaction into short-term memory. |
| `AIWhitelistManager` | `ai/AIWhitelistManager.java` | Dynamic runtime whitelist at `config/am_ai_whitelist.json` (defaults Golden_Allay, taffer2630). In-memory `CopyOnWriteArraySet` for per-tick reads; file rewritten on change. **Edits are dashboard-ONLY** via the dedicated Whitelist tab or `whitelist add/remove <name>` console command; in-game attempts are refused with a chat reply. `SurvivalMonitor.isFriendly` delegates here. |
| `AIStateManager` | `ai/AIStateManager.java` | LIFO `Deque<TaskContext>` task stack (main thread). Exposes active queue via `getQueue()` for the dashboard. New requests preempt: managers serialize progress (remaining quantity, duration limit, requester, chest) via `captureAndPause()`, push; on completion `taskCompleted()` pops + resumes. Owns `goto` journeys (arrival = within 3 blocks → pop next task) and journey continuation across death (`onDeath` caches destination, `requeueJourneyAfterRespawn` queues it behind the re-gear task). `clearAll()` = kill switch. |
| `RegistryResolver` | `ai/RegistryResolver.java` | Fuzzy LLM-name → registry mapping (`BuiltInRegistries.ITEM/BLOCK`): exact → plural strip → segment permutation (`log_acacia`→`acacia_log`) → shortest superset. Null when nothing plausible. |
| `FarmManager` | `ai/FarmManager.java` | `#farm` module: scans 18-block radius for `CropBlock` at `isMaxAge` (wheat/carrots/potatoes/beetroots), Baritone to each, `gameMode.destroyBlock` (crops instant-break), replants matching seed on the farmland below via `useItemOn`. Loops until area clear, then announces + pops the task stack. Supports duration limits (auto-stops when time is up). |
| `CraftPlanner` | `ai/CraftPlanner.java` | Dynamic recursive crafting engine. Resolves dependency trees for tools, swords, armor, and basics. Example: `iron_sword` -> resolves to `iron_ingot` + `stick` -> `raw_iron` + `furnace` -> mines wood, crafts table, mines stone, crafts furnace, mines iron, smelts, crafts sword. Restarts safely each tick by re-evaluating the inventory. Container clicks managed via `handleContainerInput`. |
| `VectorDB` | `memory/VectorDB.java` | RAG Engine using Ollama embeddings (`/api/embeddings`). Stores `MemoryRecord(text, float[])` in `am_ai_vectors.json`. Uses cosine similarity to find relevant past interactions. Injected into prompts by `OllamaNetworkClient` as "RECOVERED LONG-TERM MEMORIES". |
| `AIMemoryStore` | `memory/AIMemoryStore.java` | Persistent memory engine at `config/am_ai_memory.json`. Handles `short_term_history`. Hooks into `VectorDB` to automatically store semantic interactions. `promptContext()` renders the bank for LLM injection every request. |
| `HarvestManager` | `ai/HarvestManager.java` | Unified multi-step state machine on `END_CLIENT_TICK`. Quantity comes from the LLM's `quantity` field (defaults 16 blocks / 3 mob drops). Supports `maxDurationTicks` for time-limited harvesting ("mine for 60s"). Ids validated through `RegistryResolver`. **Ore chains**: `ORE_SOURCES`/`BLOCK_DROPS` maps — Baritone mines the ore blocks (incl. deepslate variants) while the counter watches the DROP item (`diamond`, not `diamond_ore`). **Tool gating**: `REQUIRED_PICKAXE` per drop (diamond/emerald/redstone/gold → iron pick; iron/lapis/copper → stone; coal/stone → wooden); missing tool = harvest parked on the LIFO stack + `CraftPlanner` runs first, harvest auto-resumes after (announces the numbered plan). `cancel` (soft, current task only, stack continues — `AIStateManager.cancelCurrent()`) vs `stop` (kill switch, wipes stack). Delivery priority: explicit `chest_coords` > requester (walk back + drop) > memory chest > hold. **Chest Fallback**: Verifies container existence at destination; scans 3-block radius if missing; falls back to dropping items at feet. LIFO integration via `captureAndPause()` (serializes REMAINING quantity and time, requires phase to be active) and `finishPlan()` → `AIStateManager.taskCompleted()`. **Block chain**: MINING (Baritone mines, count watched vs quantity) → RETURN_TO_PLAYER (repath to requester's live position every 2s) → within 2 blocks `drop_items` at their feet → IDLE. **Porkchop Workflow (hunt chain)**: `MOB_DROPS` map (pig→porkchop, cow→beef, chicken→chicken, sheep→mutton, rabbit→rabbit); HUNTING: between kills, walks over the nearest matching ground `ItemEntity` (24-block scan) to vacuum drops — kills alone never raise the inventory count — then re-feeds `requestAttack(mob)` until 3 drops collected, then return + drop. `attack <farm animal>` auto-routes into the hunt chain (singular-ized, requires a requester). GOTO_CHEST/DEPOSITING remain the explicit `deposit_chest` container path (`useItemOn` + `QUICK_MOVE` + `closeContainer`). Combat interrupts resume via `reissueCurrentStep()`. |
| `AIActionBridge` | `ai/AIActionBridge.java` | Re-schedules onto the game thread via `Minecraft.getInstance().execute(...)`, sends the `chat` value with `getConnection().sendChat(...)` (≤100 chars), parses the full action grammar: `goto X Y Z`, `mine <block>`, `mine_area X1 Y1 Z1 X2 Y2 Z2`, `follow <player>`, `attack <name>`, `eat`, `click_respawn`, `stop`, `equip`. Shared safe integer parsing (floors decimals); any malformed coordinates trigger a safety `stop`. Handles `"nearby"` and `"nearby_player"` queries for chest delivery. Aggressive target cleanup ensures "follow me" targets the exact requesting username. Placeholder sanitizer (`cleanTarget`): small models echo the instruction card literally — strips angle brackets/quotes, keeps text after `/`, and resolves placeholders or self-references to the requesting player's name. |
| `BaritoneBridge` | `ai/BaritoneBridge.java` | Direct calls into the official Baritone API. Combat/Eat interrupt support: `pauseForCombat()` saves the current custom goal + cancels everything; `resumeRememberedGoal()` re-issues it. Compiles against `libs/baritone-api-fabric-26.2-SNAPSHOT.jar`. Guards: mod-presence check and `LinkageError` handler that diagnoses the obfuscated-standalone-jar case. Dynamically toggles `allowBreak`/`allowPlace` during combat chases based on `combatAllowBlocks`. |
| `PlayerInteractionManager` | `ai/PlayerInteractionManager.java` | Controls spontaneous interactive mini-games (Inspect Gear, Copycat, Hide-and-Seek) with nearby players. Preempts idle behaviors when triggered by `IdleBehaviorManager`. |
| `SurvivalMonitor` | `ai/SurvivalMonitor.java` | Per-tick common-sense layer on `ClientTickEvents.END_CLIENT_TICK` (main thread). **Friendly whitelist** (`FRIENDLY_PLAYERS`): never targeted, attacks refused. **Combat interceptor**: on damage taken or health < 12.0f; pauses Baritone (goal cached), locks onto threat entity across ticks. PvP movement engine: chase logic, sprinting, jump assists. Pathfinding during combat respects the `combatAllowBlocks` setting. **Drowning Prevention**: <60 ticks air triggers `pauseForCombat()`, holds jump to surface, and looks up/attacks to break blocking ceiling blocks; resumes task when air hits max. **Advanced Auto-eat**: checks on every tick. If health < 12 and hunger < 20, force-eats to trigger regen. If hunger < 14, eats. Stages food via `InventoryHelper.stageFoodInHotbar`. `beginEating` pauses Baritone logic so walking doesn't cancel eating; `stopEating` resumes. Weapon staging delegates to `InventoryHelper.equipBestWeapon` (slot 0). **Golden apple override**: health <10 in combat stages golden apple. **follow / follow_protect**: Baritone follow + monsters engaged. **Delayed respawn chat**: chat from dead players queued and flushed on revive. `clearAllOrders()` = combat wipe for stop override. |

**26.2 naming notes:** this version ships unobfuscated Mojang names — `net.minecraft.client.Minecraft` (not `MinecraftClient`), `ClientPacketListener` (not `ClientPlayNetworkHandler`), `LocalPlayer`, `Component.getString()`, and record-style `GameProfile.name()`/`id()`. `Minecraft` extends `ReentrantBlockableEventLoop`, so `Minecraft.getInstance().execute(...)` is the main-thread scheduler. `SwordItem` no longer exists — weapons/food are data-driven: detect via `stack.has(DataComponents.WEAPON)` / `DataComponents.FOOD`, rank damage via `DataComponents.ATTRIBUTE_MODIFIERS` entries matching `Attributes.ATTACK_DAMAGE`. Hotbar slot: `Inventory.getSelectedSlot()`/`setSelectedSlot(int)`. Respawn: `LocalPlayer.respawn()`; death check: `isDeadOrDying()`. Inventory clicks: `MultiPlayerGameMode.handleContainerInput(containerId, slot, button, ContainerInput, player)` — `handleInventoryClick`/`ClickType` renamed to `handleContainerInput`/`net.minecraft.world.inventory.ContainerInput` (`QUICK_MOVE`, `SWAP`, …). `ResourceKey.location()` renamed `identifier()` (dimension ids: `level().dimension().identifier()`). `isEdible()` gone — use `stack.has(DataComponents.FOOD)`.

**Default model:** the shipped default is `llama3.1:8b`; the model ID is fully configurable from the dashboard.

**Baritone builds (critical):** `baritone-standalone-fabric-*.jar` is ProGuard-obfuscated — every `baritone.api` method is renamed to `a`/`b`, so both reflection lookups and direct API calls fail against it (`NoSuchMethodException` / `NoSuchMethodError`). Use the **api** build everywhere: `libs/baritone-api-fabric-26.2-SNAPSHOT.jar` for compiling, and the same api jar (not standalone) in `run/mods` / the game's mods folder. Source: https://github.com/IzumiiKonata/baritone (branch `26.2`), `./gradlew build`, jars land in `dist/`.

**Verified:** `gradlew build` passes; the LLM response parser was smoke-tested standalone against clean JSON, markdown-fenced JSON, prose-wrapped JSON, malformed output, garbage envelopes, and missing keys (all fall back safely).

Welcome to the official developer documentation and repository guide for the **am-ai mod**. This client-side utility mod for Minecraft version 26.2 bridges an in-game avatar with a locally hosted Large Language Model (specifically optimized for Google's llama3.1:8b running via Ollama) and orchestrates real-time movement, pathfinding, and world interactions utilizing the Baritone A-star pathfinding engine.

## System Overview

The **am-ai mod** moves away from raw TCP headless clients and operates entirely inside the standard Minecraft Java Edition game client as a Fabric client-side mod. This approach offers significant advantages:

* **Physics Offloading:** The official Minecraft client manages gravity, collision box detection, packet updates, and block rendering natively, removing the need to write physics engines.
* **Complex Movement via Baritone:** Leverages Baritone's advanced A-star algorithms to plan complex paths, jump over vertical obstacles, clear pathways, and mine specific blocks.
* **Zero-Cloud Dependency:** Communicates locally via HTTP with an Ollama endpoint on your PC, keeping data local and avoiding cloud API costs or latencies.

## Core Architecture & Multi-Threading Model

Minecraft runs its physics, tick updates, and rendering pipelines on a single primary loop: **The Main Game Thread**. Blocking this thread for even a fraction of a millisecond causes immediate micro-stutters or complete game freezes.

To prevent this, the mod distributes processes across three isolated execution layers:

Swing Event Dispatch Thread -> Spawns Async Settings & Monitoring Dashboard JFrame Window.
Ollama Network Thread -> Dispatches Asynchronous HTTP requests to Local LLM.
Main Game Thread -> Thread-safe scheduled execution of Baritone command hooks.

### Thread Concurrency Rules:

1. **Network Offloading:** Every HTTP post to the Ollama endpoint must execute on an asynchronous worker thread spawned by our HTTP Client (or within a Swing Worker Thread).
2. **The Main Thread Bridge:** Any action returned by the LLM (like chatting back to the server, jumping, walking, or targeting coordinates) must be scheduled back onto the main game thread. This is accomplished using Minecraft's task scheduler wrapper:

MinecraftClient.getInstance().execute(() -> {
// Code executed here is safe to interact with world block data and Baritone API
});

## The Swing GUI Control Dashboard

When Minecraft boots up, the Client Initializer safely triggers the creation of a native Java Swing JFrame. This GUI runs outside of the main game client and serves as an interactive telemetry station.

* **Top Navigation & SlidingPanel**: Replaces the standard rigid `JTabbedPane`. Clicking a tab (Console, Settings, Whitelist) triggers a `SlidingPanel` transition that paints the incoming and outgoing panels into memory buffers and smoothly slides them across the screen using a high-framerate Swing Timer.
* **Hold-to-Confirm Buttons (`HoldButton`)**: High-risk actions like "EMERGENCY STOP", "Reset Memory", and "Reset Feelings" are powered by a custom `HoldButton`. The user must press and hold the button for a full second, while a progress bar visually fills the button's background. Releasing early cancels the action.
* **Settings Area**: Modifies the endpoint URL, model configurations, and command prefix, and displays the state of the LLM parser.
* **Telemetry Area**: A custom scroll-pane displaying a detailed text console logs.
* **Direct Console**: Allows sending prompts to the LLM directly from the host PC window without using in-game chat.

### AI and Context
- **OllamaNetworkClient**: Bridges the LLM. Rejects actions if `BaritoneBridge` is busy. Uses JSON payloads.
  - Injects `AIMemoryStore.promptContext()` for persistent relationships.
- **AIActionBridge**: Translates `"goto"`, `"mine"`, `"sleep"`, `"attack"`, etc. into state changes. Canonicalizes LLM actions to correct model hallucinations.
- **AIMemoryStore**: Persists short-term task recall and long-term player affinities to disk (`am-ai-memory.json`).
- **ReactiveChatManager**: Responds to immediate visual/audio stimuli (damage, ores, chat mentions) without user prompting.
- **SleepManager**: Automatically pathfinds to beds, sleeps when requested or when complaining at night, and wakes up.
- **IdleBehaviorManager**: Manages autonomous actions when the bot is bored. Randomly triggers looking around, staring at animals, spontaneous gift-giving to favored players (with happy dance emotes), or wandering to touch grass and pick flowers (fake gathering counts if drops are missing). Also handles night-time sleep complaints, rain complaints, and pet-like drifting towards nearby favorite players.
- **PlayerInteractionManager**: Handles spontaneous mini-games with nearby players during idle time (0.1% chance per tick). Defines interactions like Inspecting (staring and complimenting gear), Copycat (mimicking player movements), and Hide-and-Seek (running away to hide and sneak). Uses `AIStateManager.preemptForNewTask` to prioritize these over normal idle behavior.
- **ItemAttractionManager**: Implements 'Shiny Object Syndrome'. Automatically scans for rare items (diamonds/emeralds/gold), preempts the current task to stare at them for 10 seconds, then resumes.
- **EmoteManager**: Handles physical emotes like rapid sneaking (teabagging/happy dance).

## JSON Configuration & Persistence System

Settings are saved locally inside your `.minecraft` directory in a clean JSON configuration profile: `config/ai_companion_settings.json`.

The config engine uses Minecraft’s natively bundled **GSON** parser to serialize and deserialize this file. This eliminates the need to package heavy external configuration libraries inside your mod binary.

## In-Game Event Interception & Chat Hooks

A custom Fabric Mixin intercepts all incoming server chat packets at the networking protocol layer.

1. **Network Packet Arrival:** The game processes a client-bound chat message packet (0x41 in 26.2).
2. **String Extraction:** The mixin parses the chat component, extracting the sender's identifier and plain message text.
3. **Prefix Filtering:** Checks if the text begins with the configured command prefix (default: !ai), ensures the sender is not the local player, and verifies that the system is enabled.
4. **Queue & Dispatch:** Dispatches to the background network thread to query Ollama.

## Ollama API Integration & Llama3.1:8b System Prompt

The API payload is wrapped with a strict system constraint:

Two personas (see `OllamaNetworkClient.Source`):
- **IN_GAME** — `PERSONA_PROMPT`: "You are 'am-ai', a witty, self-aware, conversational AI companion playing Minecraft live with your friends…" (Neuro-sama-inspired; jokes/teasing allowed, actions decided implicitly).
- **DASHBOARD / SYSTEM** — `STRICT_PROMPT`: flat command terminal.

The persona schema is now six-field: `{"chat", "action", "target", "quantity", "chest_coords", "duration_seconds"}` — quantity drives harvest counts (no hardcoded caps, clamped 0–2304), `chest_coords` ("X Y Z") drives goto destinations and chest deliveries, `duration_seconds` applies a time limit to gathering and farming tasks, `target` carries namespace ids / entity names (inline action args still accepted and win). Live telemetry (`current_xyz`, `world_age_days`, and a dynamic `inventory` summary) is appended to every system window. Legacy strict card output `{"chat", "action"}` remains valid:
`goto <X> <Y> <Z>`, `mine <block_id>`, `mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>`, `follow <player_name>`, `attack <singular_mob_or_player_name>`, `eat`, `drop_items <item_id>`, `deposit_chest <X> <Y> <Z>`, `sneak`, `unsneak`, `click_respawn`, `stop`, `''` (empty = just chatting / maintaining an active plan).
The persona card carries CRITICAL OPERATIONAL STRUCTURAL RULES: "follow me" MUST produce `follow <name>` (never guessed `goto` coordinates), "stop" produces `stop`, animal-drop requests (porkchop/beef/leather) produce `attack <singular mob>` (never `mine_pigs`-style inventions), and item requests rely on inventory telemetry to choose whether to output `drop_items` (if in stock) or `mine` (to gather it).
Every request appends the memory bank from `AIMemoryStore.promptContext()` to the system window.

## Thread-Safe Baritone API Bridge

The returning action string is parsed and translated into active game commands. This translation must take place on the main game loop, scheduled inside `MinecraftClient.getInstance().execute()`.

### Parsing & Dispatch Mapping:

* **The chat Parameter:**
  MinecraftClient.getInstance().player.networkHandler.sendChatMessage(chatText);

* **The action Parameter ("goto X Y Z"):**
  Parses the coordinate parameters and instantiates a Baritone GoalXZ or GoalBlock.
  GoalBlock targetGoal = new GoalBlock(targetX, targetY, targetZ);
  BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(targetGoal);

* **The action Parameter ("mine block_id"):**
  Looks up the Block ID from the registry and sets the primary Baritone search and mine task.
  Block targetBlock = Registries.BLOCK.get(Identifier.of("minecraft", blockId));
  BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().mine(targetBlock);

* **The action Parameter ("stop"):**
  BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();

## Installation, Compilation & Run Instructions

### 1. Configure the Build Environment

Add the appropriate dependencies in your build.gradle file. Ensure you define dependencies for Minecraft 26.2, Fabric API, and the compiled Baritone library for 26.2 without any outdated mappings setup:
```
dependencies {
    // Configure Minecraft (version 26.2), Fabric Loader, and the official Fabric API          dependencies here.
    // Ensure you target the correct libraries compatible with your local environment for version 26.2.
    // NOTE: In Minecraft version 26.2, standard yarn mappings are bypassed or handled dynamically.
    // Add the local or fetched Baritone API compiled JAR here.
    // For example, linking a local dependency folder:
    // compileOnly files("libs/baritone-api-fabric-26.2.jar")
}
```

### 2. Verify Your Local LLM Engine

Ensure your local Ollama server is running and has the llama3.1:8b model loaded:
ollama run llama3.1:8b

To verify the engine is responding correctly, send a test curl query:
curl http://localhost:11434/api/generate -d '{
"model": "llama3.1:8b",
"prompt": "Say hello!"
}'

### 3. Compile the Companion Mod

Use the Gradle wrapper to build the client binary:
./gradlew build

Once compilation is complete, move the output jar to your Minecraft mods folder:
cp build/libs/am-ai-1.0.0.jar ~/.minecraft/mods/

### 4. Run the Game Client

Launch Minecraft version 26.2 via Fabric. The client will open and simultaneously launch the external Swing settings control dashboard window.

## Troubleshooting & Common Failure Modes

1. **Game Crashes with ConcurrentModificationException:**
    * **The Cause:** You triggered a Minecraft or Baritone task directly from the background HTTP network worker thread.
    * **The Fix:** Ensure all actions are wrapped inside MinecraftClient.getInstance().execute(() -> { ... }); to queue and run them safely on the main rendering thread.

2. **Swing UI Freezes and Crashes:**
    * **The Cause:** The UI loop tried to run an HTTP network connection task directly on the Event Dispatch Thread (EDT).
    * **The Fix:** Ensure all OllamaClient requests run asynchronously (using CompletableFuture or external task execution pools).

3. **Baritone Errors Out on Coordinate Parsing:**
    * **The Cause:** Gemma returned empty space tokens or incorrect coordinate ranges.
    * **The Fix:** Wrap coordinate parsing inside a robust try-catch block inside AIActionBridge.java. If coordinates fail parsing, fall back to stop action to maintain entity stability.