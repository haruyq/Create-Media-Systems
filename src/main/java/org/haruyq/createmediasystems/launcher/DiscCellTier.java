package org.haruyq.createmediasystems.launcher;

import java.util.List;

/** All ordinary tiers use Minecraft/Create materials. Only CHEMI has an optional addon recipe. */
public enum DiscCellTier {
    COPPER(1, "copper", 24, 1000, 12, 0x5CE4D7),
    BRASS(2, "brass", 48, 1500, 18, 0xF6BE54),
    DIAMOND(3, "diamond", 96, 2500, 28, 0x61CCFF),
    NETHERITE(4, "netherite", 160, 4000, 40, 0xBC8CFF),
    RESONANT(5, "resonant", 256, 6000, 56, 0xFF70B4),
    CHEMI(6, "chemi", 512, 10000, 80, 0xB9FF64);

    public static final String PLANT_MOD_ID = "create_heavy_indusstry";
    public final int level;
    public final String id;
    public final int shots;
    public final int energyPerShot;
    public final float damage;
    public final int color;

    DiscCellTier(int level, String id, int shots, int energyPerShot, float damage, int color) {
        this.level = level;
        this.id = id;
        this.shots = shots;
        this.energyPerShot = energyPerShot;
        this.damage = damage;
        this.color = color;
    }

    public int capacity() { return shots * energyPerShot; }
    public boolean optional() { return this == CHEMI; }
    public static DiscCellTier at(int level) { return values()[Math.clamp(level, 1, values().length) - 1]; }

    /** Eight material slots plus the previous cell; upgrades preserve its master and stored energy. */
    public List<String> upgradeMaterials() {
        return switch (this) {
            case COPPER -> List.of();
            case BRASS -> List.of("create:precision_mechanism", "create:brass_sheet", "create:brass_sheet",
                    "create:brass_sheet", "create:brass_sheet", "minecraft:quartz", "minecraft:quartz", "minecraft:redstone_block");
            case DIAMOND -> List.of("minecraft:diamond", "minecraft:diamond", "minecraft:diamond", "minecraft:diamond",
                    "create:precision_mechanism", "create:precision_mechanism", "minecraft:amethyst_shard", "minecraft:amethyst_shard");
            case NETHERITE -> List.of("minecraft:netherite_ingot", "minecraft:netherite_ingot", "minecraft:diamond",
                    "minecraft:diamond", "minecraft:blaze_rod", "minecraft:blaze_rod", "create:precision_mechanism", "create:precision_mechanism");
            case RESONANT -> List.of("minecraft:nether_star", "minecraft:netherite_ingot", "minecraft:netherite_ingot",
                    "minecraft:echo_shard", "minecraft:echo_shard", "minecraft:ender_eye", "minecraft:ender_eye", "create:precision_mechanism");
            case CHEMI -> List.of(PLANT_MOD_ID + ":mox_pellet", PLANT_MOD_ID + ":mox_pellet",
                    PLANT_MOD_ID + ":tungsten_divertor_cassette", PLANT_MOD_ID + ":tungsten_divertor_cassette",
                    PLANT_MOD_ID + ":titanium_sheet", PLANT_MOD_ID + ":titanium_sheet",
                    PLANT_MOD_ID + ":zircaloy_cladding_tube", PLANT_MOD_ID + ":fusion_toroidal_field_coil");
        };
    }
}
