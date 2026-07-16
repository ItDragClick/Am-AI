# am-ai upgrade pass 3 — mounted spear, predictive bow, mace+wind charge, combat eating, baritone #farm

All in `src/client/java/com/itdragclick/client/`. Build gate: `gradlew build` after each item.

---

## 1. Mounted spear: real lance charges

**Problem (verified in `ai/SurvivalMonitor.engage`, lines 1000-1058):**
- Mounted branch holds `keyUp` only while `distance > STRIKE_RADIUS + 1`, then releases — horse brakes and parks next to the target; bot left-clicks like on foot. Spear speed-scaled charge damage never happens (speed ≈ 0 at contact).
- Spear right-click brace mechanic never implemented: `keyUse` is owned by shield gate (line 1086), bow loop, and eating. Holding right-click with food staged = bot eats mid-charge.
- `distance > COMBAT_TRACKING_RADIUS` mounted → full stop instead of repositioning.

**Fix — lance mode in `engage()`:**
- New condition `lanceMode = mounted && InventoryHelper.isSpear(player.getMainHandItem())` (equip best weapon already biases spear +10 when mounted).
- While `lanceMode`:
  - **Hold `keyUp` permanently** — never release inside strike radius. Horse charges through and past the target.
  - **Hold `keyUse` permanently** (brace spear). Suppress ALL other `keyUse` writers this tick: skip shield gate (early return before line 1085), skip `tickPassiveShield`, and block `beginEating`/gapple from starting while lanceMode (defer to charge-pass gaps or dismount-retreat — see item 4 interaction below).
  - **Charge-pass steering:** aim `lookAt` at target while approaching. Once target is behind (dot of horse velocity vs direction-to-target < 0, or distance increasing for ~10 ticks past contact), keep riding straight ~1.5s to bleed past, then turn: aim at a point offset ~8 blocks to the side of the target (alternate left/right per pass) and loop back for another run. Simple state: `PASS_APPROACH` / `PASS_OVERSHOOT`, static ints for timers.
  - No `gameMode.attack` calls while braced — spear contact damage is the hit. Fallback: if spear absent (broken mid-fight), lanceMode off, existing mounted melee branch takes over.
- **Verify 26.2 spear brace shape before coding:** javap the spear item class / check for a spear data component in the dev jar (`.gradle/loom-cache/minecraftMaven/...26.2.jar`). Confirm damage comes from held-use + ride speed. If spear turns out to be swing-based melee only in 26.2 → keep charge-pass movement (still correct behavior) but swing `gameMode.attack` at closest approach instead of holding use.
- Eating while mounted+combat: never stand-eat. If hunger critical during lance mode → steer AWAY from target (aim look at retreat point, keyUp held — horse outruns everything), then eat while riding away, resume passes after (rides into item 4's retreat-eat logic).

## 2. Predictive bow aim

**Problem:** `engage` aims `lookAt(eyes)` directly; `tickRangedAttack` releases at ≥20 ticks with no lead and no drop compensation. Moving targets and 15+ block shots miss.

**Fix — `aimBowAtPredicted(player, target)` helper in SurvivalMonitor:**
- Target velocity: `target.getDeltaMovement()` (server-synced enough client-side; zero out Y for ground mobs to avoid jitter, keep Y for flying: Phantom/Blaze/Ghast).
- Arrow ballistics (vanilla): full-draw speed 3.0 blocks/tick, gravity 0.05/tick², drag 0.99. Iterate flight time: `t = distance / 3.0`, recompute distance to `targetPos + vel * t`, 3 iterations converges.
- Aim point = predicted position at chest height + vertical drop compensation `0.5 * 0.05 * t * t` (scaled by drag correction ~1.1 for long shots).
- Call it every tick of the bow branch (replace the plain `lookAt` for the ranged path — engage's top `lookAt` stays for melee), including the release tick so the arrow leaves on the predicted vector.
- Crossbow branch: same helper with projectile speed 3.15.
- Release gate: only release (`keyUse` up) when full draw AND the predicted aim is set this tick. Keep the existing 20/25-tick counters.

## 3. Mace + wind charge smash (new)

**Mechanics (1.21+, still in 26.2):** mace smash damage scales with fall distance (+4 dmg/block first 3 blocks, then +2, then +1), a landed hit negates ALL fall damage and knocks back nearby entities; missing means taking the fall. Wind charge thrown at own feet launches the player up (~7 blocks) and its launch reduces accumulated fall damage. Combo: throw wind charge down → launch → swap to mace mid-air → hit target while falling = smash.

**New setting:** `useMaceAttack` (default false) in `config/AIModSettings.java` + `copy()` + dashboard checkbox "Use Mace In Attack" (`ui/AIDashboardFrame.java`, follow `useShieldWhileFighting` pattern).

**Eligibility (ALL required, checked in `engage` before melee dispatch):**
- setting on
- mace in inventory (`InventoryHelper.countItem(player, "mace") > 0` — add hasMace/find helpers)
- **wind charge in inventory (`countItem "wind_charge" > 0`) — no wind charge = no mace attempt, plain melee weapon instead** (also: `weaponScore` must NEVER pick mace as a normal melee weapon; exclude it from best-weapon scan)
- target within ~3.5 horizontal blocks, on ground, not mounted, not creeperDanger, not eating, cooldown since last smash attempt ≥ ~3s

**Smash routine — small state machine (`maceState`: IDLE / JUMPING / LAUNCHING / AIRBORNE):**
1. `JUMPING`: max-height launch = jump FIRST, throw wind charge at feet at jump apex (burst velocity stacks on jump height). Stage wind charge in hotbar + select, then `player.jumpFromGround()`, look straight down (pitch 90).
2. `LAUNCHING`: at apex (`deltaMovement.y` crosses ≤ 0.1 after the jump, ~5-6 ticks) → `gameMode.useItem(MAIN_HAND)` once, still looking straight down so the burst hits directly below. Confirm boost via `deltaMovement.y > 0.5` within a few ticks; 15-tick timeout from jump start → abort to melee.
3. `AIRBORNE`: immediately swap mace into hand. **Predictive aim + last-moment hit:**
   - Estimate remaining fall time from current height above target's ground and `deltaMovement.y` (kinematics with gravity 0.08/tick², drag 0.98 — player values, not arrow values).
   - Reuse item 2's prediction idea: predicted target position = `target.position() + target.getDeltaMovement() * ticksToImpact`, recomputed every tick. `lookAt` the predicted point (slight air-strafe with keyUp toward it if horizontal gap > 1 block).
   - Swing at the LAST possible tick for max fall distance: while falling (`deltaMovement.y < 0`, `fallDistance > 1.5`), each tick check "will target still be inside reach (≤ 3.5 to bounding box) next tick, and am I still ≥ ~2 ticks from ground?" — if yes, wait; if this is the last tick it's reachable OR landing is ≤ 2 ticks away → `gameMode.attack` + swing → IDLE, set smash cooldown.
4. Miss safety: landed (`onGround`) without hitting → IDLE + cooldown; wind-charge launch fall damage is small, and existing MLG water stays armed as backstop. Target predicted to leave reach mid-air → swing at closest-approach tick instead of never.
- While JUMPING/LAUNCHING/AIRBORNE: suppress shield gate and Baritone (stopQuietly at entry); movement keys only for the air-strafe correction above.
- Verify in dev jar: wind charge item id `wind_charge`, thrown-projectile use shape (plain `useItem` throw like snowball), mace id `mace`. One javap pass before coding.

## 4. Never stand-still eating in combat

**Problem (verified):** `tickAutoEat:361` lets critical-hunger eating start mid-combat via `beginEating` directly — no retreat, `combatEatHold` not set, `engage` keeps running against the standing bot, and the shield gate fights over `keyUse`. Bot stands, gets crit, dies. Retreat choreography exists only for low health (`fleeOnLowHealth`, line 660).

**Fix:**
- Unify: ANY eat that starts while `isInCombat()` must go through the retreat path. In `tickAutoEat`, when in combat and hunger critical (or lowHealth condition), do NOT call `beginEating` directly — set `combatEatHold = true`, `releaseMovementKeys`, `retreatFrom(player, currentThreat)`, then `beginEating` (mirror lines 668-674).
- Make retreat continuous, not one-shot: while `combatEatHold`, every ~20 ticks re-issue `retreatFrom` if threat within ~6 blocks (currently a single Baritone goal 5 blocks out — mob catches up and bot stands eating again). Bump `COMBAT_RETREAT_BLOCKS` usage to re-path away from the CURRENT threat position. Keep facing away is fine (Baritone steers); shield can't be up while eating anyway.
- Recovery condition (line 676-684) unchanged, but also exit hold if no food found mid-chain.
- Interaction with item 1: mounted → retreat by steering horse away (keyUp held, look away from threat), skip Baritone.
- Interaction with item 3: never start mace routine while `combatEatHold` or `eating` (already in eligibility).

## 5. Farm = Baritone's own #farm

**Problem:** `ai/FarmManager.java` is a hand-rolled crop state machine (scan/path/break/replant, 400-tick stuck counter). Baritone already ships a farm process that handles harvest+replant natively.

**Fix:**
- `BaritoneBridge`: new `startFarm(range)` → `BaritoneAPI.getProvider().getPrimaryBaritone().getFarmProcess().farm(range, null)` (api-jar class `baritone.api.process.IFarmProcess`; javap the dev/api jar first to confirm 26.2 signature — fallback: `getCommandManager().execute("farm")`). Add `isFarmProcessActive()` via `getFarmProcess().isActive()`, and make `stopAll`/`pauseForCombat` cancel it (`onLostControl` happens via normal cancel path — verify).
- `FarmManager` gutted to thin wrapper: `startFarming(...)` = announce + `BaritoneBridge.startFarm(range)`; `isBusy()` = `BaritoneBridge.isFarmProcessActive()`; keep the combat-freeze early return (combat pause must suspend the farm process and resume after — reuse pauseForCombat/resumeRememberedGoal flow; if farm process has no goal to remember, re-issue `startFarm` on exitCombat when it was active). Delete crop-scan/replant/stuck-counter code.
- `AIActionBridge` farm verb unchanged externally; stop override already cancels via BaritoneBridge stop + FarmManager cancel.
- Auto-sleep gate keeps working (`FarmManager.isBusy()` now reflects the Baritone process).

## 6. Attack = first-class task (stoppable, visible)

**Problem:** attack order lives only in `SurvivalMonitor.attackTargetName` — invisible to `AIStateManager`. Dashboard task line shows "IDLE - No tasks running." mid-attack; `cancel` verb (`AIStateManager.cancelCurrent`) can't touch it. `stop` verb DOES already clear it (`clearAllOrders` at `AIActionBridge:346`), but only the nuke path works.

**Fix:**
- `AIStateManager.anythingActive()` += `SurvivalMonitor.hasAttackOrder()` (new accessor: `attackTargetName != null`). `getActiveTaskDescription()` += `"ATTACK -> <name>"` branch.
- `cancelCurrent()` (cancel verb): when attack order is the active task → `SurvivalMonitor.clearAllOrders()`.
- Dashboard stop button + chat stop: already covered via `stop` verb → verify `clearAllOrders` also releases movement keys and keyUse (it calls `exitCombat(mc, false)` — confirm exitCombat's resetTransientState handles held keys, mace state, lance state).
- follow/follow_protect (`protectTarget`) same treatment: `hasFollowOrder()` in anythingActive + description + cancellable.

## 7. Auto-sleep must not interrupt work

**Problem:** `SleepManager.tickAutoSleep` gates on `AIStateManager.anythingActive() || isInCombat() || Harvest/Farm/CraftPlanner/MountManager busy` — but anythingActive misses attack + follow (item 6), and nothing checks raw Baritone activity (long `goto` journeys tracked as activeGoto are fine, but follow process / combat-paused remembered goals aren't). Bot mid-follow at night abandons the player and walks to bed.

**Fix:**
- Item 6's anythingActive extension fixes attack + follow automatically.
- Add `BaritoneBridge.isAnyProcessActive()` (pathing OR custom goal OR farm process OR follow process active — one try/catch method over the api) and gate tickAutoSleep on it too.
- Also gate on `SurvivalMonitor.isEating()` and a paused/remembered Baritone goal (`rememberedGoal != null` — mid-combat-pause counts as busy).
- Net rule: bot sleeps ONLY when truly idle — no task, no combat, no pathing, no remembered goal, not eating, not riding, not mounted-walking.

---

## Cross-cutting

- `keyUse` single-writer discipline per tick, priority order: eating > mace routine > lance brace > bow draw > shield gate > passive shield. Each higher stage early-returns before lower writers run.
- New settings key `useMaceAttack` (GSON default-tolerant), dashboard checkbox.
- `resetTransientState` clears: lance pass state, mace state + cooldown.
- Stop override cancels mace routine mid-air safely (just state = IDLE; physics finishes).

## Verification

1. `gradlew build` green per item.
2. In-game:
   - Saddled horse + spear + attack order: horse never stops at target, repeated charge passes, right-click held during approach, no food swap mid-charge.
   - Bow vs strafing zombie at 15 blocks: arrows lead the movement and land; vs skeleton on a pillar: drop compensated.
   - Mace toggle ON + mace + wind charges + zombie in reach: jump first, wind charge at apex (visibly higher than ground-throw), mid-air mace swap, swing lands at last moment on a MOVING target, no fall damage. Remove wind charges: plain sword melee, mace never equipped.
   - Drain hunger to critical mid-fight: bot runs away from the mob while eating, re-engages after; never stands still with food out.
   - `!AI farm`: Baritone #farm process harvests + replants; zombie interrupt pauses farm, fight, farm resumes; stop cancels.
   - `!AI attack zombie` then dashboard EMERGENCY STOP: attack ends instantly, keys released; task line shows "ATTACK -> zombie" while active; `cancel` also ends it.
   - Night + active follow/mine/farm/goto: bot keeps working, no bed run. Truly idle at night: sleeps.
