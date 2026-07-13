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

	public AIModSettings copy() {
		AIModSettings out = new AIModSettings();
		out.endpointUrl = endpointUrl;
		out.modelId = modelId;
		out.embeddingModelId = embeddingModelId;
		out.commandPrefix = commandPrefix;
		out.active = active;
		out.weaponPriority = weaponPriority;
		return out;
	}
}
