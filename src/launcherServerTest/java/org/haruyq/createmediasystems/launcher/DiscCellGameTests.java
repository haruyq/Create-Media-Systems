package org.haruyq.createmediasystems.launcher;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;

import com.simibubi.create.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiscCellGameTests {
    private static ItemStack original() {
        ItemStack disc = new ItemStack(CreateMediaSystems.CUSTOM_DISC.get());
        disc.set(CreateMediaSystems.URL.get(), "https://youtu.be/m1TPvPU9WTw");
        disc.set(CreateMediaSystems.TITLE.get(), "Persistent original");
        return disc;
    }
    private static ItemStack cell(DiscCellTier tier, int energy) {
        ItemStack cell = new ItemStack(CreateMediaSystems.cell(tier));
        DiscCellItem.setMaster(cell, original());
        DiscCellItem.setEnergy(cell, energy);
        return cell;
    }

    @GameTest(template="empty")
    public static void netheriteFiresAll160RoundsIncludingWhileCrouchedBeforeAutomaticReload(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var pos=h.absolutePos(new BlockPos(1,2,1));
        player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        ItemStack gun=new ItemStack(CreateMediaSystems.DISC_LAUNCHER.get());
        LauncherMagazine.setCell(gun,cell(DiscCellTier.NETHERITE,DiscCellTier.NETHERITE.capacity()));
        player.getInventory().setItem(0,gun);
        player.getInventory().setItem(4,cell(DiscCellTier.DIAMOND,DiscCellTier.DIAMOND.capacity()));
        for(int fired=1;fired<=160;fired++) {
            player.setShiftKeyDown(fired%2==0);
            player.getCooldowns().removeCooldown(gun.getItem());
            gun.remove(CreateMediaSystems.LAUNCHER_SHOT_AT.get());
            DiscLauncherItem.fire(player,player.getYRot(),player.getXRot());
            h.assertTrue(!LauncherMagazine.reloading(gun),"Firing round "+fired+" must not start a reload or eject, including while crouched");
            h.assertTrue(LauncherMagazine.count(gun)==160-fired && LauncherMagazine.tier(gun)==DiscCellTier.NETHERITE,
                    "Each real shot consumes exactly 4000 FE from the installed Lv.4 cell");
            h.assertTrue(!player.getInventory().getItem(4).isEmpty(),"The spare cell must stay in inventory while firing");
            h.getLevel().getEntitiesOfClass(DiscProjectile.class,player.getBoundingBox().inflate(2)).forEach(net.minecraft.world.entity.Entity::discard);
            if(fired==80) {
                gun=ItemStack.parse(h.getLevel().registryAccess(),gun.save(h.getLevel().registryAccess())).orElseThrow();
                player.getInventory().setItem(0,gun);
            }
        }
        player.setShiftKeyDown(true);
        player.getCooldowns().removeCooldown(gun.getItem());
        gun.remove(CreateMediaSystems.LAUNCHER_SHOT_AT.get());
        DiscLauncherItem.fire(player,player.getYRot(),player.getXRot());
        h.assertTrue(LauncherMagazine.reloading(gun) && !LauncherMagazine.ejecting(gun)
                && DiscCellItem.shots(LauncherMagazine.pending(gun))==96,"Only an empty cell starts automatic replacement");
        LauncherMagazine.finish(player,gun);
        h.assertTrue(LauncherMagazine.count(gun)==96 && player.getInventory().items.stream().anyMatch(s->s.is(CreateMediaSystems.cell(DiscCellTier.NETHERITE))
                && DiscCellItem.energy(s)==0 && DiscCellItem.hasMaster(s)),"The exhausted original cell is returned intact");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void ammoReadoutTracksTheInstalledTierAndChangesOnlyAfterReloadCommit(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack gun = new ItemStack(CreateMediaSystems.DISC_LAUNCHER.get());
        player.getInventory().setItem(0, gun);
        h.assertTrue(!LauncherReadout.of(gun).hasCell() && LauncherReadout.of(gun).capacity() == 0, "An empty gun has no invented 512-round capacity");
        for (DiscCellTier tier : DiscCellTier.values()) {
            LauncherMagazine.setCell(gun, cell(tier, tier.capacity()));
            var full = LauncherReadout.of(gun);
            h.assertTrue(full.shots() == tier.shots && full.capacity() == tier.shots && full.tier() == tier, "Every cell exposes its actual capacity");
            LauncherMagazine.pop(gun);
            h.assertTrue(LauncherReadout.of(gun).shots() == tier.shots - 1, "Readout follows the real shot, not a client counter");
        }
        LauncherMagazine.setCell(gun, cell(DiscCellTier.COPPER, 23000));
        player.getInventory().setItem(4, cell(DiscCellTier.DIAMOND, DiscCellTier.DIAMOND.capacity()));
        LauncherMagazine.reserve(player, gun, h.getLevel().getGameTime());
        h.assertTrue(LauncherReadout.of(gun).shots() == 23 && LauncherReadout.of(gun).capacity() == 24, "Pending Diamond cannot change the installed Copper readout");
        LauncherMagazine.finish(player, gun);
        h.assertTrue(LauncherReadout.of(gun).shots() == 96 && LauncherReadout.of(gun).capacity() == 96, "Committing the reload changes both values together");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void eachTierProjectsWithoutConsumingItsOriginal(GameTestHelper h) {
        for (DiscCellTier tier : DiscCellTier.values()) {
            ItemStack cell = cell(tier, tier.capacity());
            ItemStack master = DiscCellItem.master(cell).copy();
            for (int n = 0; n < 3; n++) {
                ItemStack shot = DiscCellItem.project(cell);
                h.assertTrue(ItemStack.matches(master, shot), "The projected media keeps its URL and title");
                h.assertTrue(ItemStack.matches(master, DiscCellItem.master(cell)), "The original is never consumed");
                h.assertTrue(DiscCellItem.shots(cell) == tier.shots - n - 1, "Exactly one shot of energy is spent");
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void depletedCellKeepsTheMasterAndCanBeRecharged(GameTestHelper h) {
        ItemStack cell = cell(DiscCellTier.COPPER, 2000);
        DiscCellItem.project(cell); DiscCellItem.project(cell);
        h.assertTrue(DiscCellItem.project(cell).isEmpty() && DiscCellItem.energy(cell) == 0, "A depleted cell cannot fire");
        h.assertTrue(ItemStack.matches(original(), DiscCellItem.master(cell)), "Depletion cannot destroy the original");
        var energy = cell.getCapability(Capabilities.EnergyStorage.ITEM);
        h.assertTrue(energy != null && energy.receiveEnergy(100000, true) == 24000, "FE simulation reports its capacity");
        h.assertTrue(DiscCellItem.energy(cell) == 0, "Simulating a charge changes nothing");
        h.assertTrue(energy.receiveEnergy(100000, false) == 24000 && DiscCellItem.shots(cell) == 24, "FE refills the real cell");
        h.assertTrue(energy.extractEnergy(10000, false) == 0, "A cell cannot be used to generate/extract free power");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void interruptedReloadPreservesBothCellsAcrossSave(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack launcher = new ItemStack(CreateMediaSystems.DISC_LAUNCHER.get());
        ItemStack old = cell(DiscCellTier.COPPER, 2000);
        ItemStack next = cell(DiscCellTier.DIAMOND, 12000);
        LauncherMagazine.setCell(launcher, old);
        player.getInventory().setItem(0, launcher);
        player.getInventory().setItem(4, next.copy());
        h.assertTrue(LauncherMagazine.reserve(player, launcher, h.getLevel().getGameTime()), "Reload starts");
        h.assertTrue(player.getInventory().getItem(4).isEmpty() && LauncherMagazine.pop(launcher).isEmpty(),
                "The pending cell is escrowed, and firing is locked");
        var restored = ItemStack.parse(h.getLevel().registryAccess(), launcher.save(h.getLevel().registryAccess())).orElseThrow();
        player.getInventory().setItem(0, restored);
        h.assertTrue(ItemStack.matches(old, LauncherMagazine.cell(restored)) && ItemStack.matches(next, LauncherMagazine.pending(restored)),
                "Dropping/disconnecting mid-reload cannot lose either cell or its original");
        h.assertTrue(LauncherMagazine.finish(player, restored), "Recovered reload finishes");
        h.assertTrue(ItemStack.matches(next, LauncherMagazine.cell(restored)) && player.getInventory().countItem(old.getItem()) == 1,
                "The old cell is returned exactly once");
        h.assertTrue(!LauncherMagazine.finish(player, restored) && player.getInventory().countItem(old.getItem()) == 1,
                "Finishing twice cannot duplicate a cell");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void emptyCellCanBeEjectedWithoutLosingItsMaster(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack launcher = new ItemStack(CreateMediaSystems.DISC_LAUNCHER.get());
        ItemStack depleted = cell(DiscCellTier.BRASS, 0);
        LauncherMagazine.setCell(launcher, depleted);
        player.getInventory().setItem(0, launcher);
        h.assertTrue(LauncherMagazine.eject(player, launcher, 0), "An empty cell can be ejected for charging");
        LauncherMagazine.finish(player, launcher);
        h.assertTrue(LauncherMagazine.cell(launcher).isEmpty() && player.getInventory().countItem(depleted.getItem()) == 1,
                "The same depleted cell is returned");
        h.assertTrue(player.getInventory().items.stream().anyMatch(item -> ItemStack.matches(item, depleted)), "Its original survives");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void legacyAmmoIsRefundedOnce(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack launcher = new ItemStack(CreateMediaSystems.DISC_LAUNCHER.get());
        launcher.set(CreateMediaSystems.LAUNCHER_LOADED.get(), List.of(original()));
        launcher.set(CreateMediaSystems.LAUNCHER_RESERVED.get(), List.of(original()));
        launcher.set(CreateMediaSystems.LAUNCHER_RELOAD_START.get(), 12L);
        LauncherMagazine.migrateLegacy(player, launcher);
        LauncherMagazine.migrateLegacy(player, launcher);
        h.assertTrue(player.getInventory().countItem(CreateMediaSystems.CUSTOM_DISC.get()) == 2,
                "All pre-cell originals are refunded once rather than deleted or duplicated");
        h.assertTrue(!LauncherMagazine.reloading(launcher) && LauncherMagazine.pop(launcher).isEmpty(), "Raw discs no longer fuel the gun");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void upgradingPreservesOriginalAndEnergyAndRequiresAllMaterials(GameTestHelper h) {
        for (int level = 2; level <= 5; level++) {
            DiscCellTier target = DiscCellTier.at(level);
            ItemStack previous = cell(DiscCellTier.at(level - 1), 1234);
            List<ItemStack> inputs = new ArrayList<>();
            inputs.add(previous);
            for (String id : target.upgradeMaterials()) inputs.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))));
            var recipe = new DiscCellUpgradeRecipe(level);
            var grid = CraftingInput.of(3, 3, inputs);
            h.assertTrue(recipe.matches(grid, h.getLevel()), "Vanilla/Create materials craft level " + level);
            ItemStack upgraded = recipe.assemble(grid, h.getLevel().registryAccess());
            h.assertTrue(upgraded.is(CreateMediaSystems.cell(target)) && DiscCellItem.energy(upgraded) == 1234
                    && ItemStack.matches(DiscCellItem.master(previous), DiscCellItem.master(upgraded)), "Upgrade keeps the original and stored energy");
            inputs.set(1, new ItemStack(Items.DIRT));
            h.assertTrue(!recipe.matches(CraftingInput.of(3, 3, inputs), h.getLevel()), "Missing an expensive material must fail");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void plantRecipeIsConditionalButOrdinaryCellsAlwaysExist(GameTestHelper h) {
        for (DiscCellTier tier : DiscCellTier.values()) {
            var recipe = h.getLevel().getRecipeManager().byKey(CreateMediaSystems.id(tier.id + "_disc_cell"));
            h.assertTrue(recipe.isPresent() == (!tier.optional() || ModList.get().isLoaded(DiscCellTier.PLANT_MOD_ID)),
                    "Only the Chemi Apex recipe depends on Create Plant");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void loadedCellUpgradesExposeCompleteCraftingInputsAndOutputsToRecipeViewers(GameTestHelper h) {
        for(int level=2;level<=6;level++) {
            var holder=h.getLevel().getRecipeManager().byKey(CreateMediaSystems.id(DiscCellTier.at(level).id+"_disc_cell"));
            if(holder.isEmpty()) {h.assertTrue(level==6 && !ModList.get().isLoaded(DiscCellTier.PLANT_MOD_ID),"Only the optional top-tier recipe may be absent");continue;}
            var recipe=(DiscCellUpgradeRecipe)holder.get().value();
            h.assertTrue(!recipe.isSpecial() && recipe.getType()==net.minecraft.world.item.crafting.RecipeType.CRAFTING,"Recipe viewers must handle the loaded upgrade as ordinary crafting");
            var ingredients=recipe.getIngredients();h.assertTrue(ingredients.size()==9,"Expose the previous cell and all eight materials");
            List<ItemStack> shown=new ArrayList<>();
            for(var ingredient:ingredients) {var alternatives=ingredient.getItems();h.assertTrue(alternatives.length>0 && !alternatives[0].isEmpty(),"No displayed ingredient may be missing or AIR");shown.add(alternatives[0].copyWithCount(1));}
            var input=CraftingInput.of(3,3,shown);h.assertTrue(recipe.matches(input,h.getLevel()),"The displayed ingredient grid must actually craft");
            var result=recipe.getResultItem(h.getLevel().registryAccess());
            h.assertTrue(!result.isEmpty() && recipe.assemble(input,h.getLevel().registryAccess()).is(result.getItem()),"The displayed output must match the real crafted cell");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void plantMaterialsUpgradeToTheUltimateCellWhenPresent(GameTestHelper h) {
        if (Boolean.getBoolean("cms.cellPlantTest"))
            h.assertTrue(ModList.get().isLoaded(DiscCellTier.PLANT_MOD_ID), "The requested optional-mod fixture must actually load");
        if (!ModList.get().isLoaded(DiscCellTier.PLANT_MOD_ID)) { h.succeed(); return; }
        var holder = h.getLevel().getRecipeManager().byKey(CreateMediaSystems.id("chemi_disc_cell")).orElseThrow();
        var recipe = (DiscCellUpgradeRecipe) holder.value();
        ItemStack previous = cell(DiscCellTier.RESONANT, 123456);
        List<ItemStack> inputs = new ArrayList<>(); inputs.add(previous);
        for (String id : DiscCellTier.CHEMI.upgradeMaterials()) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            h.assertTrue(item != Items.AIR, "The optional material ID really exists: " + id);
            inputs.add(new ItemStack(item));
        }
        var grid = CraftingInput.of(3, 3, inputs);
        h.assertTrue(recipe.matches(grid, h.getLevel()), "The condition-enabled recipe accepts the verified Plant materials");
        ItemStack apex = recipe.assemble(grid, h.getLevel().registryAccess());
        h.assertTrue(apex.is(CreateMediaSystems.CHEMI_DISC_CELL.get()) && DiscCellItem.energy(apex) == 123456
                && ItemStack.matches(DiscCellItem.master(previous), DiscCellItem.master(apex)), "The ultimate upgrade preserves the original and energy");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void masterMenuLocksTheHeldCellAndTransfersOriginalWithoutDuplication(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack cell = cell(DiscCellTier.COPPER, 1000);
        player.getInventory().setItem(0, cell);
        var menu = new DiscCellMenu(1, player.getInventory(), 0);
        menu.clicked(28, 0, ClickType.PICKUP, player);
        menu.clicked(0, 0, ClickType.SWAP, player);
        h.assertTrue(player.getInventory().getItem(0) == cell && DiscCellItem.hasMaster(cell), "The cell cannot be taken or hot-swapped while its menu owns it");
        menu.quickMoveStack(player, 0);
        h.assertTrue(DiscCellItem.master(cell).isEmpty() && player.getInventory().countItem(CreateMediaSystems.CUSTOM_DISC.get()) == 1,
                "Extracting the original removes it from the cell exactly once");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void chargerSimulationSaveAndRemovalPreserveOneRealCell(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlockAndUpdate(pos, CreateMediaSystems.DISC_CELL_CHARGER.get().defaultBlockState());
        var charger = (DiscCellCharger) h.getLevel().getBlockEntity(pos);
        h.assertTrue(new ItemStack(Items.STONE_PICKAXE).isCorrectToolForDrops(charger.getBlockState())
                && !new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(charger.getBlockState()),
                "The charger is recoverable with its proper survival pickaxe tier");
        charger.insert(cell(DiscCellTier.COPPER, 0));
        var port = h.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        h.assertTrue(port != null && port.receiveEnergy(1000, true) == 1000 && DiscCellItem.energy(charger.cell()) == 0,
                "Charging simulation leaves the dock unchanged");
        port.receiveEnergy(1000, false);
        CompoundTag saved = charger.saveWithoutMetadata(h.getLevel().registryAccess());
        var restored = new DiscCellCharger(pos, charger.getBlockState());
        restored.loadWithComponents(saved, h.getLevel().registryAccess());
        h.assertTrue(ItemStack.matches(charger.cell(), restored.cell()), "Block save/reload keeps the original and exact energy");
        h.getLevel().destroyBlock(pos, false);
        var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2));
        h.assertTrue(drops.size() == 1 && DiscCellItem.energy(drops.getFirst().getItem()) == 1000
                && ItemStack.matches(original(), DiscCellItem.master(drops.getFirst().getItem())), "Breaking a charger returns one cell, never a loose copy of its original");
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=100)
    public static void createRotationChargesACellWithoutAnElectricalAddon(GameTestHelper h) {
        BlockPos motor = h.absolutePos(new BlockPos(1, 1, 1));
        BlockPos dock = motor.above();
        h.getLevel().setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(DirectionalBlock.FACING, net.minecraft.core.Direction.UP));
        h.getLevel().setBlockAndUpdate(dock, CreateMediaSystems.DISC_CELL_CHARGER.get().defaultBlockState());
        var charger = (DiscCellCharger) h.getLevel().getBlockEntity(dock);
        charger.insert(cell(DiscCellTier.COPPER, 0));
        h.runAfterDelay(30, () -> {
            h.assertTrue(Math.abs(charger.getSpeed()) >= 16 && DiscCellItem.energy(charger.cell()) > 0,
                    "A real Create shaft connection charges the cell without CEE, Plant or FE machinery");
            h.succeed();
        });
    }

    @GameTest(template="empty")
    public static void everyTierDealsItsConfiguredDamageAndSavesIt(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        for (DiscCellTier tier : DiscCellTier.values()) {
            var target = new IronGolem(EntityType.IRON_GOLEM, h.getLevel());
            var point = h.absolutePos(new BlockPos(1, 2, 1));
            target.setPos(point.getX(), point.getY(), point.getZ());
            var shot = new DiscProjectile(h.getLevel(), player, original());
            shot.setCellTier(tier);
            h.assertTrue(shot.getPickResult().isEmpty(), "Creative pick-block cannot copy the protected original from a projection");
            shot.onHitEntity(new EntityHitResult(target));
            h.assertTrue(Math.abs(target.getHealth() - (100 - tier.damage)) < .01, "Tier " + tier.level + " deals its real configured damage");
            CompoundTag tag = new CompoundTag(); shot.save(tag);
            var loaded = new DiscProjectile(CreateMediaSystems.DISC_PROJECTILE.get(), h.getLevel()); loaded.load(tag);
            h.assertTrue(loaded.cellTier() == tier && loaded.damage() == tier.damage, "Reload keeps its tier and damage");
        }
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=40)
    public static void legacyReloadLightExpiresWithoutLeavingABlockBehind(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos feet = h.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
        h.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
        ReloadGlowBlock.illuminate(player);
        h.assertTrue(h.getLevel().getBlockState(feet.above()).is(CreateMediaSystems.RELOAD_GLOW.get()), "Legacy saves may still contain the old white light block");
        h.runAfterDelay(12, () -> {
            h.assertTrue(h.getLevel().getBlockState(feet.above()).isAir(), "The temporary light cannot remain after reloading stops");
            h.succeed();
        });
    }

    @GameTest(template="empty")
    public static void coloredCellLightingDoesNotPlaceBlocksInTheWorld(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos feet = h.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
        h.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
        ItemStack gun = new ItemStack(CreateMediaSystems.DISC_LAUNCHER.get());
        LauncherMagazine.setCell(gun, cell(DiscCellTier.RESONANT, DiscCellTier.RESONANT.capacity()));
        gun.set(CreateMediaSystems.LAUNCHER_RELOAD_START.get(), h.getLevel().getGameTime() - 20);
        gun.getItem().inventoryTick(gun, h.getLevel(), player, 0, true);
        h.assertTrue(h.getLevel().getBlockState(feet.above()).isAir(), "Cell light is a client RGB pass and cannot mutate a server block");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void decoderDurationOnlyFillsUnknownMetadataAndPersists(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var shot = new DiscProjectile(h.getLevel(), player, original());
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 86401}) shot.noteDuration(invalid);
        CompoundTag tag = new CompoundTag(); shot.save(tag);
        h.assertTrue(tag.getDouble("CmsDiscDuration") < 0, "Invalid decoder metadata cannot change the server lifetime");
        shot.noteDuration(254);
        shot.noteDuration(1);
        shot.save(tag);
        h.assertTrue(tag.getDouble("CmsDiscDuration") == 254, "A later client cannot shorten an established track");
        var restored = new DiscProjectile(CreateMediaSystems.DISC_PROJECTILE.get(), h.getLevel()); restored.load(tag);
        CompoundTag savedAgain = new CompoundTag(); restored.save(savedAgain);
        h.assertTrue(savedAgain.getDouble("CmsDiscDuration") == 254, "Chunk reload keeps the established duration");
        tag.putLong("CmsDiscMusicStart", h.getLevel().getGameTime() - 254 * 20);
        restored.load(tag);
        h.assertTrue(!restored.tickPlayback() && restored.isRemoved(), "An expired reloaded projection disappears immediately");
        h.succeed();
    }
}
