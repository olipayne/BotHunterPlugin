package com.bothunter;

import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.*;
import javax.inject.Inject;
import java.awt.*;
import java.util.Map;


public class BotHunterOverlay extends Overlay {
    private final BotHunterPlugin plugin;
    private final BotHunterConfig config;

    @Inject
    private BotHunterOverlay(BotHunterPlugin plugin, BotHunterConfig config) {
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay()) {
            return null;
        }

        Map<String, Double> anomalyScores = plugin.getAnomalyScores();
        if (anomalyScores.isEmpty()) {
            return null;
        }

        for (Player player : plugin.getClient().getWorldView(-1).players()) {
            if (player == null || player == plugin.getClient().getLocalPlayer()) {
                continue;
            }

            String playerName = player.getName();
            Double score = anomalyScores.get(playerName);
            if (score == null) {
                continue;
            }

            Point textLocation = player.getCanvasTextLocation(graphics,
                    String.format("%.2f", score),
                    player.getLogicalHeight() + 40);

            if (textLocation == null) {
                continue;
            }

            // Color based on anomaly score
            Color textColor = getScoreColor(score);

            renderTextLocation(graphics, textLocation,
                    String.format("%.2f", score), textColor);
        }

        return null;
    }

    private Color getScoreColor(double score) {
        if (score >= 0.8) return Color.RED;
        if (score >= 0.6) return Color.ORANGE;
        if (score >= 0.4) return Color.YELLOW;
        return Color.GREEN;
    }

    private void renderTextLocation(Graphics2D graphics, Point txtLoc, String text, Color color) {
        if (txtLoc == null) {
            return;
        }

        int x = txtLoc.getX();
        int y = txtLoc.getY();

        graphics.setColor(Color.BLACK);
        graphics.drawString(text, x + 1, y + 1);

        graphics.setColor(color);
        graphics.drawString(text, x, y);
    }
}