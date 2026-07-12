# am-ai Bot Capabilities Guide

This document lists every command, feature, and autonomous behavior that the am-ai companion bot is currently capable of executing in Minecraft (v26.2).

## 🗣️ Direct Commands
The bot listens to chat messages (prefixed with `!ai` by default, or configurable in the Dashboard). The LLM processes your natural language and converts it into the following backend commands:

- **Movement & Pathfinding**
  - `goto <X> <Y> <Z>`: Uses Baritone to pathfind to exact coordinates.
  - `follow <player>`: Follows a specific player around the world.
  - `follow_protect <player>`: Follows a player and automatically engages hostile mobs that get too close.
  - `stop`: Immediately halts all current movement and clears the task queue. (Can be explicitly invoked with `!ai stop` to bypass the LLM).
  - `cancel`: Skips the current active task and moves to the next one in the queue.

- **Resource Gathering & Crafting**
  - `mine <block>`: Mines a specified block (defaults to 16 blocks).
  - `craft <item>`: Fully autonomous crafting engine. If you ask for an `iron_sword`, the bot will calculate the recipe recursively (needs iron + sticks), mine wood, craft a table, mine stone, craft a furnace, mine iron, smelt it, and craft the sword! Supported: tools, swords, armor, basics.
  - `mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>`: Clears out all blocks within a specified 3D coordinate box.
  - `farm` / `#farm`: Scans an 18-block radius for fully grown crops (wheat, carrots, potatoes, beetroots), harvests them, and automatically replants the seeds.

- **Combat & Interaction**
  - `attack <entity>`: Hunts down and attacks a specified mob or player. If you ask for animal drops (like "get me porkchops"), it will automatically convert this into hunting pigs.
  - `eat`: Forces the bot to eat food from its inventory.
  - `sleep`: Scans the nearby area for a bed, pathfinds to it, and sleeps.
  - `leave_bed`: Wakes up and gets out of bed.
  - `sneak` / `unsneak`: Toggles the sneaking state.

- **Inventory Management**
  - `drop_items <item>`: Drops a specific item from its inventory at your feet.
  - `deposit_chest <X> <Y> <Z>`: Walks to a chest at the given coordinates and deposits items.
  - `equip`: Automatically equips the best available armor in its inventory.

---

## 🧠 Autonomous Behaviors (No Commands Required)

The bot doesn't just wait for your orders. It has a mind of its own and reacts to the world around it dynamically.

### 1. Survival Instincts
- **Auto-Eating**: If health drops below 12 and hunger is not full, it will automatically pause its task, eat food to regenerate, and resume.
- **Golden Apples**: If health drops below 10 during active combat, it will prioritize eating an Enchanted Golden Apple to survive.
- **Drowning Prevention**: If the bot is underwater for too long and running out of air, it will abandon its task, swim to the surface, and break any blocks blocking its path to air.
- **Self Defense**: If the bot takes damage from an entity, it will automatically lock onto the threat and fight back.

### 2. Idle Behaviors (Boredom)
When the bot has no active tasks and is waiting around, it will begin to exhibit lifelike idle behaviors:
- **Nighttime Sleeping**: If it gets dark outside (between 13000 and 23000 ticks), the bot will complain in chat about being tired or scared of the dark, and automatically seek out a bed to sleep in.
- **Spontaneous Gifts**: If a favored player (Affinity ≥ 50) is standing nearby, the bot has a chance to look at them, say something sweet, drop an item at their feet as a gift, and do a happy dance (teabagging).
- **Grass Touching / Flower Picking**: The bot will cure its boredom by wandering off to "touch some grass" (breaking `short_grass` or `tall_grass`) or picking a pretty flower (`poppy` or `dandelion`). Grass that doesn't drop items is handled seamlessly.
- **Fidgeting**: It will occasionally look around randomly, stare at nearby animals, stare at the sky, or jump up and down.
- **Pet-like Drifting**: When idle, if a favored player is 4-10 blocks away, the bot will slowly drift towards them.

### 3. Reactive Behaviors (Lifelike Interactions)
- **Shiny Object Syndrome (Item Attraction)**: If a rare item (`diamond`, `emerald`, `gold_ingot`, etc.) is dropped nearby, the bot will pause its current task, pathfind to the item, stare at it in awe for 10 seconds, and then resume whatever it was doing.
- **Weather Reactions**: If it starts raining while the bot is outside, it will complain in chat about getting wet.

### 4. Memory & Affinity System
The bot remembers how you treat it. Affinities range from -100 (Hatred) to 100 (Love).
- **Holding Grudges**: If a player kills the bot, it will immediately deduct 30 Affinity points from that player and remember the murder.
- **Mocking Enemies**: If a player dies in the server (e.g., falls in lava), the bot intercepts the server death message. If it hates that player (Affinity < 0), it will brutally mock them in chat. If it likes them, it will act surprised or laugh playfully.

---

## 💻 The Control Dashboard
The desktop application allows for complete out-of-game control over the bot via a premium Swing interface.
- **Sliding Navigation**: Smoothly animate between Console, Settings, and Whitelist tabs.
- **Task Tracker**: Monitor exactly what the bot is doing and what tasks are queued up next.
- **Hold-to-Confirm Safety Buttons**: Hold the "EMERGENCY STOP", "Reset Memory", or "Reset Feelings" buttons for 1 full second to trigger them, preventing accidental clicks.
- **Manual Control**: Bypass the LLM and send commands directly to the bot via the manual prompt field.
- **Whitelist Management**: Only allow specific players to command the bot.
