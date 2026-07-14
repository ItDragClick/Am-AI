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

	/** Health (half-hearts) at which the low-health retreat triggers. */
	public int lowHealthThreshold = 8;

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
		out.lowHealthThreshold = lowHealthThreshold;
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
