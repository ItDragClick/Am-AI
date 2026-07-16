# am-ai Bot Capabilities Guide

This document lists every command, feature, and autonomous behavior that the am-ai companion bot is currently capable of executing in Minecraft (v26.2).

- **Long-Term Memory (RAG)**: The bot uses a built-in Vector Database to store every interaction. When you talk to it, it semantically searches its database and injects relevant past conversations into its prompt, allowing it to remember jokes, events, and facts from hours or days ago.
- **Live Telemetry**: Every prompt carries the bot's real state — position, health, hunger, armor, XP, effects, inventory, mount, dimension, biome, time/weather, server IP, ping, and the online player list — so it answers questions about itself from facts, not guesses.

## 🗣️ Direct Commands
The bot listens to chat messages (prefixed with `!ai` by default, or configurable in the Dashboard). The LLM processes your natural language and converts it into the following backend commands:

- **Movement & Pathfinding**
  - `goto <X> <Y> <Z>`: Uses Baritone to pathfind to exact coordinates.
  - `follow <player>`: Follows a specific player around the world.
  - `follow_protect <player>`: Follows a player and automatically engages hostile mobs that get too close.
  - `mount` / `dismount`: Walks to the nearest horse, camel, boat, minecart, saddled pig, or strider (or one you name) and rides it. Movement commands auto-dismount first.
  - `stop`: Immediately halts all current movement and clears the task queue. (Can be explicitly invoked with `!ai stop` to bypass the LLM).
  - `cancel`: Skips the current active task and moves to the next one in the queue.

- **Resource Gathering & Crafting**
  - `mine <block>`: Mines a specified block. Quantity comes from your request ("mine 64 stone"), and you can time-limit it ("mine for 60 seconds"). Ore requests mine the ore but count the **drop** (asks for `diamond`, mines `diamond_ore` + deepslate variants). Silk-touch aware. If it lacks the right pickaxe tier, it parks the job, crafts the pickaxe first, then resumes automatically.
  - `craft <item>`: Fully autonomous crafting engine. If you ask for an `iron_sword`, the bot will calculate the recipe recursively (needs iron + sticks), mine wood, craft a table, mine stone, craft a furnace, mine iron, smelt it, and craft the sword! Supported: tools, swords, armor, basics.
  - `mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>`: Clears out all blocks within a specified 3D coordinate box.
  - `farm` / `#farm`: Runs Baritone's farm process — harvests grown crops and replants in a radius around it, until told to stop or the time limit runs out.
  - **Stock counting**: Ask for 120 cobblestone while holding 100 and it mines 20 more, then delivers the whole stack. Enough in the backpack already? It skips straight to delivery.

- **Combat & Interaction**
  - `attack <entity>`: Hunts down and attacks a specified mob or player. If you ask for animal drops (like "get me porkchops"), it will automatically convert this into hunting pigs — and it walks over the drops to pick them up between kills.
  - `eat`: Forces the bot to eat food from its inventory.
  - `sleep`: Scans the nearby area for a bed, pathfinds to it, and sleeps.
  - `leave_bed`: Wakes up and gets out of bed.
  - `sneak` / `unsneak`: Toggles the sneaking state.

- **Inventory Management**
  - `drop_items <item>`: Drops a specific item from its inventory at your feet.
  - `deposit_chest <X> <Y> <Z>`: Walks to a chest at the given coordinates and deposits items. If the chest isn't there, it scans a 3-block radius, and drops the haul at its feet as a last resort.
  - `equip`: Automatically equips the best available armor in its inventory.

---

## ⚔️ Combat Skills

The bot fights like a player, not a mob. Everything below is toggleable from the Dashboard.

### Weapons & Targeting
- **Weapon Ranking**: Scans the whole inventory and stages the best weapon in the hotbar, biased by your Weapon Priority setting (Swords / Axes / Highest Damage).
- **Critical Hits**: Kills its sprint, jumps, and lands the swing on the way down for the 1.5× crit — exactly like a real player. Normal sword/axe swings still wait out the full attack cooldown, so every hit is a strong hit.
- **Bow & Crossbow**: When a target sits 8–20 blocks away with line of sight, it stands still and shoots instead of chasing. It **leads the shot**, predicting your left/right movement and compensating for arrow drop. Jumping won't fool it, and it swaps back to melee when you close in.
- **Shields**: Auto-equips a shield to the offhand and raises it when a threat faces it, when an archer is mid-draw, when an arrow is already in flight toward it, or when a creeper is about to blow. It never swings through its own raised shield.

### The Stun Slam (Axe → Mace)
- **Shield Break**: If you block, the bot swaps to its best axe and spams paced swings starting just before true reach (3.2 blocks) to disable your shield fast. While chopping, it quietly pre-stages the mace into its hotbar so the follow-up costs zero time.
- **Punish**: The instant your shield drops, the mace is out and swinging — a burst of paced hits, then back to the normal weapon.

### The Mace Air Combo
- **Jump + Wind Charge**: Jumps and throws a wind charge at its own feet on the same tick, so the burst stacks on the jump for maximum height.
- **Falling Smash**: Swaps to the mace mid-air, tracks your live position (not a stale prediction), drifts to stay on top of you, and spams swings through the whole descent — fall distance is the damage.
- **Fail-safes**: No wind charge in the inventory = never attempted. Clear miss = it bails so the MLG water bucket can save the landing. A landed smash negates the fall damage, so no bucket is wasted. Never used against creepers or while mounted.

### Mounted Spear (Lance)
- **Charge Passes**: On a horse or camel with a spear, it rides **away** to get a run-up, re-braces the spear, then charges straight at your body center — contact damage scales with ride speed, so a standing hit is worthless. After blowing past, it loops around for another pass.
- **Brace Watchdog**: The kinetic brace times out if held too long; the bot notices and re-presses so a charge never lands limp.

### Survival Under Fire
- **Creeper Handling**: Backpedals with the shield up instead of trading hits; nearby creepers are engaged even while idle.
- **Proactive Defense**: While working, hostiles within 9 blocks get dealt with before they interrupt the job. Neutral mobs (zombified piglins, wolves) are never first-struck.
- **Low-Health Retreat**: Backs off, eats, and re-engages once healed (threshold configurable).
- **Combat Eating**: Retreats while eating instead of standing still as a free crit dispenser — and it lowers the shield first, since a raised shield blocks the meal.
- **MLG Water Bucket**: Long falls trigger a bucket clutch, then it scoops the water back up.

---

## 🧠 Autonomous Behaviors (No Commands Required)

The bot doesn't just wait for your orders. It has a mind of its own and reacts to the world around it dynamically.

### 1. Survival Instincts
- **Auto-Eating**: If health drops below 12 and hunger is not full, it will automatically pause its task, eat food to regenerate, and resume.
- **Golden Apples**: If health drops below 10 during active combat, it will prioritize eating an Enchanted Golden Apple to survive.
- **Drowning Prevention**: If the bot is underwater for too long and running out of air, it will abandon its task, swim to the surface, and break any blocks blocking its path to air.
- **Self Defense**: If the bot takes damage from an entity, it will automatically lock onto the threat and fight back — whatever hit it, at any range.
- **Auto-Sleep**: Genuinely idle at night (or in a thunderstorm), it finds the nearest bed on its own. It never abandons a job or a follow for a nap.

### 2. Idle Behaviors (Boredom)
When the bot has no active tasks and is waiting around, it will begin to exhibit lifelike idle behaviors:
- **Nighttime Complaints**: When it gets dark, the bot complains in chat about being tired or scared of the dark, then seeks out a bed.
- **Spontaneous Gifts**: If a favored player is standing nearby, the bot has a chance to look at them, say something sweet, drop a (cheap!) item at their feet as a gift, and do a happy dance. It won't hand out your diamonds.
- **Grass Touching / Flower Picking**: The bot will cure its boredom by wandering off to "touch some grass" (breaking `short_grass` or `tall_grass`) or picking a pretty flower (`poppy` or `dandelion`). Grass that doesn't drop items is handled seamlessly.
- **Fidgeting**: It will occasionally look around randomly, stare at nearby animals, or stare at the sky.
- **Big Goals**: Bored for over 3 minutes, it starts a project of its own — chopping wood, exploring, or hunting.
- **Politeness**: Idle behaviors respect your builds — idle wandering won't break or place blocks unless you allow it in the Dashboard.

### 3. Reactive Behaviors (Lifelike Interactions)
- **Shiny Object Syndrome (Item Attraction)**: If a rare item (`diamond`, `emerald`, `gold_ingot`, etc.) is dropped nearby, the bot may get distracted (25% of the time), pause its task, walk over, stare in awe for 10 seconds, then resume. A failed roll means it ignores shinies for a minute — no obsessive re-checking.
- **Mini-Games**: Spontaneously starts games with nearby players — Inspect Gear (stares and compliments your armor), Copycat (mimics your movements), and Hide-and-Seek (runs off to hide; find it within 60 seconds and it rewards you with +5 relationship).
- **Weather Reactions**: If it starts raining while the bot is outside, it will complain in chat about getting wet.
- **Damage & Discovery**: Reacts in chat to taking hits, spotting ores, and being mentioned.

### 4. Memory & Affinity System
The bot remembers how you treat it. Scores range from -100 (Hatred) to 100 (Love), and every new player starts at 0 (neutral).
- **Holding Grudges**: If a player kills the bot, it deducts 30 points and permanently remembers the murder — that fact rides along in every future prompt. Attacking it costs 10 per fight.
- **Tiers**: Below -60 it's blacklisted — the bot refuses your commands outright and may take swings at you while idle. Between -60 and 0 it just refuses to help or play, with grumpy excuses. At 0 or above, it's normal.
- **Making Up**: Saying "sorry" pulls a hated score back up to -60. Dropping a flower for it is real forgiveness — a hated player resets to 0, anyone else gains +10. Kill a player it hates and its revenge is satisfied (their score jumps to -40).
- **Mocking Enemies**: If a player dies in the server (e.g., falls in lava), the bot intercepts the server death message. If it dislikes that player, it will brutally mock them in chat. If it likes them, it will act surprised or laugh playfully.

---

## 💻 The Control Dashboard

The desktop application allows for complete out-of-game control over the bot via a premium Swing interface (Luna UI, FlatLaf dark theme).

- **Sliding Navigation**: Smoothly animate between Console, Settings, and Whitelist tabs.
- **Task Tracker**: Monitor exactly what the bot is doing and what tasks are queued up next.
- **System Status**: Live connection state, model, endpoint, and uptime.
- **Activity Overview**: Real session counters — tasks completed, play time, LLM requests, and fights.
- **Combat & Survival Toggles**: Shield use, bow/crossbow, mace, crit hits, MLG water, flee on low HP, auto-defend while working, auto-sleep, combat/idle/follow block-editing permissions.
- **Combat Tuning Sliders**: Axe/Mace spam delay, max mace punish hits, spear charge distance, shield break range, mace combo cooldown, eat unshield warmup, crit jump cooldown, and flee HP threshold. Changes apply live — no restart.
- **Presets**: Save the whole configuration under a name, load it back, or delete it. Saving opens a small dialog to name the preset.
- **Quick Toggles**: The most-used switches in the sidebar, applied the moment you click them.
- **Hold-to-Confirm Safety Buttons**: Hold the "EMERGENCY STOP", "Reset Memory", or "Reset Feelings" buttons for 1 full second to trigger them, preventing accidental clicks.
- **Manual Control**: Bypass the LLM and send commands directly to the bot via the manual prompt field.
- **Whitelist Management**: Only allow specific players to command the bot. Whitelist edits are dashboard-only — nobody can talk their way onto it in chat.
