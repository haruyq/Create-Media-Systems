package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.power.ChargeableItem;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.launcher.DiscProjectile;
import org.haruyq.createmediasystems.launcher.LauncherMagazine;
import org.haruyq.createmediasystems.mixin.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.openal.AL10;

/** Opt-in real-engine diagnostic fixture; never part of the release build. */
@EventBusSubscriber(modid="create_media_systems", value=Dist.CLIENT)
public final class LauncherDebugClient {
    private static boolean requested;
    private static int ticks;
    private static int walkTicks;
    private static int fireTicks;
    private static boolean firing,crouchFire;
    private static boolean inputCapture,aimHeld;
    private static boolean savedPauseOnLostFocus;
    static {LauncherClient.additionalInput(()->inputCapture);}
    private static final Gson JSON = new Gson();
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();
        Path dir=mc.gameDirectory.toPath();
        try {
            if(inputCapture){mc.options.keyAttack.setDown(fireTicks>0);mc.options.keyUse.setDown(aimHeld);}
            if(fireTicks>0){mc.options.keyAttack.setDown(true);--fireTicks;}
            else if(firing){
                mc.options.keyAttack.setDown(false);firing=false;
                if(crouchFire && mc.player!=null){mc.player.input.shiftKeyDown=false;mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));crouchFire=false;}
            }
            if(walkTicks>0){mc.options.keyUp.setDown(mc.screen==null);if(--walkTicks==0)mc.options.keyUp.setDown(false);}
            if (Files.deleteIfExists(dir.resolve("debug-quit"))) { LauncherDebugCapture.stop(); if(inputCapture)mc.options.pauseOnLostFocus=savedPauseOnLostFocus; mc.stop(); return; }
            Path command=dir.resolve("client-command");
            if(Files.exists(command)) {
                String action=Files.readString(command).strip();Files.delete(command);
                if(action.startsWith("record-start ")) LauncherDebugCapture.start(action.substring("record-start ".length()));
                if(action.equals("record-stop")) LauncherDebugCapture.stop();
                if(action.startsWith("walk-forward "))walkTicks=Integer.parseInt(action.substring(13));
                if(action.equals("walk-stop")){walkTicks=0;mc.options.keyUp.setDown(false);}
                if(action.startsWith("partybox-interact ") && mc.player!=null && mc.gameMode!=null){
                    var target=mc.level.getEntity(Integer.parseInt(action.substring(18)));
                    if(target!=null)mc.gameMode.interact(mc.player,target,InteractionHand.MAIN_HAND);
                }
                if(action.startsWith("partybox-menu ") && mc.player!=null && mc.gameMode!=null){
                    var target=mc.level.getEntity(Integer.parseInt(action.substring(14)));
                    mc.player.setShiftKeyDown(true);
                    mc.player.input.shiftKeyDown=true;
                    mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                    if(target!=null)mc.gameMode.interact(mc.player,target,InteractionHand.MAIN_HAND);
                    mc.player.setShiftKeyDown(false);
                    mc.player.input.shiftKeyDown=false;
                    mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                }
                if(action.startsWith("partybox-action ")){
                    String[] bits=action.split(" ");var target=mc.level.getEntity(Integer.parseInt(bits[1]));
                    if(target instanceof org.haruyq.createmediasystems.partybox.PartyBoxEntity box)
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new org.haruyq.createmediasystems.partybox.PartyBoxAction(box.getId(),org.haruyq.createmediasystems.partybox.PartyBoxAction.Kind.byId(Integer.parseInt(bits[2])),Integer.parseInt(bits[3]),box.revision()));
                }
                if(action.equals("launcher-reload")) net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        org.haruyq.createmediasystems.launcher.DiscLauncherReloadPacket.INSTANCE);
                if(action.equals("launcher-fire")){fireTicks=2;firing=true;mc.options.keyAttack.setDown(true);}
                if(action.startsWith("launcher-hold-fire ")){fireTicks=Integer.parseInt(action.substring(19));firing=true;mc.options.keyAttack.setDown(true);}
                if(action.startsWith("launcher-aim ")){aimHeld=action.substring(13).equals("1");mc.options.keyUse.setDown(aimHeld);}
                if(action.equals("megaphone-open") && mc.player!=null && mc.gameMode!=null)mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
                if(action.startsWith("megaphone-action ") && mc.player!=null){var bits=action.split(" ");net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new org.haruyq.createmediasystems.megaphone.MegaphoneAction(mc.player.containerMenu.containerId,org.haruyq.createmediasystems.megaphone.MegaphoneAction.Kind.byId(Integer.parseInt(bits[1])),Integer.parseInt(bits[2])));}
                if(action.startsWith("input-capture ")) {
                    boolean capture=action.substring(14).equals("1");
                    if(capture && !inputCapture){savedPauseOnLostFocus=mc.options.pauseOnLostFocus;mc.options.pauseOnLostFocus=false;}
                    if(!capture && inputCapture)mc.options.pauseOnLostFocus=savedPauseOnLostFocus;
                    inputCapture=action.substring(14).equals("1");fireTicks=0;aimHeld=false;
                    mc.options.keyAttack.setDown(false);mc.options.keyUse.setDown(false);
                }
                if(action.startsWith("damage-mode "))LauncherClient.DAMAGE_NUMBERS.set(
                        LauncherDamageDisplay.Mode.valueOf(action.substring(12).toUpperCase(java.util.Locale.ROOT)));
                if(action.equals("launcher-crouch-fire") && mc.player!=null && mc.gameMode!=null) {
                    mc.player.input.shiftKeyDown=true;
                    mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                    fireTicks=2;firing=true;crouchFire=true;mc.options.keyAttack.setDown(true);
                }
                if(action.equals("reload")) mc.reloadResourcePacks();
                if(action.startsWith("screenshot ")) {
                    String name = action.substring("screenshot ".length());
                    if (!name.matches("[a-zA-Z0-9_-]+\\.png")) throw new IllegalArgumentException("Invalid capture name");
                    net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("LAUNCHER_DEBUG {}", message.getString()));
                }
                if(action.equals("close-screen")) mc.setScreen(null);
                if(action.startsWith("recipes ")) mc.setScreen(new LauncherRecipeScreen(Integer.parseInt(action.substring(8))));
                if(action.startsWith("models "))mc.setScreen(new LauncherModelAuditScreen(Integer.parseInt(action.substring(7))));
                if(action.startsWith("horn-model "))mc.setScreen(new MegaphoneModelAuditScreen(Integer.parseInt(action.substring(11))));
                if(action.equals("sound-pause"))mc.getSoundManager().pause();
                if(action.equals("sound-resume"))mc.getSoundManager().resume();
                if(action.startsWith("gui-scale ")) {mc.options.guiScale().set(Integer.parseInt(action.substring(10)));mc.resizeDisplay();}
                if(action.equals("camera-third")) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                if(action.startsWith("pin-crouch "))mc.options.keyShift.setDown(action.substring(11).equals("1"));
                if(action.equals("camera-first")) mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                if(action.equals("staticpack")) {
                    var repo=mc.getResourcePackRepository();repo.reload();
                    var selected=new ArrayList<String>(repo.getSelectedIds());selected.add("file/StaticStereo");
                    repo.setSelected(selected);mc.options.resourcePacks.add("file/StaticStereo");
                    mc.reloadResourcePacks();
                }
                if(action.equals("disconnect") && mc.level!=null) {
                    mc.level.disconnect();mc.disconnect();mc.setScreen(new TitleScreen());
                }
                if(action.equals("rejoin")) mc.createWorldOpenFlows().openWorld("disc_lab",null);
                if(action.equals("lan") && mc.getSingleplayerServer()!=null) {
                    boolean published=mc.getSingleplayerServer().publishServer(GameType.CREATIVE,false,25591);
                    // Local development profiles have no online access token.
                    mc.getSingleplayerServer().setUsesAuthentication(false);
                    LogUtils.getLogger().info("LAUNCHER_DEBUG LAN published={}",published);
                }
                LogUtils.getLogger().info("LAUNCHER_DEBUG client command {}",action);
            }
            if (!requested && mc.level==null && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
                requested=true;
                if(Boolean.getBoolean("cms.launcherDebug.observer")) {
                    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(),mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString("127.0.0.1:25591"),
                        new net.minecraft.client.multiplayer.ServerData("Launcher LAN", "127.0.0.1:25591", net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null);
                }
                else if (Files.exists(dir.resolve("saves/disc_lab/level.dat"))) mc.createWorldOpenFlows().openWorld("disc_lab",null);
                else mc.createWorldOpenFlows().createFreshLevel("disc_lab",
                    new LevelSettings("Disc Launcher Lab",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(12345L,false,false),WorldPresets::createNormalWorldDimensions,(Screen)null);
            }
            if (++ticks%5!=0 || mc.level==null || mc.player==null) return;
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("wall",System.currentTimeMillis()); row.put("tick",mc.level.getGameTime());
            row.put("screen",mc.screen==null?"none":mc.screen.getClass().getSimpleName());
            row.put("camera",mc.options.getCameraType().name());
            row.put("guiScale",mc.getWindow().getGuiScale());
            row.put("megaphonePlaybacks",org.haruyq.createmediasystems.megaphone.client.MegaphoneClient.playbacks());
            var groundItems=new ArrayList<Map<String,Object>>();
            for(var entity:mc.level.entitiesForRendering())if(entity instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(LauncherFeature.DISC_LAUNCHER.get()))
                groundItems.add(Map.of("x",item.getX(),"y",item.getY(),"z",item.getZ(),"item",item.getItem().toString()));
            row.put("groundLaunchers",groundItems);
            if(mc.level.getBlockEntity(new net.minecraft.core.BlockPos(2,100,7)) instanceof org.haruyq.createmediasystems.launcher.DiscCellCharger charger)
                row.put("charger",Map.of("speed",charger.getSpeed(),"energy",ChargeableItem.energy(charger.cell()),"cell",charger.cell().toString()));
            row.put("player",mc.player.getName().getString());
            row.put("spectator",mc.player.isSpectator());
            row.put("allowedHosts",org.haruyq.createmediasystems.ServerConfig.ALLOWED_HOSTS.get());
            row.put("listenerX",mc.player.getX());row.put("listenerY",mc.player.getY());row.put("listenerZ",mc.player.getZ());
            row.put("dimension",mc.level.dimension().location().toString());
            row.put("playerId",mc.player.getId());row.put("creative",mc.player.getAbilities().instabuild);row.put("health",mc.player.getHealth());
            row.put("crouching",mc.player.isCrouching());row.put("bodyYaw",mc.player.yBodyRot);row.put("headYaw",mc.player.yHeadRot);
            row.put("loaded",LauncherMagazine.count(mc.player.getMainHandItem()));
            row.put("capacity",org.haruyq.createmediasystems.launcher.LauncherReadout.of(mc.player.getMainHandItem()).capacity());
            row.put("reserved",LauncherMagazine.pending(mc.player.getMainHandItem()).isEmpty()?0:1);
            row.put("reloading",LauncherMagazine.reloading(mc.player.getMainHandItem()));
            row.put("ejecting",LauncherMagazine.ejecting(mc.player.getMainHandItem()));
            row.put("aim",LauncherClient.aim(1));row.put("gunKick",LauncherClient.kick(1));
            var diagnostics=LauncherClient.diagnostics();row.put("reticleDrawn",diagnostics.reticleDrawn());
            row.put("trigger",mc.options.keyAttack.isDown());row.put("aimKey",mc.options.keyUse.isDown());
            row.put("usingItem",mc.player.isUsingItem());row.put("yaw",mc.player.getYRot());row.put("pitch",mc.player.getXRot());
            row.put("confirmedShots",diagnostics.confirmedShots());row.put("confirmedHits",diagnostics.confirmedHits());row.put("confirmedHeadshots",diagnostics.confirmedHeadshots());
            row.put("hitAge",LauncherClient.hitAge());row.put("headHit",LauncherClient.headHit());row.put("hitDamage",diagnostics.hitDamage());
            row.put("damageTotal",LauncherClient.damageDisplay().total());
            double damageAge=LauncherClient.damageDisplay().age(System.nanoTime()/1_000_000_000.0);row.put("damageAge",Double.isFinite(damageAge)?damageAge:-1);
            row.put("damageMode",LauncherClient.DAMAGE_NUMBERS.get().name());
            var damage=LauncherClient.damageDisplay().latest();
            if(damage!=null){row.put("damageTarget",damage.target());row.put("healthDamage",damage.health());row.put("shieldDamage",damage.shield());row.put("shieldBroken",damage.shieldBroken());}
            var pose=LauncherGunPose.sample(LauncherClient.frame(mc.player.getMainHandItem(),mc.level,1),1,LauncherClient.aim(1),LauncherClient.kick(1),0);
            row.put("weaponPose",java.util.Map.of("x",pose.x(),"y",pose.y(),"z",pose.z(),"pitch",pose.pitch(),"yaw",pose.yaw(),"scale",pose.scale()));
            var targets=new ArrayList<Map<String,Object>>();
            for(var target:mc.level.entitiesForRendering())if(target instanceof net.minecraft.world.entity.LivingEntity living && target.hasCustomName() && target.getCustomName().getString().startsWith("CMS BALLISTICS"))
                targets.add(Map.of("id",target.getId(),"health",living.getHealth(),"eyeY",living.getEyeY()));
            row.put("gunTargets",targets);
            var dragons=new ArrayList<Map<String,Object>>();
            for(var e:mc.level.entitiesForRendering())if(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon && e.hasCustomName() && e.getCustomName().getString().equals("CMS DRAGON CHECK")){
                dragons.add(Map.of("id",e.getId(),"uuid",e.getUUID().toString(),"x",e.getX(),"y",e.getY(),"z",e.getZ(),"health",dragon.getHealth(),
                    "head",List.of(dragon.head.getX(),dragon.head.getY(),dragon.head.getZ())));
            }
            row.put("dragons",dragons);
            var cell = LauncherMagazine.cell(mc.player.getMainHandItem());
            row.put("cellLevel", cell.getItem() instanceof org.haruyq.createmediasystems.launcher.DiscCellItem item ? item.tier.level : 0);
            row.put("cellEnergy", ChargeableItem.energy(cell));
            row.put("master", org.haruyq.createmediasystems.launcher.DiscCellItem.master(cell).toString());
            row.put("inventory", mc.player.getInventory().items.toString());
            row.put("flying",mc.player.getAbilities().flying);
            if(mc.player.getMainHandItem().getItem() instanceof org.haruyq.createmediasystems.megaphone.MegaphoneItem horn){
                var item=mc.player.getMainHandItem();var data=org.haruyq.createmediasystems.megaphone.MegaphoneData.state(item);
                row.put("megaphone",Map.of("id",org.haruyq.createmediasystems.megaphone.MegaphoneData.id(item).map(UUID::toString).orElse(""),
                    "playing",org.haruyq.createmediasystems.megaphone.MegaphoneData.playing(item),"black",horn.black,
                    "seconds",org.haruyq.createmediasystems.megaphone.MegaphoneData.seconds(item,mc.level),"volume",org.haruyq.createmediasystems.megaphone.MegaphoneData.volume(item),
                    "drive",org.haruyq.createmediasystems.megaphone.MegaphoneData.drive(item),"revision",org.haruyq.createmediasystems.megaphone.MegaphoneData.revision(item),
                    "energy",ChargeableItem.energy(org.haruyq.createmediasystems.megaphone.MegaphoneData.battery(item,mc.level))));
            }
            var playerRenderer=(net.minecraft.client.renderer.entity.player.PlayerRenderer)mc.getEntityRenderDispatcher().getRenderer(mc.player);
            row.put("arm",List.of(playerRenderer.getModel().rightArm.xRot,playerRenderer.getModel().rightArm.yRot,playerRenderer.getModel().rightArm.zRot));
            List<Map<String,Object>> partyBoxes=new ArrayList<>();
            for(var e:mc.level.entitiesForRendering())if(e instanceof org.haruyq.createmediasystems.partybox.PartyBoxEntity box){
                Map<String,Object> b=new LinkedHashMap<>();b.put("id",box.getId());b.put("uuid",box.getUUID().toString());
                b.put("x",box.getX());b.put("y",box.getY());b.put("z",box.getZ());b.put("playing",box.playing());
                b.put("lean",box.lean);b.put("yaw",box.getYRot());
                b.put("pitchTarget",org.haruyq.createmediasystems.partybox.client.PartyBoxClient.pitchAt(box));
                b.put("vx",box.getX()-box.xo);b.put("vy",box.getY()-box.yo);b.put("vz",box.getZ()-box.zo);
                b.put("seconds",box.seconds());b.put("energy",box.energy());b.put("volume",box.volume());b.put("bass",box.bass());b.put("lights",box.lights());
                b.put("audibleSeconds",org.haruyq.createmediasystems.partybox.client.PartyBoxClient.audibleSeconds(box.getUUID()));
                b.put("apiRequestStart",org.haruyq.createmediasystems.playback.ClientStreams.requestedSeconds(box.getUUID()));
                b.put("puller",box.puller().map(UUID::toString).orElse(""));b.put("beat",org.haruyq.createmediasystems.partybox.client.PartyBoxClient.beat(box));
                b.put("native",decoded(box.getUUID()));partyBoxes.add(b);
            }
            row.put("partyBoxes",partyBoxes);
            List<Map<String,Object>> discs=new ArrayList<>();
            for (var e:mc.level.entitiesForRendering()) if(e instanceof DiscProjectile d) {
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("id",d.getId());m.put("uuid",d.getUUID().toString());m.put("x",d.getX());m.put("y",d.getY());m.put("z",d.getZ());
                m.put("vx",d.getDeltaMovement().x);m.put("vy",d.getDeltaMovement().y);m.put("vz",d.getDeltaMovement().z);
                m.put("embedded",d.embedded());m.put("elapsed",d.elapsedMusicSeconds());
                m.put("audibleSeconds",DiscAudioClient.audibleSeconds(d.getUUID()));
                m.put("apiRequestStart",org.haruyq.createmediasystems.playback.ClientStreams.requestedSeconds(d.getUUID()));
                m.put("attached",d.attached());m.put("attachedHead",d.attachedToHead());m.put("attachedId",d.attachmentTargetId());
                m.put("attachedPart",d.attachmentPart());
                m.put("attachedBone",d.attachmentBone());
                m.put("attachedUUID",d.attachmentTargetUUID().map(UUID::toString).orElse(""));
                var anchor=d.attachmentPosition(1);m.put("anchor",List.of(anchor.x,anchor.y,anchor.z));
                m.put("playback",d.getItem().get(LauncherFeature.DISC_PLAYBACK.get()));
                m.put("audio",decoded(d.getUUID()));
                m.put("pitchTarget",DiscAudioClient.pitchAt(d));m.put("gainTarget",DiscAudioClient.gain(d));
                m.put("item",d.getItem().toString());discs.add(m);
                m.put("url", org.haruyq.createmediasystems.stream.MediaItem.url(d.getItem()));
                m.put("managed", org.haruyq.createmediasystems.playback.ClientStreams.contains(d.getUUID()));
            }
            row.put("discs",discs);
            var engine=((SoundManagerAccessor)mc.getSoundManager()).create_media_systems$soundEngine();
            var access=(SoundEngineAccessor)engine;
            List<Map<String,Object>> sounds=new ArrayList<>();
            for(var entry:new ArrayList<>(access.create_media_systems$instanceToChannel().entrySet())) {
                SoundInstance s=entry.getKey();
                if (!(s instanceof FlyingJukeboxSound) && !s.getClass().getSimpleName().equals("PartyBoxSound") && !s.getClass().getSimpleName().equals("MegaphoneSound") && !s.getLocation().getNamespace().equals("create_media_systems")) continue;
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("name",s.getLocation().toString());m.put("class",s.getClass().getSimpleName());
                if(s instanceof FlyingJukeboxSound song)m.put("initialOffset",song.initialOffsetSeconds());
                m.put("pitch",s.getPitch());m.put("volume",s.getVolume());m.put("x",s.getX());m.put("y",s.getY());m.put("z",s.getZ());
                sounds.add(m);
                entry.getValue().execute(channel->{
                    int al=((ChannelAccessor)(Object)channel).create_media_systems$source();
                    m.put("alState",AL10.alGetSourcei(al,AL10.AL_SOURCE_STATE));
                    m.put("alPitch",AL10.alGetSourcef(al,AL10.AL_PITCH));
                    m.put("alGain",AL10.alGetSourcef(al,AL10.AL_GAIN));
                    m.put("queued",AL10.alGetSourcei(al,AL10.AL_BUFFERS_QUEUED));
                    int buffer=AL10.alGetSourcei(al,AL10.AL_BUFFER);
                    if(buffer!=0)m.put("channels",AL10.alGetBufferi(buffer,AL10.AL_CHANNELS));
                });
            }
            row.put("sounds",sounds);
            access.create_media_systems$executor().execute(()-> {
                try { Files.writeString(dir.resolve("probe.jsonl"),JSON.toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
                catch(Exception e){LogUtils.getLogger().warn("Launcher probe write failed",e);}
            });
        } catch(Exception e){LogUtils.getLogger().warn("Launcher debug failed",e);}
    }

    /** Where the native player of the moving disc {@code id} actually is; null if none plays it. */
    private static org.haruyq.createmediasystems.playback.NativeAudio.Progress decoded(java.util.UUID id) {
        var playback=org.haruyq.createmediasystems.playback.NativeAudio.playback(id);
        return playback==null?null:playback.decoded();
    }
}
