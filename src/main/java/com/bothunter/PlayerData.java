package com.bothunter;

import net.runelite.api.coords.WorldPoint;
import lombok.Data;

@Data
class PlayerData {
    private final String name;
    private final WorldPoint location;
    private final int animation;
    private final String interacting;
    private final int combatLevel;
    private final int[] equipment;

    public PlayerData(String name, WorldPoint location, int animation, 
            String interacting, int combatLevel, int[] equipment) {
        this.name = name;
        this.location = location;
        this.animation = animation;
        this.interacting = interacting;
        this.combatLevel = combatLevel;
        this.equipment = equipment;
    }
}