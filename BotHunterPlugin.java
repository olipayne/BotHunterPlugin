package net.runelite.client.plugins.bothunter;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Timer;
import java.util.TimerTask;

@Slf4j
@PluginDescriptor(
		name = "BotHunter"
)
public class BotHunterPlugin extends Plugin {
	@Inject
	private Client client;

	@Inject
	private BotHunterConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BotHunterOverlay overlay;

	private final OkHttpClient httpClient;

	private static final String API_ENDPOINT = "https://ping.bothunter.cloud";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int SUBMIT_INTERVAL_TICKS = 100; // About 60 seconds
	private int tickCounter = 0;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private long lastSubmissionTime = 0;
	private int totalSubmissions = 0;
	private int failedSubmissions = 0;

	// Track players we've seen this interval
	private Map<String, PlayerData> newPlayerSightings = new ConcurrentHashMap<>();
	// Track players we've already reported this session to avoid duplicates
	private Set<String> reportedPlayers = new HashSet<>();
	// Store anomaly scores with timestamps
	private final Map<String, AnomalyData> anomalyScores = new ConcurrentHashMap<>();
	private static final long SCORE_CACHE_DURATION = TimeUnit.MINUTES.toMillis(1);

	private class AnomalyData {
		double score;
		long timestamp;

		AnomalyData(double score) {
			this.score = score;
			this.timestamp = System.currentTimeMillis();
		}

		boolean isExpired() {
			return System.currentTimeMillis() - timestamp > SCORE_CACHE_DURATION;
		}
	}

	@Inject
	public BotHunterPlugin() {
		this.httpClient = new OkHttpClient.Builder()
				.connectTimeout(30, TimeUnit.SECONDS)
				.writeTimeout(30, TimeUnit.SECONDS)
				.readTimeout(30, TimeUnit.SECONDS)
				.retryOnConnectionFailure(true)
				.build();
	}

	@Override
	protected void startUp() throws Exception {
		log.info("BotHunter started! Plugin version 1.0");
		log.info("Config status - Tracking enabled: {}", config.enableTracking());
		log.info("API endpoint configured: {}", API_ENDPOINT);
		overlayManager.add(overlay);
		tickCounter = 0;
		lastSubmissionTime = System.currentTimeMillis();
		newPlayerSightings.clear();
		reportedPlayers.clear();
		anomalyScores.clear();
	}

	@Override
	protected void shutDown() throws Exception {
		log.info("BotHunter stopped! Statistics - Total submissions: {}, Failed: {}, Unique players: {}",
				totalSubmissions, failedSubmissions, reportedPlayers.size());
		overlayManager.remove(overlay);
		newPlayerSightings.clear();
		reportedPlayers.clear();
		anomalyScores.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		GameState newState = gameStateChanged.getGameState();
		log.debug("Game state changed to: {}", newState);

		if (newState == GameState.LOGGED_IN) {
			String playerName = client.getLocalPlayer() != null ?
					client.getLocalPlayer().getName() : "unknown";
			log.info("BotHunter activated for player: {}", playerName);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"BotHunter activated - Helping track suspicious activity", null);
		} else if (newState == GameState.LOGIN_SCREEN) {
			newPlayerSightings.clear();
			reportedPlayers.clear();
			anomalyScores.clear();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		if (!config.enableTracking() || client.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		// Check for new players and fetch their scores
		checkForNewPlayers();

		// Update anomaly scores for visible players
		updateAnomalyScores();

		// Submit accumulated data when interval is reached
		tickCounter++;
		if (tickCounter >= SUBMIT_INTERVAL_TICKS && !newPlayerSightings.isEmpty()) {
			log.debug("Tick counter reached {} - submitting {} new player sightings",
					SUBMIT_INTERVAL_TICKS, newPlayerSightings.size());
			submitPlayerData();
			tickCounter = 0;
		}
	}

	private void checkForNewPlayers() {
		WorldView worldView = client.getWorldView(-1);
		if (worldView == null) {
			return;
		}

		for (Player player : worldView.players()) {
			if (player == null || player.getName() == null || player == client.getLocalPlayer()) {
				continue;
			}

			String playerName = player.getName();

			// Skip if we've already recorded this player this interval
			if (newPlayerSightings.containsKey(playerName)) {
				continue;
			}

			PlayerData data = new PlayerData(
					playerName,
					player.getWorldLocation(),
					player.getAnimation(),
					Optional.ofNullable(player.getInteracting()).map(Actor::getName).orElse(null),
					player.getCombatLevel(),
					Optional.ofNullable(player.getPlayerComposition())
							.map(PlayerComposition::getEquipmentIds)
							.orElse(null)
			);

			newPlayerSightings.put(playerName, data);
			log.debug("New player sighted: {}", playerName);
		}
	}

	private void updateAnomalyScores() {
		WorldView worldView = client.getWorldView(-1);
		if (worldView == null) return;

		for (Player player : worldView.players()) {
			if (player == null || player.getName() == null || player == client.getLocalPlayer()) {
				continue;
			}

			String playerName = player.getName();
			AnomalyData existingScore = anomalyScores.get(playerName);

			// Fetch new score if none exists or if cached score is expired
			if (existingScore == null || existingScore.isExpired()) {
				fetchAnomalyScore(playerName);
			}
		}
	}

	private void fetchAnomalyScore(String playerName) {
		String url = API_ENDPOINT + "?player=" + playerName;
		Request request = new Request.Builder()
				.url(url)
				.get()
				.build();

		httpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(@NotNull Call call, @NotNull IOException e) {
				log.debug("Failed to fetch anomaly score for {}: {}", playerName, e.getMessage());
			}

			@Override
			public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
				try (response) {
					if (!response.isSuccessful()) {
						log.debug("Failed to fetch anomaly score for {}: {}",
								playerName, response.code());
						return;
					}

					String responseBody = response.body().string();
					Type type = new TypeToken<Map<String, Object>>(){}.getType();
					Map<String, Object> data = gson.fromJson(responseBody, type);

					if (data.containsKey("anomalyScore")) {
						double score = ((Number) data.get("anomalyScore")).doubleValue();
						anomalyScores.put(playerName, new AnomalyData(score));
					}
				} catch (Exception e) {
					log.debug("Error processing anomaly score for {}: {}",
							playerName, e.getMessage());
				}
			}
		});
	}

	private void submitPlayerData() {
		if (newPlayerSightings.isEmpty()) {
			return;
		}

		// Create submission payload
		Map<String, Object> payload = new HashMap<>();
		payload.put("reporter", client.getLocalPlayer().getName());
		payload.put("world", client.getWorld());
		payload.put("location", client.getLocalPlayer().getWorldLocation());
		payload.put("players", new ArrayList<>(newPlayerSightings.values()));
		payload.put("timestamp", System.currentTimeMillis());
		payload.put("apiKey", config.apiKey());

		// Log submission details
		long timeSinceLastSubmission = System.currentTimeMillis() - lastSubmissionTime;
		log.info("Preparing submission - New Players: {}, World: {}, Time since last: {}ms",
				newPlayerSightings.size(), client.getWorld(), timeSinceLastSubmission);

		if (log.isDebugEnabled()) {
			String prettyJson = gson.toJson(payload);
			log.debug("Payload JSON: {}", prettyJson);
		}

		// Submit to API with retry logic
		String jsonPayload = gson.toJson(payload);
		RequestBody body = RequestBody.create(JSON, jsonPayload);
		Request request = new Request.Builder()
				.url(API_ENDPOINT)
				.post(body)
				.build();

		final int maxRetries = 3;
		final int retryDelayMs = 5000; // 5 seconds

		submitWithRetry(request, jsonPayload, 0, maxRetries, retryDelayMs);
	}

	private void submitWithRetry(Request request, String jsonPayload, int currentRetry, int maxRetries, int retryDelayMs) {
		httpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(@NotNull Call call, @NotNull IOException e) {
				failedSubmissions++;
				log.error("Failed to submit player data (attempt {}): {}",
						totalSubmissions + 1, e.getMessage());

				if (currentRetry < maxRetries &&
						(e instanceof java.net.SocketTimeoutException ||
								e instanceof java.net.UnknownHostException)) {
					log.info("Retrying submission in {} seconds (retry {}/{})",
							retryDelayMs/1000, currentRetry + 1, maxRetries);

					Timer timer = new Timer();
					timer.schedule(new TimerTask() {
						@Override
						public void run() {
							submitWithRetry(request, jsonPayload, currentRetry + 1, maxRetries, retryDelayMs);
						}
					}, retryDelayMs);
				} else {
					log.debug("Failed request details - URL: {}, Payload size: {} bytes",
							call.request().url(), jsonPayload.length());
					newPlayerSightings.clear(); // Clear on final failure
				}
			}

			@Override
			public void onResponse(@NotNull Call call, @NotNull Response response) {
				int responseCode = response.code();
				totalSubmissions++;
				lastSubmissionTime = System.currentTimeMillis();

				if (!response.isSuccessful()) {
					failedSubmissions++;
					log.error("Unexpected API response: {} {}", responseCode, response.message());
					if (response.body() != null) {
						try {
							String responseBody = response.body().string();
							log.debug("Error response body: {}", responseBody);
						} catch (Exception e) {
							log.debug("Could not read error response body", e);
						}
					}
				} else {
					reportedPlayers.addAll(newPlayerSightings.keySet());
					log.info("Successfully submitted {} new player sightings - Response code: {}, Submission #{}",
							newPlayerSightings.size(), responseCode, totalSubmissions);
					newPlayerSightings.clear(); // Clear only on success
				}
				response.close();
			}
		});
	}

	// Getter for the overlay to access scores
	public Map<String, Double> getAnomalyScores() {
		// Clean expired scores and return current ones
		anomalyScores.entrySet().removeIf(entry -> entry.getValue().isExpired());

		Map<String, Double> currentScores = new HashMap<>();
		anomalyScores.forEach((name, data) -> currentScores.put(name, data.score));
		return currentScores;
	}

	public Client getClient() {
		return client;
	}

	@Provides
	BotHunterConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(BotHunterConfig.class);
	}
}

