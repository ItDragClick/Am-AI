# Am-AI Codebase File-by-File Explanation (v26.2)

This document provides a detailed breakdown of every file located inside the `src/client/java/com/itdragclick/client/` directory and explains its role within the Am-AI architecture.

## 1. Root Client Folder
- **`AmAIClient.java`**: The main entry point of the Fabric client mod. It runs when the game starts. It loads the configuration, initializes the Swing UI on the Event Dispatch Thread (so it doesn't block the game), registers the chat event hooks, and safely tears down the UI when the game closes.

## 2. `/config/` - Configuration & Persistence
- **`AIModSettings.java`**: Defines the data structure for the mod's settings (Endpoint URL, Model ID, Toggles for MLG Water, Follow Block Edit, Idle Behaviors, etc.). It is fully GSON-serializable.
- **`SettingsPersistenceManager.java`**: Handles the saving and loading of `AIModSettings` to and from the disk (`.minecraft/config/am-ai/ai_companion_settings.json`). It ensures that changes made in the dashboard apply instantly in-game.

## 3. `/memory/` - Memory & Relationships
- **`PlayerRelationshipDB.java`**: The absolute source of truth for the bot's "Feelings" system. It tracks the relationship score (-100 to 100) for every player. It handles logic for forgiveness (e.g., dropping flowers) and grudges (e.g., getting attacked).
- **`VectorDB.java`**: A custom RAG (Retrieval-Augmented Generation) engine. It uses Ollama's embedding API to generate and store semantic vectors for chat interactions, allowing the bot to "remember" past conversations by finding similar contexts.
- **`AIMemoryStore.java`**: Manages the short-term history and links up with the `VectorDB` to form the `promptContext()`, which is injected into the LLM prompt on every request.

## 4. `/chat/` - Inbound Communication
- **`ChatEventListener.java`**: Intercepts chat messages using Fabric's networking hooks. It filters out the bot's own messages, checks if the message starts with the command prefix (e.g., `!ai`), handles hard-stops (`!ai stop`), and dispatches valid messages to the Ollama network client.

## 5. `/net/` - Outbound Communication
- **`OllamaNetworkClient.java`**: The async HTTP client that talks to the local Ollama server. It formats the system prompt (choosing between the witty `PERSONA_PROMPT` for in-game and the strict `STRICT_PROMPT` for system requests), injects memory, sends the request, and parses the 6-field JSON response (`chat`, `action`, `target`, `quantity`, `chest_coords`, `duration_seconds`).

## 6. `/ui/` - The Dashboard
- **`AIDashboardFrame.java`**: The main Swing JFrame window. It contains all the toggles, input fields, and the console log. 
- **`SlidingPanel.java`**: A custom UI component that creates smooth, animated sliding transitions between the different tabs in the dashboard.
- **`HoldButton.java`**: A custom button used for destructive actions (like Emergency Stop). It requires the user to hold the click for a set duration while a progress bar fills up.

## 7. `/ai/` - The "Nervous System" (Action Engines)
This folder contains the core logic that translates the LLM's high-level commands into tick-by-tick actions.
- **`AIActionBridge.java`**: The main router. It takes the parsed JSON from the LLM and translates it into specific commands (e.g., routing `mine` to `HarvestManager`, `attack` to `SurvivalMonitor`). It ensures everything is safely scheduled on the main game thread.
- **`BaritoneBridge.java`**: The wrapper around the Baritone API. It provides safe methods to pathfind (`goTo`, `follow`, `mine`). It enforces settings like `allowFollowBlockEdit` to prevent Baritone from griefing the player's builds.
- **`SurvivalMonitor.java`**: Runs every tick to keep the bot alive. Handles auto-eating, shield mechanics, axe swapping, ranged combat (bows/crossbows), MLG water falling, and fleeing on low health.
- **`HarvestManager.java`**: A state machine for mining. Tells Baritone to mine blocks, checks the inventory until the requested quantity is met, and can deliver items back to a chest or player.
- **`CraftPlanner.java`**: A recursive crafting engine. It dynamically resolves dependency trees (e.g., needing to mine wood to craft a table to craft a sword) and executes the steps sequentially.
- **`AIStateManager.java`**: A LIFO (Last-In-First-Out) task stack. It allows the bot to pause a long task (like mining 64 diamonds), go do something else (like fight a zombie), and then resume the original task.
- **`IdleBehaviorManager.java`**: The personality engine. When the bot is bored, this triggers random autonomous events like wandering, picking flowers, throwing tantrums, or staring at animals, all gated by the dashboard toggles.
- **`PlayerInteractionManager.java`**: Handles spontaneous mini-games with players (Hide and Seek, Copycat) when triggered by the Idle behavior system.
- **`ItemAttractionManager.java`**: The "Shiny Object Syndrome" module. Pauses tasks if the bot sees a rare item dropped on the ground.
- **`FarmManager.java`**: Scans for fully grown crops, harvests them, and replants seeds.
- **`InventoryHelper.java`**: A utility class for safely moving items around the inventory, equipping weapons/armor, and dropping items without desyncing from the server.
- **`RegistryResolver.java`**: A fuzzy-matching utility that translates the LLM's natural language item names (e.g., "diamond sword") into strict Minecraft registry IDs (`minecraft:diamond_sword`).
- **`ReactiveChatManager.java`**: Triggers autonomous chats based on stimuli (like taking damage or finding diamonds) without waiting for a player prompt.
- **`SleepManager.java`**: Automatically handles pathfinding to and using beds when it gets dark.
- **`EmoteManager.java`**: Handles visual physical expressions, such as rapid sneaking (teabagging/happy dances).
- **`AIWhitelistManager.java`**: Maintains a runtime list of players who are allowed to issue commands to the bot.
- **`ActionHelper.java`**: A utility for triggering client-side actions like swinging arms or interacting with blocks.

## 8. `/mixin/`
- **`ExampleClientMixin.java`**: A placeholder/example Mixin file (Mixins are used to inject code directly into Minecraft's base classes).
