# am-ai mod (v26.2) — System Architecture & Developer Guide

## Implementation Status & Code Map (updated 2026-07-12)

The full architecture described below is now implemented and building (`gradlew build` → `build/libs/am-ai-a0.1.jar`). All AI code lives in the client source set:

| Class | Path (under `src/client/java/com/itdragclick/client/`) | Role |
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

When Minecraft boots up, the Client Mod Initializer safely triggers the creation of a native Java Swing JFrame. This GUI runs outside of the main game client and serves as an interactive telemetry station.

* Settings Area: Modifies the endpoint URL, model configurations, and command prefix, and displays the state of the LLM parser.
* Telemetry Area: A custom scroll-pane displaying a detailed text console logs.
* Direct Console: Allows sending prompts to the LLM directly from the host PC window without using in-game chat.

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

Both output `{"chat": "...", "action": "..."}` with the allowed action set:
`goto <X> <Y> <Z>`, `mine <block_id>`, `mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>`, `follow <player_name>`, `attack <singular_mob_or_player_name>`, `eat`, `drop_items <item_id>`, `deposit_chest <X> <Y> <Z>`, `sneak`, `unsneak`, `click_respawn`, `stop`, `''` (empty = just chatting / maintaining an active plan).
The persona card carries CRITICAL OPERATIONAL STRUCTURAL RULES: "follow me" MUST produce `follow <name>` (never guessed `goto` coordinates), "stop" produces `stop`, animal-drop requests (porkchop/beef/leather) produce `attack <singular mob>` (never `mine_pigs`-style inventions), in-inventory gifts use `drop_items` (not `deposit_chest` unless a chest is physically specified).
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