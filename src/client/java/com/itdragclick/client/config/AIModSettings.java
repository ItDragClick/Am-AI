package com.itdragclick.client.config;

/**
 * Serializable configuration payload persisted to
 * {@code .minecraft/config/ai_companion_settings.json} via GSON.
 */
public class AIModSettings {

	/** Full Ollama generate endpoint, e.g. http://localhost:11434/api/generate */
	public String endpointUrl = "http://localhost:11434/api/generate";

	/** Ollama model tag to query. */
	public String modelId = "llama3.1:8b";

	/** In-game chat prefix that triggers the AI, e.g. "!AI <prompt>". */
	public String commandPrefix = "!AI";

	/** Global on/off switch for the whole pipeline. */
	public boolean active = true;

	public AIModSettings copy() {
		AIModSettings out = new AIModSettings();
		out.endpointUrl = endpointUrl;
		out.modelId = modelId;
		out.commandPrefix = commandPrefix;
		out.active = active;
		return out;
	}
}
