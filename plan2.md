# Plan 2

## 1. LLM Request Queue
Drop no prompt. Add queue in `OllamaNetworkClient`.
When `IN_FLIGHT` busy, push to `ConcurrentLinkedQueue`.
When request finish, poll queue, run next. Fix "not respawning" bug.

## 2. Shield Break Fast
In `SurvivalMonitor`, check if target `isBlocking()`.
If blocking: use axe. Hit instantly. Ignore `getAttackStrengthScale() >= 1.0f`.
Condition: bot must NOT be blocking with own shield when swinging axe.

## 3. Bow Line of Sight
In `SurvivalMonitor` ranged combat.
Check `player.hasLineOfSight(target)`.
No line of sight -> no shoot wall. Pathfind or swap melee.

## 4. Item Switch Pause
While eating, stop hotbar swap.
Block `InventoryHelper.equipBestWeapon()` if `SurvivalMonitor.isEating()`.
Prevent weapon swap cancelling food use.

## 5. Low Health Flee Toggle
Check dashboard. `fleeOnLowHealthCheck` exist.
Ensure it syncs to `AIModSettings.fleeOnLowHealth`.
Verify `SurvivalMonitor` reads config correctly. If missing, wire it.
