package org.haruyq.createmediasystems.items.launcher.client;

import static org.haruyq.createmediasystems.client.PonderScenes.hold;
import static org.haruyq.createmediasystems.client.PonderScenes.holder;
import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.items.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.items.launcher.DiscCellChargerBlock;
import org.haruyq.createmediasystems.items.launcher.DiscCellTier;
import org.haruyq.createmediasystems.items.launcher.DiscProjectile;
import org.haruyq.createmediasystems.items.launcher.LauncherFeature;
import org.haruyq.createmediasystems.items.power.PowerFeature;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Rotations;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The Ponder scenes of the Disc Cells with their Energy Injector, and of the Disc Launcher with its aim module. */
public final class LauncherPonder {
    private LauncherPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        List<ResourceLocation> cells = new ArrayList<>();
        LauncherFeature.DISC_CELLS.forEach(cell -> cells.add(cell.getId()));
        cells.add(LauncherFeature.DISC_CELL_CHARGER_ITEM.getId());
        helper.forComponents(cells).addStoryBoard(Create.asResource("redstone_link"), LauncherPonder::cells);
        helper.forComponents(LauncherFeature.DISC_LAUNCHER.getId(), LauncherFeature.LAUNCHER_AIM_MODULE_ITEM.getId())
                .addStoryBoard(Create.asResource("redstone_link"), LauncherPonder::launcher);
    }

    private static void cells(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "disc_cells", "Disc Cells and the Energy Injector");
        BlockPos motor = util.grid().at(2, 1, 2);
        BlockPos charger = motor.above();
        scene.world().setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.UP), false);
        scene.world().setBlock(charger, LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(motor, charger), Direction.DOWN);
        scene.idle(20);
        Vec3 dock = util.vector().topOf(charger).add(0, -0.3, 0);
        // A player stand-in beside the injector, facing the camera with a cell in its hand.
        ElementLink<EntityElement> player = holder(scene, util.vector().of(3.6, 1, 1.4), 180, cell(DiscCellTier.COPPER));
        scene.idle(10);
        Vec3 air = util.vector().of(3.6, 3.1, 1.4);

        scene.overlay().showControls(air, Pointing.DOWN, 60).rightClick();
        say(scene, 130, air, "A Disc Cell is what the Disc Launcher fires from. Right-click it in the air to put in a music disc, Custom Disc, DVD or Blu-ray, and to set the time its playback starts from.");
        say(scene, 100, air, "Firing never uses up that original disc. Each shot spends energy from the cell instead.");

        scene.overlay().showControls(dock, Pointing.DOWN, 40).rightClick().withItem(cell(DiscCellTier.COPPER));
        scene.idle(10);
        hold(scene, player, ItemStack.EMPTY);
        dock(scene, charger, DiscCellTier.COPPER);
        say(scene, 110, dock, "Right-click a Disc Cell Energy Injector with a cell to place it in the socket, and with an empty hand to take it back.");

        scene.world().setKineticSpeed(util.select().fromTo(motor, charger), 32);
        scene.world().modifyBlock(charger, state -> state.setValue(DiscCellChargerBlock.CHARGING, true), false);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, motor, new AABB(motor), 120);
        say(scene, 120, util.vector().centerOf(motor), PonderPalette.GREEN, "A shaft turning its bottom at 16 RPM or faster charges the cell, and faster rotation charges it quicker. It also accepts FE from energy cables on any side.");

        scene.world().setKineticSpeed(util.select().fromTo(motor, charger), 0);
        hold(scene, player, new ItemStack(PowerFeature.BATTERY.get()));
        scene.overlay().showControls(dock, Pointing.DOWN, 40).rightClick().whileSneaking()
                .withItem(new ItemStack(PowerFeature.BATTERY.get()));
        scene.idle(10);
        say(scene, 120, dock, "Sneak-right-click it with a charged battery to fill its reserve. Whenever no other power comes in, the reserve charges the cell.");

        scene.overlay().showText(140).attachKeyFrame().pointAt(dock).placeNearTarget()
                .text("Cells come in tiers: copper, brass, diamond, netherite and resonant. Each tier holds more shots and hits harder.");
        for (DiscCellTier tier : List.of(DiscCellTier.BRASS, DiscCellTier.DIAMOND, DiscCellTier.NETHERITE,
                DiscCellTier.RESONANT)) {
            scene.idle(30);
            dock(scene, charger, tier);
        }
        scene.idle(30);
        say(scene, 120, dock, "A crafting table upgrades a cell to the next tier with eight materials. The new cell keeps the disc, the energy and the start time.");
        say(scene, 100, dock, "The top tier, the Chemi Apex Disc Cell, is made from the Create Plant mod's materials, so it can be made only with that mod installed.");
    }

    private static void launcher(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "disc_launcher", "The Disc Launcher");
        // As in the display's scene: the wall stands at the back, its face towards the camera at z = 3.
        Selection wall = util.select().fromTo(0, 1, 3, 4, 3, 3);
        scene.world().setBlocks(wall, Blocks.STONE_BRICKS.defaultBlockState(), false);
        scene.world().showSection(wall, Direction.DOWN);
        scene.idle(15);
        Vec3 hit = util.vector().of(2.5, 2.5, 2.97);
        ItemStack launcher = new ItemStack(LauncherFeature.DISC_LAUNCHER.get());
        // A player stand-in near the camera, facing the wall with the launcher raised.
        // Both arms forward as a player's are when holding it (LauncherClient.poseArms).
        ElementLink<EntityElement> player = holder(scene, util.vector().of(2.5, 1, 0.6), 0, launcher,
                new Rotations(-81, -7, 0), new Rotations(-80, 24, -6));
        scene.idle(10);
        Vec3 shooter = util.vector().of(2.5, 2.4, 0.9);

        say(scene, 130, shooter, "The Disc Launcher fires from a Disc Cell. Carry a cell holding an original disc and press R to load it; any cell it held goes back into your inventory. Shift+R takes the cell out.");

        scene.overlay().showControls(shooter, Pointing.DOWN, 60).leftClick();
        scene.idle(10);
        stuckDisc(scene, hit);
        notes(scene, hit, 80);
        say(scene, 110, shooter, "Hold left-click to fire. Each shot spends one shot of the cell's energy, and when the cell runs empty the launcher loads the next charged cell you carry.");
        say(scene, 130, hit, PonderPalette.GREEN, "A disc that hits a block sticks in it and plays the original disc from the cell's start time for everyone nearby. It stays until its track ends or a minute passes, and disappears if the block is removed.");
        Vec3 zombieAt = util.vector().of(0.8, 1, 2.3);
        scene.world().createEntity(level -> {
            Zombie zombie = new Zombie(level);
            zombie.setPos(zombieAt);
            zombie.setYRot(180);
            zombie.yRotO = 180;
            zombie.setYBodyRot(180);
            zombie.yBodyRotO = 180;
            zombie.setYHeadRot(180);
            zombie.yHeadRotO = 180;
            return zombie;
        });
        scene.idle(10);
        say(scene, 120, zombieAt.add(0, 1.7, 0), "A disc that hits a creature damages it, twice as much on a headshot, and plays stuck to it until its track ends or the creature dies. A shot that hits nothing disappears within five seconds.");

        BlockPos table = util.grid().at(0, 1, 1);
        scene.world().setBlock(table, Blocks.SMITHING_TABLE.defaultBlockState(), false);
        scene.world().showSection(util.select().position(table), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(table), Pointing.DOWN, 50).rightClick()
                .withItem(new ItemStack(LauncherFeature.LAUNCHER_AIM_MODULE_ITEM.get()));
        say(scene, 110, util.vector().topOf(table), "To install a Predictive Aim Module, put the module, the Disc Launcher and a Sturdy Sheet into a smithing table, from left to right.");

        ItemStack fitted = launcher.copy();
        fitted.set(LauncherFeature.LAUNCHER_AIM_MODULE.get(), true);
        hold(scene, player, fitted);
        scene.overlay().showControls(shooter, Pointing.DOWN, 50).rightClick();
        scene.idle(10);
        say(scene, 130, shooter, "With the module installed, holding right-click to aim locks onto a creature you can harm near your crosshair. Your view then turns to where a disc will meet it, even while it moves.");
    }

    /** A launched disc stuck in the wall whose face is at {@code at}, that face toward the camera. */
    private static void stuckDisc(CreateSceneBuilder scene, Vec3 at) {
        scene.world().createEntity(level -> {
            DiscProjectile disc = new DiscProjectile(LauncherFeature.DISC_PROJECTILE.get(), level);
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("CmsDiscEmbedded", true);
            disc.readAdditionalSaveData(tag);
            disc.setPos(at);
            disc.setYRot(0);
            disc.yRotO = 0;
            return disc;
        });
    }

    private static ItemStack cell(DiscCellTier tier) {
        return new ItemStack(LauncherFeature.cell(tier));
    }

    /** Shows {@code tier}'s cell in the injector's socket. */
    private static void dock(CreateSceneBuilder scene, BlockPos charger, DiscCellTier tier) {
        scene.world().modifyBlockEntity(charger, DiscCellCharger.class,
                injector -> injector.inventory.setStackInSlot(0, cell(tier)));
    }
}
