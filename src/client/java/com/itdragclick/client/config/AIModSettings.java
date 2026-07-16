package com.itdragclick.client.config;

/**
 * Serializable configuration payload persisted to
 * {@code .minecraft/config/ai_companion_settings.json} via GSON.
 */
public class AIModSettings {

	/** Full Ollama generate endpoint, e.g. http://localhost:11434/api/generate */
	public String endpointUrl = "http://localhost:11434/api/generate";

	/** Ollama model tag to query. */
	public String modelId = "deepseek-r1:8b";

	/** Ollama embedding model tag. */
	public String embeddingModelId = "nomic-embed-text";

	/** In-game chat prefix that triggers the AI, e.g. "!AI <prompt>". */
	public String commandPrefix = "!AI";

	/** Global on/off switch for the whole pipeline. */
	public boolean active = true;

	/** Weapon priority mode: "Swords", "Axes", or "Highest Damage". */
	public String weaponPriority = "Swords";

	/** Whether Baritone is allowed to break/place blocks while chasing targets in combat. */
	public boolean combatAllowBlocks = false;

	/** Whether the bot should attempt MLG water bucket drops. */
	public boolean allowMlgWater = true;

	/** Whether the bot auto-equips and raises a shield during combat. */
	public boolean useShieldWhileFighting = true;

	/** Whether the bot switches to a bow/crossbow when the target is out of melee reach. */
	public boolean useBowCrossbow = true;

	/** Whether the bot retreats to eat/heal when health drops below the threshold. */
	public boolean fleeOnLowHealth = true;

	/**
	 * Whether the bot proactively attacks nearby hostile mobs while a work
	 * task (farming/mining/crafting) is running, instead of waiting to be hit.
	 * Neutral-until-provoked mobs (zombified piglins...) are never first-struck.
	 */
	public boolean autoDefendWhileWorking = true;

	/** Whether the bot walks to a bed and sleeps on its own at night when idle. */
	public boolean autoSleepAtNight = true;

	/**
	 * Whether the bot may open melee engagements with the mace smash combo:
	 * jump, wind charge at the feet at the jump apex, mace hit while falling.
	 * Requires BOTH a mace and at least one wind charge in the inventory —
	 * without wind charges the mace is never used at all.
	 */
	public boolean useMaceAttack = false;

	/**
	 * Whether the bot jumps and swings on the way down for critical hits
	 * (vanilla rule: falling, not sprinting, not in water/on a ladder = 1.5x).
	 */
	public boolean useCritAttack = true;

	/** Health (half-hearts) at which the low-health retreat triggers. */
	public int lowHealthThreshold = 8;

	// ------------------------------------------------ combat tuning (sliders)
	/** Ticks between cooldown-bypass spam swings (axe shield-break / mace punish). */
	public int spamSwingDelayTicks = 4;
	/** Ground mace hits after a shield break before returning to the normal weapon. */
	public int macePunishMaxHits = 5;
	/** Run-up room (blocks) the mounted spear needs before committing to a charge. */
	public int lanceChargeDistance = 8;
	/** Distance where axe shield-break swings start, in tenths of a block (32 = 3.2). */
	public int shieldBreakRangeTenths = 32;
	/** Cooldown (ticks) between mace jump/wind-charge combo attempts. */
	public int maceComboCooldownTicks = 60;
	/** Ticks holding the food with the use key UP (shield down) before chewing. */
	public int eatWarmupTicks = 20;
	/** Ticks between crit jumps (also caps how long a swing waits for the fall). */
	public int critJumpCooldownTicks = 12;

	/** Whether the bot is allowed to break blocks while idle. */
	public boolean allowIdleBlockBreak = false;

	/** Whether the bot is allowed to place blocks while idle. */
	public boolean allowIdleBlockPlace = false;

	public boolean allowIdleLookAround = true;
	public boolean allowIdleStare = true;
	public boolean allowIdleGift = true;
	public boolean allowIdleExplore = true;
	public boolean allowIdleBigGoal = true;
	public boolean allowFollowBlockEdit = false;

	public AIModSettings copy() {
		AIModSettings out = new AIModSettings();
		out.endpointUrl = endpointUrl;
		out.modelId = modelId;
		out.embeddingModelId = embeddingModelId;
		out.commandPrefix = commandPrefix;
		out.active = active;
		out.weaponPriority = weaponPriority;
		out.combatAllowBlocks = combatAllowBlocks;
		out.allowMlgWater = allowMlgWater;
		out.useShieldWhileFighting = useShieldWhileFighting;
		out.useBowCrossbow = useBowCrossbow;
		out.fleeOnLowHealth = fleeOnLowHealth;
		out.autoDefendWhileWorking = autoDefendWhileWorking;
		out.autoSleepAtNight = autoSleepAtNight;
		out.useMaceAttack = useMaceAttack;
		out.useCritAttack = useCritAttack;
		out.lowHealthThreshold = lowHealthThreshold;
		out.spamSwingDelayTicks = spamSwingDelayTicks;
		out.macePunishMaxHits = macePunishMaxHits;
		out.lanceChargeDistance = lanceChargeDistance;
		out.shieldBreakRangeTenths = shieldBreakRangeTenths;
		out.maceComboCooldownTicks = maceComboCooldownTicks;
		out.eatWarmupTicks = eatWarmupTicks;
		out.critJumpCooldownTicks = critJumpCooldownTicks;
		out.allowIdleBlockBreak = allowIdleBlockBreak;
		out.allowIdleBlockPlace = allowIdleBlockPlace;
		out.allowIdleLookAround = allowIdleLookAround;
		out.allowIdleStare = allowIdleStare;
		out.allowIdleGift = allowIdleGift;
		out.allowIdleExplore = allowIdleExplore;
		out.allowIdleBigGoal = allowIdleBigGoal;
		out.allowFollowBlockEdit = allowFollowBlockEdit;
		return out;
	}
}
