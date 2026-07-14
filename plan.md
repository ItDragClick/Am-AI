# AM-AI Bot — Implementation Spec (rewrite of user prompt)

Target: agent working on the `am-ai` Fabric/Forge mod. Config lives at
`/.minecraft/config/am-ai/`. Before writing new code, **first audit the existing
settings class and confirm current location of**: MLG toggle, follow-player toggle.
If they don't exist yet, add them per Section 1.

---

## 0. Priority order for this pass
1. Settings/toggle system (Section 1) — everything else reads from this.
2. Command parser (Section 2)
3. Combat rework: axe/shield/bow, low-health retreat (Section 3)
4. Feelings system (Section 4)
5. Blacklist (Section 5)
6. Memory → ChromaDB migration (Section 6)
7. Idle behavior state machine, e.g. flower-fetching (Section 7)
8. Update `CLAUDE.md` (Section 8)

---

## 1. Settings / Toggle System

Single source of truth, e.g. `AmAiSettings` backed by `settings.json` in the config
folder. Every behavior below must check its own toggle before running — no hidden
always-on behaviors. Add (at minimum) these boolean/enum keys:

```jsonc
{
  "combat": {
    "mlgWaterBucket": true,
    "useShieldWhileFighting": true,
    "useBowCrossbow": true,
    "weaponPreference": "SWORD", // SWORD | AXE | AUTO (AUTO = axe vs shield users, sword otherwise)
    "fleeOnLowHealth": true,
    "lowHealthThreshold": 8 // half-hearts
  },
  "movement": {
    "followPlayerEnabled": true,
    "followCanBreakBlocks": false,   // <- the "don't destroy my house" toggle
    "followCanPlaceBlocks": false
  },
  "idle": {
    "enabled": true,
    "canBreakBlocksWhileIdle": false,
    "canPlaceBlocksWhileIdle": false,
    "wanderRadius": 32,
    "events": {
      "fetchFlowers": true,
      "randomWander": true,
      "spinLookAround": true,
      "chitchat": true,
      "sitByFire": true
      // one flag per idle event you actually have — enumerate all real ones here
    }
  },
  "feelings": { "enabled": true },
  "blacklist": { "enabled": true, "replyWithLlmRefusal": true },
  "memory": { "backend": "chromadb" }
}
```

Every module (follow, idle, combat) reads its flags from this object at call time
(not cached at startup) so in-game `/amai set <path> <value>` commands take effect
immediately. Add a simple in-game command to list/toggle all of these without
editing JSON by hand.

**Follow-player fix**: the reported bug ("destroys my house to follow me") is caused
by follow's pathing being allowed to break/place blocks. Gate that specifically
behind `movement.followCanBreakBlocks` / `followCanPlaceBlocks`, default **false**.

---

## 2. Natural-Language Command Parser

Add an intent layer between LLM chat output and the action executor. Support at
least these intents, each mapping to a concrete executor call:

| Intent | Example phrases | Executor call |
|---|---|---|
| Move | "move forward", "go left", "walk toward x=100 z=200" | `moveDirection(axis, distance/duration)` with camera/body tween, not an instant teleport-snap |
| Turn | "spin around slowly", "look at me", "turn 180" | `turnTo(pitch, yaw, tweenDurationTicks)` — "slowly" maps to a longer tween duration, not a different mechanic |
| Break-for-duration | "break blocks for 1h", "mine here for 10 minutes" | `breakForDuration(target, durationTicks)`, respects `movement`/`idle` break toggles depending on context |
| Place-for-duration | "build a wall for 5 minutes" | `placeForDuration(block, durationTicks)` |
| Jump | "jump" | one-tick jump input |

Parsing approach: LLM classifies the message into `{intent, params}` JSON (small
system prompt, not full agent context), then a deterministic dispatcher executes it.
Keep "slowly / quickly / a bit" as a duration/speed modifier parsed into a tween-time
multiplier, not bespoke per-verb logic — this is what makes "common commands"
actually general instead of a growing pile of special cases.

---

## 3. Combat System

- **Weapon logic**: if `weaponPreference == AUTO`, detect if target has a shield
  raised → switch to axe (axes disable shields on hit); otherwise use sword.
  Manual override via `weaponPreference` setting still works.
- **Shield use**: equip shield in offhand automatically when
  `combat.useShieldWhileFighting` is true and bot is not currently attacking (raise
  shield between swings, lower to attack).
- **Bow/crossbow**: if enabled and target is out of melee range, switch to
  ranged weapon; return to melee weapon when target closes distance.
- **Low health behavior**: when health ≤ `lowHealthThreshold` and
  `fleeOnLowHealth` is true → use Baritone to retreat to a safe/known location
  while eating food, **no block breaking/placing during this retreat**, then
  re-engage or continue fighting once healed above threshold.
- **MLG water bucket**: implement as its own toggleable idle/emergency action
  (place water bucket to survive a fall, pick bucket back up after landing),
  gated behind `combat.mlgWaterBucket`.
- **Offhand equip**: generic `equipOffhand(item)` / `equipMainhand(item)` /
  `unequip(slot)` commands, usable both by the command parser and internally by
  combat logic.

---

## 4. Feelings System (per player)

- Score per player, integer, **range -100 to +100**, default **0** on first meeting.
- Triggers:
    - Player hits bot → score -N (define N, e.g. -10 per hit).
    - Player chat directed at bot → LLM classifies sentiment (good/neutral/bad) →
      ± small amount.
    - Player says "sorry" (LLM-detected apology) → score moves toward -60 from
      below (i.e., clamps up to -60, not fully reset).
    - Player throws a flower at bot → if score < -60, forgive: reset to a
      positive baseline (e.g. 0 or +10); if score ≥ -60 already, small bonus.
- Thresholds:
    - score < -60 → bot may **randomly** attempt to attack that player during idle
      mode (probability roll, not guaranteed every tick).
    - score < 0 (but ≥ -60) → bot refuses to "play"/cooperate with that player
      (ignore non-essential commands, no PvP-adjacent help) but does not attack.
    - score ≥ 0 → normal behavior.
- Persist per-player score in the memory store (Section 6), keyed by player UUID.

---

## 5. Blacklist System

- `blacklist.json` (or table in ChromaDB metadata) listing player UUIDs.
- Commands from blacklisted players are **not executed**.
- Instead of a flat refusal string, send the command through the LLM with a
  system note like "you are declining this player's request, respond briefly and
  in character" so refusals sound natural and vary, rather than a hardcoded
  "I can't do that" every time.
- Blacklist is independent of the feelings system — a blacklisted player can
  still have a positive feelings score; blacklist only blocks command execution.

---

## 6. Memory System → ChromaDB

- Replace the flat `.json` files under `/.minecraft/config/am-ai/` with a
  ChromaDB collection (embeddings for chat history / semantic recall) plus a
  small structured store (SQLite or a single JSON) for exact-value fields that
  don't need semantic search: feelings scores, blacklist, settings.
- Suggested layout:
    - `am-ai/settings.json` — Section 1 config (stays plain JSON, human-editable).
    - `am-ai/players.json` — per-player structured data: feelings score,
      blacklist flag, last-seen timestamp.
    - `am-ai/memory_chroma/` — ChromaDB persistent client directory, one
      collection per player (or one collection with player-UUID metadata filter),
      storing embedded chat snippets/events for semantic recall ("what did we talk
      about last time").
- Write a one-time migration script that reads the old `.json` memory files and
  inserts their contents into the new ChromaDB collection(s) plus `players.json`.

---

## 7. Idle Behavior State Machine

General rule: **idle actions must never break/place blocks unless
`idle.canBreakBlocksWhileIdle` / `idle.canPlaceBlocksWhileIdle` are true**, same
pattern as follow (Section 1).

Example — "fetch flower" idle event:
1. Search for a flower block within `idle.wanderRadius`.
2. If found: Baritone-path to it, then mine it (client-side break is fine here
   since it's the actual target block, not an obstacle) — this does **not**
   require `canBreakBlocksWhileIdle` since it's the goal, not incidental clearing.
3. If pathing requires clearing an obstacle block and
   `idle.canBreakBlocksWhileIdle` is false: **do not break it** — try an
   alternate path; if no path exists, abandon this idle action and fall back to
   the next enabled idle event (or plain wander).
4. If no flower found within radius at all: fall back to next enabled idle
   event / idle wander. Never break through a player's house wall to "get
   outside" — that's exactly the obstacle-clearing case forbidden in step 3.

Apply the same break/place-gating pattern to every other idle event and to the
follow behavior — one shared "can I break/place right now?" check function used
everywhere, driven entirely by Section 1's settings, so there's a single place to
fix this class of bug in the future.

---

## 8. Documentation

After implementing the above, update `CLAUDE.md` to reflect:
- Final settings schema (Section 1) with all toggle keys.
- Command parser intents and how to add a new one.
- Combat weapon-selection logic.
- Feelings score rules and thresholds.
- Blacklist behavior.
- New memory architecture (ChromaDB + players.json) and where the old `.json`
  memory files went.
- Idle event list and the shared break/place-gating function.

Also note explicitly in `CLAUDE.md` which items from this spec were completed vs.
still TODO, so future sessions don't have to re-derive status from the code.