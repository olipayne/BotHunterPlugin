package net.runelite.client.plugins.bothunter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bothunter")
public interface BotHunterConfig extends Config {
	@ConfigItem(
			keyName = "enableTracking",
			name = "Enable Tracking",
			description = "Enable player tracking and data submission"
	)
	default boolean enableTracking() {
		return true;
	}

	@ConfigItem(
			keyName = "apiKey",
			name = "API Key",
			description = "Your BotHunter API key"
	)
	default String apiKey() {
		return "";
	}

	@ConfigItem(
			keyName = "showOverlay",
			name = "Show Scores",
			description = "Show anomaly scores above players' heads"
	)
	default boolean showOverlay() {
		return true;
	}
}