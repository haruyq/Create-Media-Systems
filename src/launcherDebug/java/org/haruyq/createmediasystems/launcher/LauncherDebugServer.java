package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.power.PowerFeature;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.power.ChargeableItem;
import java.nio.file.*;
import java.util.*;
import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.megaphone.MegaphoneFeature;
import org.haruyq.createmediasystems.partybox.PartyBoxFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid="create_media_systems")
public final class LauncherDebugServer {
    static final Path DIR=Path.of("").toAbsolutePath();
    static final String URL="http://127.0.0.1:18765/disc-test.wav";
    private static List<ItemStack> savedInventory;
    private static UUID demoPlayer;
    private static int savedSelected;
    private static net.minecraft.world.phys.Vec3 savedPosition;
    private static float savedYaw, savedPitch;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> savedDimension,observerReturnDimension;
    private static int previewAge = -1;
    private static net.minecraft.world.phys.Vec3 observerReturn;
    private static float observerYaw, observerPitch;
    private static final Map<BlockPos, net.minecraft.world.level.block.state.BlockState> demoBlocks = new HashMap<>();
    private record Visit(Vec3 position, float yaw, float pitch, boolean flying,net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {}
    private static final Map<UUID, Visit> visits = new HashMap<>();
    private static final Path SNAPSHOT = DIR.resolve("cell-demo-state.nbt");
    private static String commandPlayer="DiscTester";

    static void saveSnapshot(ServerPlayer p) throws java.io.IOException {
        demoPlayer=p.getUUID();
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putUUID("Player", p.getUUID());
        tag.putString("Dimension",p.level().dimension().location().toString());
        tag.put("Inventory", p.getInventory().save(new net.minecraft.nbt.ListTag()));
        tag.putInt("Selected", p.getInventory().selected);
        tag.putBoolean("Flying",p.getAbilities().flying);
        tag.putInt("GameType",p.gameMode.getGameModeForPlayer().getId());
        tag.putDouble("X", p.getX()); tag.putDouble("Y", p.getY()); tag.putDouble("Z", p.getZ());
        tag.putFloat("Yaw", p.getYRot()); tag.putFloat("Pitch", p.getXRot());
        var level = p.serverLevel().getServer().overworld();
        var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
        tag.putLong("DayTime", level.getDayTime());
        tag.putBoolean("Daylight", level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
        tag.putBoolean("WeatherCycle", level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE));
        tag.putInt("ClearWeatherTime", data.getClearWeatherTime()); tag.putInt("RainTime", data.getRainTime());
        tag.putInt("ThunderTime", data.getThunderTime()); tag.putBoolean("Raining", data.isRaining());
        tag.putBoolean("Thundering", data.isThundering()); tag.putFloat("RainLevel", level.getRainLevel(1));
        tag.putFloat("ThunderLevel", level.getThunderLevel(1));
        net.minecraft.nbt.NbtIo.writeCompressed(tag, SNAPSHOT);
    }
    static void restoreStage(ServerLevel level, net.minecraft.nbt.CompoundTag tag) {
        if (!tag.contains("DayTime")) return;
        var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
        level.setDayTime(tag.getLong("DayTime"));
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(tag.getBoolean("Daylight"), level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(tag.getBoolean("WeatherCycle"), level.getServer());
        data.setClearWeatherTime(tag.getInt("ClearWeatherTime")); data.setRainTime(tag.getInt("RainTime"));
        data.setThunderTime(tag.getInt("ThunderTime")); data.setRaining(tag.getBoolean("Raining"));
        data.setThundering(tag.getBoolean("Thundering")); level.setRainLevel(tag.getFloat("RainLevel"));
        level.setThunderLevel(tag.getFloat("ThunderLevel"));
        syncWeather(level);
    }

    static void syncWeather(ServerLevel level) {
        for (var player : level.players()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    level.isRaining() ? net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING
                            : net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING, 0));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1)));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1)));
        }
    }
    static void restoreSnapshot(ServerPlayer p) throws java.io.IOException {
        if (!Files.exists(SNAPSHOT)) return;
        var tag = net.minecraft.nbt.NbtIo.readCompressed(SNAPSHOT, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        if (!p.getUUID().equals(tag.getUUID("Player"))) return;
        p.getInventory().load(tag.getList("Inventory", 10)); select(p, tag.getInt("Selected"));
        if(tag.contains("GameType"))p.setGameMode(net.minecraft.world.level.GameType.byId(tag.getInt("GameType")));
        if(tag.contains("Flying")){p.getAbilities().flying=tag.getBoolean("Flying");p.onUpdateAbilities();}
        var destination=tag.contains("Dimension")?p.serverLevel().getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(tag.getString("Dimension")))):p.serverLevel();
        p.teleportTo(destination, tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"), tag.getFloat("Yaw"), tag.getFloat("Pitch"));
        p.inventoryMenu.broadcastChanges();
        restoreStage(p.serverLevel().getServer().overworld(), tag);
        clearTargets(p.serverLevel());
        Files.delete(SNAPSHOT); savedInventory=null; demoPlayer=null; previewAge=-1;
        LogUtils.getLogger().info("LAUNCHER_DEBUG restored persistent demo inventory");
    }

    private static void clearTargets(ServerLevel level) {
        level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(-1000,-1000,-1000,1000,1000,1000))
                .stream().filter(e->e.getTags().contains("cms_gun_target")).forEach(Entity::discard);
    }

    static void select(ServerPlayer p, int slot) {
        p.getInventory().selected = slot;
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(slot));
    }

    static ItemStack cell(DiscCellTier tier, String kind) {
        ItemStack cell = new ItemStack(LauncherFeature.cell(tier));
        DiscCellItem.setMaster(cell, disc(kind));
        ChargeableItem.setEnergy(cell, tier.capacity());
        return cell;
    }
    @SubscribeEvent public static void spawned(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if(savedInventory!=null && !event.getLevel().isClientSide && !event.loadedFromDisk()
                && event.getEntity() instanceof DiscProjectile disc && disc.getOwner()!=null && disc.getOwner().getUUID().equals(demoPlayer)) disc.addTag("cms_launcher_check");
    }
    @SubscribeEvent
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer p))return;
        ServerLevel l=p.serverLevel();
        if(!l.getServer().getWorldData().getLevelName().equals("Disc Launcher Lab"))return;
        var allowedHosts = new ArrayList<String>(ServerConfig.ALLOWED_HOSTS.get());
        if (!allowedHosts.contains("127.0.0.1")) allowedHosts.add("127.0.0.1");
        ServerConfig.ALLOWED_HOSTS.set(allowedHosts);
        ServerConfig.ALLOWED_HOSTS.save();
        if (Boolean.getBoolean("cms.launcherDebug.preserveWorld")) {
            try { restoreSnapshot(p); } catch(java.io.IOException e) { throw new RuntimeException(e); }
            return;
        }
        l.setDayTime(6000);l.setWeatherParameters(600000,0,false,false);
        l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,l.getServer());
        l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
        for(int x=-36;x<=36;x++)for(int z=-25;z<=16;z++) {
            l.setBlock(new BlockPos(x,99,z),((x%8==0||z%8==0)?Blocks.WHITE_CONCRETE:Blocks.GRAY_CONCRETE).defaultBlockState(),3);
            for(int y=100;y<=105;y++)l.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        for(int x=-16;x<=16;x++)for(int y=100;y<105;y++)l.setBlock(new BlockPos(x,y,-22),Blocks.COPPER_BLOCK.defaultBlockState(),3);
        p.teleportTo(l,.5,100,9.5,180,0);
        equip(p,"custom");
        if(p.getGameProfile().getName().equals("DiscObserver")) {
            p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            p.teleportTo(l,20.5,100,6,90,0);
        }
        log("READY",p);
    }
    static void log(String action,ServerPlayer p) {
        String text=action+" tick="+p.level().getGameTime()+" loaded="+LauncherMagazine.count(p.getMainHandItem())+" reserved="+LauncherMagazine.reserved(p.getMainHandItem()).size()+" inventory="+p.getInventory().items;
        LogUtils.getLogger().info("LAUNCHER_DEBUG {}",text);
        try {Files.writeString(DIR.resolve("server-checks.log"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}
    }
    static ItemStack disc(String kind) {
        ItemStack d=new ItemStack(switch(kind){case "vanilla"->Items.MUSIC_DISC_CAT;case "dvd"->VideoFeature.DVD.get();case "blu"->VideoFeature.BLU_RAY.get();default->AudioFeature.CUSTOM_DISC.get();});
        if(kind.equals("custom"))d.set(AudioFeature.URL.get(),URL);
        if(kind.equals("blocked"))d.set(AudioFeature.URL.get(),"http://blocked.invalid/song.wav");
        if(kind.equals("dvd")||kind.equals("blu"))d.set(VideoFeature.PLAYLIST.get(),List.of(URL,URL+"?next=1"));
        return d;
    }
    static void equip(ServerPlayer p,String kind) {
        p.getInventory().clearContent();select(p,0);
        p.getInventory().setItem(0,new ItemStack(LauncherFeature.DISC_LAUNCHER.get()));
        for(int i=1;i<=6;i++) if (!DiscCellTier.at(i).optional() || net.neoforged.fml.ModList.get().isLoaded(DiscCellTier.PLANT_MOD_ID))
            p.getInventory().setItem(i,cell(DiscCellTier.at(i),kind));
        p.inventoryMenu.broadcastChanges();
    }
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if(event.getServer().getPlayerList().getPlayers().isEmpty())return;
        ServerPlayer p=event.getServer().getPlayerList().getPlayerByName(commandPlayer);
        if(p==null)return;
        if(!event.getServer().getWorldData().getLevelName().equals("Disc Launcher Lab"))return;
        Path cmd=DIR.resolve("command");
        try {
            if (previewAge >= 0 && LauncherMagazine.reloading(p.getMainHandItem()))
                p.getMainHandItem().set(LauncherFeature.LAUNCHER_RELOAD_START.get(), p.level().getGameTime() - previewAge);
            if (p.level().getGameTime() % 20 == 0) {
                Map<String, Double> clocks = new LinkedHashMap<>();
                Map<String, Map<String,Object>> partyClocks=new LinkedHashMap<>();
                for (Entity entity : p.serverLevel().getAllEntities())
                    if (entity instanceof DiscProjectile disc && disc.getTags().contains("cms_playback_check"))
                        clocks.put(disc.getUUID().toString(), disc.elapsedMusicSeconds());
                    else if(entity instanceof org.haruyq.createmediasystems.partybox.PartyBoxEntity box && box.getTags().contains("cms_partybox_check"))
                        partyClocks.put(box.getUUID().toString(),Map.of("seconds",box.seconds(),"energy",box.energy(),"playing",box.playing()));
                Files.writeString(DIR.resolve("server-probe.json"), new com.google.gson.Gson().toJson(
                        Map.of("wall", System.currentTimeMillis(), "tick", p.level().getGameTime(), "discs", clocks,"partyBoxes",partyClocks)));
            }
            if(!Files.exists(cmd))return;
            String action=Files.readString(cmd).strip();Files.delete(cmd);
            String[] parts=action.split(" ");ServerLevel l=p.serverLevel();
            var all=l.getEntitiesOfClass(DiscProjectile.class,new AABB(-1000,-1000,-1000,1000,1000,1000));
            switch(parts[0]) {
                case "control-player" -> {
                    if(savedInventory!=null)throw new IllegalStateException("Restore the current demo before switching its player");
                    if(parts.length!=2 || !(parts[1].equals("DiscTester") || parts[1].equals("DiscObserver"))
                            || event.getServer().getPlayerList().getPlayerByName(parts[1])==null)throw new IllegalArgumentException("Unknown debug player");
                    commandPlayer=parts[1];
                }
                case "megaphone-demo" -> {
                    if(savedInventory==null){
                        saveSnapshot(p);savedInventory=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)savedInventory.add(p.getInventory().getItem(i).copy());
                        savedSelected=p.getInventory().selected;savedPosition=p.position();savedYaw=p.getYRot();savedPitch=p.getXRot();savedDimension=p.level().dimension();
                    }
                    p.getInventory().clearContent();select(p,0);p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                    p.teleportTo(event.getServer().overworld(),700.5,116,688.5,0,0);p.getAbilities().flying=true;p.onUpdateAbilities();
                    for(int i=0;i<2;i++){
                        var horn=new ItemStack(i==0?MegaphoneFeature.MEGAPHONE_WHITE.get():MegaphoneFeature.MEGAPHONE_BLACK.get());
                        var battery=new ItemStack(PowerFeature.BATTERY.get());ChargeableItem.setEnergy(battery,BatteryItem.CAPACITY);
                        var media=disc(parts.length>1?parts[1]:"vanilla");
                        if(parts.length>1 && parts[1].equals("youtube"))media.set(AudioFeature.URL.get(),"https://youtu.be/m1TPvPU9WTw?si=eGxskvi9oXZlcCF6");
                        org.haruyq.createmediasystems.megaphone.MegaphoneData.contents(horn,p.level(),media,battery);
                        org.haruyq.createmediasystems.megaphone.MegaphoneData.controls(horn,26,1);
                        if(i==0)org.haruyq.createmediasystems.megaphone.MegaphoneData.play(horn,p.level(),true);
                        p.getInventory().setItem(i,horn);
                    }
                    p.inventoryMenu.broadcastChanges();
                }
                case "megaphone-style" -> {
                    if(savedInventory==null)throw new IllegalStateException("No horn demo active");
                    select(p,Integer.parseInt(parts[1]));org.haruyq.createmediasystems.megaphone.MegaphoneData.play(p.getMainHandItem(),p.level(),true);p.inventoryMenu.broadcastChanges();
                }
                case "dragon-demo" -> {
                    if(savedInventory==null)throw new IllegalStateException("Start megaphone-demo first");
                    var dragon=net.minecraft.world.entity.EntityType.ENDER_DRAGON.create(l);dragon.setPos(700.5,116,700.5);dragon.addTag("cms_gun_target");dragon.setCustomName(Component.literal("CMS DRAGON CHECK"));
                    dragon.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOVERING);l.addFreshEntity(dragon);
                }
                case "dragon-pin" -> {
                    if(savedInventory==null)throw new IllegalStateException("No demo active");
                    for(var target:l.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,new AABB(680,100,680,720,140,720)))if(target.getTags().contains("cms_gun_target")) {
                        for(int index:new int[]{0,2,4,6}){
                            var part=target.getSubEntities()[index];var blade=new DiscProjectile(l,p,disc("vanilla"));blade.setCellTier(DiscCellTier.COPPER);
                            if(index==0){
                                blade.setPos(part.getX()+(part.getX()-part.xOld),part.getY()-.18,part.getZ()+(part.getZ()-part.zOld));blade.setDeltaMovement(0,5.2,0);
                            }else{
                                blade.setPos(part.position().add(-part.getBbWidth()/2-.2,part.getBbHeight()*(index==6?.75:.5),0));blade.setDeltaMovement(5.2,0,0);
                            }
                            // Deterministic post-impact render fixture. Swept collision is covered by server GameTests.
                            blade.onHitEntity(new EntityHitResult(part,part.position().add(-part.getBbWidth()/2,part.getBbHeight()*.5,0)));
                            blade.addTag("cms_launcher_check");l.addFreshEntity(blade);
                        }
                    }
                }
                case "dragon-shift" -> {
                    if(savedInventory==null)throw new IllegalStateException("No demo active");
                    for(var dragon:l.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,new AABB(650,90,650,750,150,750)))if(dragon.getTags().contains("cms_gun_target"))
                        dragon.moveTo(dragon.getX()+3,dragon.getY()+2,dragon.getZ()+4,dragon.getYRot()+70,0);
                }
                case "partybox-grant" -> {
                    if(p.getInventory().countItem(PartyBoxFeature.PARTYBOX.get())==0){
                        p.getInventory().placeItemBackInInventory(new ItemStack(PartyBoxFeature.PARTYBOX.get()));
                        ItemStack battery=new ItemStack(PowerFeature.BATTERY.get());
                        ChargeableItem.setEnergy(battery,BatteryItem.CAPACITY);
                        p.getInventory().placeItemBackInInventory(battery);p.inventoryMenu.broadcastChanges();
                    }
                }
                case "partybox-demo" -> {
                    if(savedInventory==null){
                        saveSnapshot(p);savedInventory=new ArrayList<>();
                        for(int i=0;i<p.getInventory().getContainerSize();i++)savedInventory.add(p.getInventory().getItem(i).copy());
                        savedSelected=p.getInventory().selected;savedPosition=p.position();savedYaw=p.getYRot();savedPitch=p.getXRot();savedDimension=p.level().dimension();
                    }
                    l.getEntitiesOfClass(org.haruyq.createmediasystems.partybox.PartyBoxEntity.class,new AABB(-1000,-1000,-1000,1000,1000,1000))
                            .stream().filter(box->box.getTags().contains("cms_partybox_check")).forEach(Entity::discard);
                    p.getInventory().clearContent();select(p,0);p.getAbilities().flying=false;p.onUpdateAbilities();
                    p.teleportTo(l,.5,100,9.5,180,25);
                    var box=new org.haruyq.createmediasystems.partybox.PartyBoxEntity(PartyBoxFeature.PARTYBOX_ENTITY.get(),l);
                    box.setPos(.5,100,7.8);box.setYRot(180);box.addTag("cms_partybox_check");
                    ItemStack battery=new ItemStack(PowerFeature.BATTERY.get());
                    ChargeableItem.setEnergy(battery,BatteryItem.CAPACITY);
                    box.inventory.setItem(1,battery);ItemStack media=disc(parts.length>1?parts[1]:"vanilla");
                    if(parts.length>1 && parts[1].equals("youtube"))media.set(AudioFeature.URL.get(),"https://youtu.be/m1TPvPU9WTw?si=eGxskvi9oXZlcCF6");
                    box.inventory.setItem(0,media);
                    l.addFreshEntity(box);p.getInventory().setItem(1,battery.copy());
                    p.getInventory().setItem(2,new ItemStack(PartyBoxFeature.PARTYBOX.get()));p.inventoryMenu.broadcastChanges();
                }
                case "cell-demo" -> {
                    if (savedInventory == null) {
                        saveSnapshot(p);
                        savedInventory = new ArrayList<>();
                        for (int i = 0; i < p.getInventory().getContainerSize(); i++) savedInventory.add(p.getInventory().getItem(i).copy());
                        savedSelected = p.getInventory().selected;
                        savedPosition = p.position(); savedYaw = p.getYRot(); savedPitch = p.getXRot();savedDimension=p.level().dimension();
                    }
                    equip(p, parts.length > 1 ? parts[1] : "vanilla");
                    p.teleportTo(event.getServer().overworld(), .5, 100, 9.5, 180, 0);
                }
                case "cell-grant" -> {
                    ItemStack media = new ItemStack(AudioFeature.CUSTOM_DISC.get());
                    media.set(AudioFeature.URL.get(), "https://youtu.be/m1TPvPU9WTw?si=eGxskvi9oXZlcCF6");
                    media.set(AudioFeature.TITLE.get(), "YouTube playback test");
                    for (DiscCellTier tier : DiscCellTier.values()) if (!tier.optional()) {
                        ItemStack kit = cell(tier, "vanilla"); DiscCellItem.setMaster(kit, media);
                        if (tier == DiscCellTier.DIAMOND && p.getMainHandItem().is(LauncherFeature.DISC_LAUNCHER.get())
                                && LauncherMagazine.cell(p.getMainHandItem()).isEmpty()) LauncherMagazine.setCell(p.getMainHandItem(), kit);
                        else p.getInventory().placeItemBackInInventory(kit);
                    }
                    p.getInventory().placeItemBackInInventory(new ItemStack(LauncherFeature.DISC_CELL_CHARGER.get()));
                    p.inventoryMenu.broadcastChanges();
                }
                case "cell-charger" -> {
                    if(savedInventory==null)throw new IllegalStateException("Start cell-demo before placing an audit charger");
                    BlockPos pos = new BlockPos(2, 100, 7), motor = pos.below();
                    for (BlockPos changed : List.of(pos, motor)) {
                        if (l.getBlockEntity(changed) != null && !demoBlocks.containsKey(changed)) throw new IllegalStateException("Existing block entity at demo position");
                        demoBlocks.putIfAbsent(changed, l.getBlockState(changed));
                    }
                    l.setBlockAndUpdate(motor, com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState()
                            .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, net.minecraft.core.Direction.UP));
                    l.setBlockAndUpdate(pos, LauncherFeature.DISC_CELL_CHARGER.get().defaultBlockState());
                    var charger = (DiscCellCharger) l.getBlockEntity(pos);
                    charger.take();
                    ItemStack demo = cell(DiscCellTier.RESONANT, "vanilla");
                    if(parts.length==1)ChargeableItem.setEnergy(demo,0);
                    charger.insert(demo);
                    if(parts.length>1 && parts[1].equals("static"))l.setBlockAndUpdate(motor,Blocks.AIR.defaultBlockState());
                    p.teleportTo(l, .5, 100, 9.5, -145, 15);
                }
                case "cell-demo-end" -> {
                    previewAge = -1;
                    clearTargets(l);
                    all.stream().filter(d -> d.getTags().contains("cms_launcher_check")).forEach(Entity::discard);
                    l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(-1000,-1000,-1000,1000,1000,1000))
                            .stream().filter(e->e.getTags().contains("cms_ground_check")).forEach(Entity::discard);
                    l.getEntitiesOfClass(org.haruyq.createmediasystems.partybox.PartyBoxEntity.class,new AABB(-1000,-1000,-1000,1000,1000,1000))
                            .stream().filter(box->box.getTags().contains("cms_partybox_check")).forEach(Entity::discard);
                    for (var entry : demoBlocks.entrySet()) {
                        if (l.getBlockEntity(entry.getKey()) instanceof DiscCellCharger charger) charger.take();
                        l.setBlockAndUpdate(entry.getKey(), entry.getValue());
                    }
                    demoBlocks.clear();
                    if (savedInventory != null) {
                        p.closeContainer(); p.getInventory().clearContent();
                        for (int i = 0; i < savedInventory.size(); i++) p.getInventory().setItem(i, savedInventory.get(i));
                        select(p, savedSelected);
                        p.teleportTo(savedDimension==null?l:event.getServer().getLevel(savedDimension), savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                        savedInventory = null;
                        demoPlayer = null;
                        p.inventoryMenu.broadcastChanges();
                        var tag=net.minecraft.nbt.NbtIo.readCompressed(SNAPSHOT, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                        restoreStage(event.getServer().overworld(),tag);if(tag.contains("Flying")){p.getAbilities().flying=tag.getBoolean("Flying");p.onUpdateAbilities();}
                        if(tag.contains("GameType"))p.setGameMode(net.minecraft.world.level.GameType.byId(tag.getInt("GameType")));
                        Files.deleteIfExists(SNAPSHOT);
                    }
                }
                case "cell-preview" -> {
                    previewAge = Integer.parseInt(parts[1]);
                    if (previewAge >= 0 && !LauncherMagazine.reloading(p.getMainHandItem()))
                        LauncherMagazine.reserve(p, p.getMainHandItem(), l.getGameTime() - previewAge);
                }
                case "cell-fixture" -> {
                    if (savedInventory == null) throw new IllegalStateException("Start cell-demo before changing its fixture");
                    previewAge = -1;
                    ItemStack gun = p.getMainHandItem();
                    if (!gun.is(LauncherFeature.DISC_LAUNCHER.get())) throw new IllegalStateException("Hold the demo launcher");
                    gun.remove(LauncherFeature.LAUNCHER_PENDING_CELL.get());
                    gun.remove(LauncherFeature.LAUNCHER_RELOAD_START.get());
                    gun.remove(LauncherFeature.LAUNCHER_EJECT_ONLY.get());
                    DiscCellTier tier = DiscCellTier.at(Integer.parseInt(parts[1]));
                    ItemStack fixture = cell(tier, "vanilla");
                    if (parts.length > 2) ChargeableItem.setEnergy(fixture, Integer.parseInt(parts[2]));
                    LauncherMagazine.setCell(gun, fixture);
                    p.inventoryMenu.broadcastChanges();
                }
                case "cell-view" -> {
                    if (savedInventory == null) throw new IllegalStateException("Start cell-demo before moving its camera");
                    p.teleportTo(l, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                            Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
                }
                case "ground-demo" -> {
                    if(savedInventory==null)throw new IllegalStateException("Start cell-demo before showing ground items");
                    for(int i=0;i<2;i++) {
                        var item=new net.minecraft.world.entity.item.ItemEntity(l,i==0?0:1.15,101.05,7.5,
                                new ItemStack(i==0?LauncherFeature.DISC_LAUNCHER.get():Items.DIAMOND_SWORD));
                        item.addTag("cms_ground_check");item.setPickUpDelay(32767);item.setDeltaMovement(Vec3.ZERO);l.addFreshEntity(item);
                    }
                    p.teleportTo(l,.6,100,9.7,180,25);select(p,8);p.inventoryMenu.broadcastChanges();
                }
                case "music-sync-start" -> {
                    if(savedInventory==null)throw new IllegalStateException("Start partybox-demo before testing playback");
                    var host=event.getServer().getPlayerList().getPlayerByName("DiscTester");
                    Vec3 center=host!=null && host.level()==l?host.position().add(0,0,4):p.position().add(0,0,-3);
                    boolean projection=parts.length>1 && parts[1].startsWith("disc");
                    for(var box:l.getEntitiesOfClass(org.haruyq.createmediasystems.partybox.PartyBoxEntity.class,new AABB(-1000,-1000,-1000,1000,1000,1000)))
                        if(box.getTags().contains("cms_partybox_check")){box.setNoGravity(true);box.setPos(center);if(projection)box.pause();else box.play();}
                    if(projection) {
                        clearTargets(l);all.stream().filter(d->d.getTags().contains("cms_launcher_check")).forEach(Entity::discard);
                        var target=net.minecraft.world.entity.EntityType.IRON_GOLEM.create(l);target.setPos(center.add(2,0,0));target.setNoAi(true);target.setNoGravity(true);target.setInvulnerable(true);target.addTag("cms_gun_target");l.addFreshEntity(target);
                        var media=new ItemStack(parts[1].equals("disc-youtube")?AudioFeature.CUSTOM_DISC.get():Items.MUSIC_DISC_CAT);
                        if(parts[1].equals("disc-youtube"))media.set(AudioFeature.URL.get(),"https://www.youtube.com/watch?v=tVSCbJZ54tc");
                        var disc=new DiscProjectile(l,p,media);disc.addTag("cms_launcher_check");disc.setDeltaMovement(3,0,0);
                        disc.onHitEntity(new EntityHitResult(target,target.position().add(-.76,1,0)));
                        var tag=new net.minecraft.nbt.CompoundTag();disc.save(tag);tag.putLong("CmsDiscMusicStart",l.getGameTime()-620);disc.load(tag);l.addFreshEntity(disc);
                    }
                    p.teleportTo(l,center.x,center.y,center.z+3,180,0);p.getAbilities().flying=true;p.onUpdateAbilities();
                }
                case "music-sync-end" -> {
                    if(savedInventory==null)throw new IllegalStateException("Start the music fixture first");
                    for(var disc:all)if(disc.getTags().contains("cms_launcher_check")) {
                        String url=org.haruyq.createmediasystems.stream.MediaItem.url(disc.getItem());if(url==null)continue;
                        double duration=org.haruyq.createmediasystems.stream.Streams.duration(url);
                        if(duration<=3)throw new IllegalStateException("Wait for API duration metadata");
                        var tag=new net.minecraft.nbt.CompoundTag();disc.save(tag);tag.putDouble("CmsDiscDuration",duration);
                        tag.putLong("CmsDiscMusicStart",l.getGameTime()-(long)((duration-2)*20));disc.load(tag);
                    }
                }
                case "cell-stage" -> {
                    if (savedInventory == null) throw new IllegalStateException("Start cell-demo before changing its stage");
                    l.setDayTime(Long.parseLong(parts[1]));
                    l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, l.getServer());
                    l.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, l.getServer());
                    l.setWeatherParameters(600000, 0, false, false); l.setRainLevel(0); l.setThunderLevel(0);
                    syncWeather(l);
                }
                case "cell-select" -> { select(p, Integer.parseInt(parts[1])); p.inventoryMenu.broadcastChanges(); }
                case "cell-eject" -> LauncherMagazine.eject(p, p.getMainHandItem(), l.getGameTime());
                case "cell-menu" -> {
                    select(p, Integer.parseInt(parts[1]));
                    p.getMainHandItem().getItem().use(l,p,InteractionHand.MAIN_HAND);
                }
                case "observer-away" -> {
                    for (var other : event.getServer().getPlayerList().getPlayers()) if (other.getGameProfile().getName().equals("DiscObserver")) {
                        observerReturn = other.position();observerReturnDimension=other.level().dimension(); observerYaw = other.getYRot(); observerPitch = other.getXRot();
                        other.teleportTo(l, other.getX() + 512, 160, other.getZ(), other.getYRot(), other.getXRot());
                    }
                }
                case "all-away" -> {
                    for (var other : event.getServer().getPlayerList().getPlayers()) {
                        visits.put(other.getUUID(), new Visit(other.position(), other.getYRot(), other.getXRot(), other.getAbilities().flying,other.level().dimension()));
                        other.getAbilities().flying = true; other.onUpdateAbilities();
                        other.teleportTo(l, other.getX() + 512, 160, other.getZ(), other.getYRot(), other.getXRot());
                    }
                }
                case "all-return" -> {
                    for (var other : event.getServer().getPlayerList().getPlayers()) {
                        Visit visit = visits.remove(other.getUUID());
                        if (visit == null) continue;
                        other.teleportTo(event.getServer().getLevel(visit.dimension), visit.position.x, visit.position.y, visit.position.z, visit.yaw, visit.pitch);
                        other.getAbilities().flying = visit.flying; other.onUpdateAbilities();
                    }
                }
                case "observer-return" -> {
                    if (observerReturn != null) for (var other : event.getServer().getPlayerList().getPlayers()) if (other.getGameProfile().getName().equals("DiscObserver"))
                        other.teleportTo(observerReturnDimension==null?l:event.getServer().getLevel(observerReturnDimension), observerReturn.x, observerReturn.y, observerReturn.z, observerYaw, observerPitch);
                }
                case "playback-check" -> {
                    for (int i = 0; i < 2; i++) {
                        ItemStack item = new ItemStack(AudioFeature.CUSTOM_DISC.get());
                        item.set(AudioFeature.URL.get(), parts[1]);
                        var d = new DiscProjectile(l, p, item);
                        d.setPos(p.getX() + 2 + i * 3, p.getY() + 12, p.getZ());
                        d.addTag("cms_playback_check");
                        var tag = new net.minecraft.nbt.CompoundTag();
                        d.save(tag);
                        tag.putBoolean("CmsDiscEmbedded", true);
                        tag.putLong("CmsDiscEmbeddedAt", l.getGameTime());
                        tag.putLong("CmsDiscMusicStart", l.getGameTime() - (i == 0 ? 260 : 40));
                        d.load(tag);
                        l.addFreshEntity(d);
                        log("PLAYBACK_CHECK_" + i + " uuid=" + d.getUUID(), p);
                    }
                }
                case "playback-check-clear" -> all.stream().filter(d -> d.getTags().contains("cms_playback_check"))
                        .forEach(Entity::discard);
                case "equip" -> {all.forEach(Entity::discard);equip(p,parts[1]);p.teleportTo(l,.5,100,9.5,180,0);}
                case "reload" -> LauncherMagazine.reserve(p,p.getMainHandItem(),l.getGameTime());
                case "fire" -> {
                    DiscLauncherItem.fire(p,p.getYRot(),p.getXRot());
                    if (savedInventory != null) l.getEntitiesOfClass(DiscProjectile.class, p.getBoundingBox().inflate(8))
                            .stream().filter(d -> !all.contains(d)).forEach(d -> d.addTag("cms_launcher_check"));
                }
                case "gun-target" -> {
                    if(savedInventory==null)throw new IllegalStateException("Start cell-demo before target checks");
                    l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(-1000,-1000,-1000,1000,1000,1000))
                            .stream().filter(e->e.getTags().contains("cms_gun_target")).forEach(Entity::discard);
                    var target=new net.minecraft.world.entity.animal.IronGolem(net.minecraft.world.entity.EntityType.IRON_GOLEM,l);
                    target.setPos(10.5,100,-5.5);target.setNoAi(true);target.setPersistenceRequired();target.addTag("cms_gun_target");
                    target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);target.setHealth(1000);
                    if(parts.length>1 && parts[1].equals("shield")){target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).setBaseValue(60);target.setAbsorptionAmount(60);}
                    target.setCustomName(Component.literal("CMS BALLISTICS / "+(parts.length>1?parts[1]:"body")));target.setCustomNameVisible(true);
                    l.addFreshEntity(target);
                    double targetY=parts.length>1 && parts[1].equals("head")?target.getEyeY():target.getY()+1.0;
                    float pitch=(float)-Math.toDegrees(Math.atan2(targetY-(100+p.getEyeHeight()),15));
                    p.teleportTo(l,10.5,100,9.5,180,pitch);
                }
                case "gun-target-clear" -> l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(-1000,-1000,-1000,1000,1000,1000))
                        .stream().filter(e->e.getTags().contains("cms_gun_target")).forEach(Entity::discard);
                case "pin-player" -> {
                    if(savedInventory==null)throw new IllegalStateException("Start cell-demo before the pin check");
                    p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.getAbilities().flying=false;p.onUpdateAbilities();
                    var source=new net.neoforged.neoforge.common.util.FakePlayer(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"DiscPinProbe"));
                    for(int i=0;i<2;i++) {
                        var disc=new DiscProjectile(l,source,disc("vanilla"));disc.setCellTier(DiscCellTier.at(i+1));
                        disc.setPos(p.getX()-3,i==0?p.getY()+.95:p.getEyeY(),p.getZ());disc.shoot(1,0,0,5.2f,0);
                        disc.addTag("cms_launcher_check");l.addFreshEntity(disc);
                    }
                }
                case "pin-reload" -> {
                    if(savedInventory==null)throw new IllegalStateException("No pin check active");
                    for(var old:all.stream().filter(d->d.getTags().contains("cms_launcher_check") && d.attached()).toList()) {
                        var tag=new net.minecraft.nbt.CompoundTag();old.save(tag);old.discard();
                        var restored=net.minecraft.world.entity.EntityType.loadEntityRecursive(tag,l,e->e);
                        if(restored!=null)l.addFreshEntity(restored);
                    }
                }
                case "clear" -> all.forEach(Entity::discard);
                case "pickup" -> {if(!all.isEmpty()){var d=all.getFirst();p.teleportTo(l,d.getX(),100,d.getZ(),180,0);}}
                case "home" -> p.teleportTo(l,.5,100,9.5,180,0);
                case "away" -> p.teleportTo(l,.5,100,100,180,0);
                case "flyby" -> {
                    all.forEach(Entity::discard);
                    var d=new DiscProjectile(l,p,disc(parts.length>1?parts[1]:"custom"));
                    d.setPos(-32,101.4,6);d.setDeltaMovement(1.5,0,0);l.addFreshEntity(d);
                }
                case "savecheck" -> {
                    for(var d:all) {
                        var tag=new net.minecraft.nbt.CompoundTag();d.save(tag);
                        var e=net.minecraft.world.entity.EntityType.loadEntityRecursive(tag,l,x->x);
                        if(!(e instanceof DiscProjectile copy)||!ItemStack.matches(d.getItem(),copy.getItem())||d.embedded()!=copy.embedded())throw new AssertionError("Disc save/load lost metadata");
                        log("PASS_SAVE_METADATA "+tag,p);
                    }
                }
                case "expire" -> {for(var d:all){var tag=new net.minecraft.nbt.CompoundTag();d.save(tag);tag.putLong("CmsDiscMusicStart",l.getGameTime()-3600);d.load(tag);}}
                case "golem" -> {
                    l.getEntitiesOfClass(net.minecraft.world.entity.animal.IronGolem.class,new AABB(-5,99,-10,5,106,0)).forEach(Entity::discard);
                    var golem=net.minecraft.world.entity.EntityType.IRON_GOLEM.create(l);
                    golem.setNoAi(true);golem.setPos(.5,100,-6);l.addFreshEntity(golem);
                }
                case "checkgolem" -> {
                    var golems=l.getEntitiesOfClass(net.minecraft.world.entity.animal.IronGolem.class,new AABB(-5,99,-10,5,106,0));
                    float damage = LauncherMagazine.tier(p.getMainHandItem()).damage;
                    if(golems.size()!=1 || Math.abs(golems.getFirst().getHealth()-(100-damage))>0.001 || all.stream().noneMatch(DiscProjectile::embedded))throw new AssertionError("Golem damage/embed failed");
                    log("PASS_ENTITY_DAMAGE_"+(int)damage+" health="+golems.getFirst().getHealth(),p);
                }
                case "observer-near" -> {
                    if(!all.isEmpty())for(var other:event.getServer().getPlayerList().getPlayers())if(other.getGameProfile().getName().equals("DiscObserver")) {
                        var d=all.getFirst();other.teleportTo(l,d.getX(),100,d.getZ()+.1,180,0);
                    }
                }
                case "observer-home" -> {
                    for(var other:event.getServer().getPlayerList().getPlayers())if(other.getGameProfile().getName().equals("DiscObserver"))other.teleportTo(l,20.5,100,6,90,0);
                }
                case "observer-view" -> {
                    for(var other:event.getServer().getPlayerList().getPlayers())if(other.getGameProfile().getName().equals("DiscObserver"))
                        other.teleportTo(l,Double.parseDouble(parts[1]),Double.parseDouble(parts[2]),Double.parseDouble(parts[3]),Float.parseFloat(parts[4]),Float.parseFloat(parts[5]));
                }
                case "connections" -> log("PASS_NETWORK_PLAYERS="+event.getServer().getPlayerList().getPlayerCount(),p);
                default -> throw new IllegalArgumentException(action);
            }
            log(action,p);
        } catch(Exception e){LogUtils.getLogger().error("LAUNCHER_DEBUG command failed",e);}
    }
}
