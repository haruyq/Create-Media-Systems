package org.haruyq.createmediasystems.partybox;

import java.util.Optional;
import java.util.UUID;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.launcher.DiscPlayback;
import org.haruyq.createmediasystems.launcher.LauncherMagazine;
import org.haruyq.createmediasystems.stream.Streams;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A real collision-aware trolley, with server-owned inventory, battery, puller and playback clock. */
public final class PartyBoxEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> DISC = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> BATTERY = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> ENERGY = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VOLUME = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BASS = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LIGHTS = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> REVISION = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> PLAYING = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> START = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> OFFSET = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Optional<UUID>> PULLER = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    public final SimpleContainer inventory = new SimpleContainer(2) {
        @Override public void setItem(int slot,ItemStack item) { settlePower();super.setItem(slot,item); }
        @Override public ItemStack removeItem(int slot,int count) { settlePower();return super.removeItem(slot,count); }
        @Override public ItemStack removeItemNoUpdate(int slot) { settlePower();return super.removeItemNoUpdate(slot); }
    };
    private boolean changing;
    private long powerAt = Long.MIN_VALUE;
    private double fraction, duration = -1;
    private int lerpSteps;
    private double targetX, targetY, targetZ;
    private float targetYaw;
    private Vec3 previousPullerPosition;
    public float wheelTravel, previousWheelTravel, lean, previousLean;

    public PartyBoxEntity(EntityType<? extends PartyBoxEntity> type, Level level) {
        super(type, level);
        inventory.addListener(container -> syncInventory());
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DISC, ItemStack.EMPTY); b.define(BATTERY, ItemStack.EMPTY); b.define(ENERGY, 0);
        b.define(VOLUME, 18); b.define(BASS, 0); b.define(LIGHTS, 1); b.define(REVISION, 0);
        b.define(PLAYING, false); b.define(START, 0L); b.define(OFFSET, 0L); b.define(PULLER, Optional.empty());
    }
    public ItemStack disc() { return entityData.get(DISC); }
    public int energy() { return entityData.get(ENERGY); }
    public int volume() { return entityData.get(VOLUME); }
    public int bass() { return entityData.get(BASS); }
    public int lights() { return entityData.get(LIGHTS); }
    public int revision() { return entityData.get(REVISION); }
    public boolean playing() { return entityData.get(PLAYING); }
    public Optional<UUID> puller() { return entityData.get(PULLER); }
    public long positionTicks() { return entityData.get(OFFSET) + (playing() ? Math.max(0, level().getGameTime() - entityData.get(START)) : 0); }
    public double seconds() { return positionTicks() / 20.0; }
    public Vec3 soundPosition() { return position().add(0, .34, 0); }
    public void setVolume(int value) { entityData.set(VOLUME, Math.clamp(value, 0, 32)); }
    public void setBass(int value) { entityData.set(BASS, Math.clamp(value, 0, 2)); }
    public void setLights(int value) { entityData.set(LIGHTS, Math.floorMod(value, 4)); }

    private void syncInventory() {
        if (changing || level().isClientSide) return;
        ItemStack next = inventory.getItem(0);
        if (!ItemStack.matches(next, disc())) {
            pause(); duration = -1;
            entityData.set(DISC, next.copy()); entityData.set(OFFSET, 0L);
            entityData.set(REVISION, revision() + 1);
        }
        entityData.set(BATTERY, inventory.getItem(1).copy());
        entityData.set(ENERGY, PartyBoxBatteryItem.energy(inventory.getItem(1)));
        powerAt = level().getGameTime();
        if (!next.isEmpty() && energy() > 0 && !playing()) play();
    }
    public boolean play() {
        if (disc().isEmpty() || energy() <= 0) return false;
        String url = DiscPlayback.mediaUrl(disc());
        if ((disc().is(AudioFeature.CUSTOM_DISC.get()) || disc().is(VideoFeature.DVD.get()) || disc().is(VideoFeature.BLU_RAY.get()))
                && (url==null || url.isBlank())) return false;
        if (url != null && !ServerConfig.isAllowed(url)) return false;
        if (playing()) return true;
        entityData.set(START, level().getGameTime()); entityData.set(PLAYING, true);
        entityData.set(REVISION, revision() + 1); powerAt = level().getGameTime();
        return true;
    }
    public void pause() {
        if (!playing()) return;
        tickPower();if(!playing())return;
        entityData.set(OFFSET, positionTicks()); entityData.set(PLAYING, false);
        entityData.set(REVISION, revision() + 1);
    }
    public void noteDuration(double seconds) {
        if (duration <= 0 && Double.isFinite(seconds) && seconds > 0 && seconds <= 86400) duration = seconds;
    }
    public void togglePull(Player player) {
        if (puller().filter(player.getUUID()::equals).isPresent()) { entityData.set(PULLER, Optional.empty()); previousPullerPosition=null; return; }
        if (puller().isPresent() || player.isSpectator() || player.distanceToSqr(this) > 9) return;
        for (PartyBoxEntity other : level().getEntitiesOfClass(PartyBoxEntity.class, player.getBoundingBox().inflate(8)))
            if (other.puller().filter(player.getUUID()::equals).isPresent()) other.entityData.set(PULLER, Optional.empty());
        entityData.set(PULLER, Optional.of(player.getUUID()));
        previousPullerPosition=player.position();
    }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        ItemStack held = player.getItemInHand(hand);
        int slot = held.getItem() instanceof PartyBoxBatteryItem ? 1 : LauncherMagazine.isDisc(held) ? 0 : -1;
        if (slot >= 0) {
            String url = slot == 0 ? DiscPlayback.mediaUrl(held) : null;
            if (url != null && !ServerConfig.isAllowed(url)) {
                player.displayClientMessage(Component.translatable("create_media_systems.url.rejected"), true); return InteractionResult.CONSUME;
            }
            ItemStack previous = inventory.removeItemNoUpdate(slot);
            inventory.setItem(slot, held.copyWithCount(1));
            held.shrink(1);
            if (!previous.isEmpty()) player.getInventory().placeItemBackInInventory(previous);
        } else if (player.isShiftKeyDown() && player instanceof ServerPlayer server) {
            server.openMenu(new SimpleMenuProvider((id, inv, owner) -> new PartyBoxMenu(id, inv, this),
                    Component.translatable("item.create_media_systems.partybox_stage_320")), buf -> buf.writeVarInt(getId()));
        } else togglePull(player);
        return InteractionResult.CONSUME;
    }
    public boolean pack(Player player) {
        if (isRemoved() || player.distanceToSqr(this) > 16 || puller().isPresent()) return false;
        ItemStack item = portableItem();
        if (!player.getInventory().add(item)) return false;
        discard(); return true;
    }
    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return !isRemoved(); }
    @Override public float maxUpStep() { return .6f; }
    @Override public ItemStack getPickResult() { return new ItemStack(PartyBoxFeature.PARTYBOX.get()); }
    @Override public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && !isRemoved() && amount > 0) {
            // One item owns both inventory slots. Creative destruction also cannot spill duplicate originals.
            spawnAtLocation(portableItem()); discard();
        }
        return true;
    }
    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        targetX=x; targetY=y; targetZ=z; targetYaw=yaw; lerpSteps=Math.clamp(steps,1,2);
    }
    @Override public void tick() {
        super.tick(); previousWheelTravel=wheelTravel; previousLean=lean;
        if (level().isClientSide) {
            double ox=getX(), oz=getZ();
            if (lerpSteps > 0) lerpPositionAndRotationStep(lerpSteps--, targetX, targetY, targetZ, targetYaw, 0);
            wheelTravel += (float) Math.hypot(getX()-ox, getZ()-oz);
            lean += ((puller().isPresent() ? 19 : 0) - lean) * .25f;
            return;
        }
        tickPower();
        Player player = puller().map(id -> level().getPlayerByUUID(id)).orElse(null);
        Vec3 horizontal = getDeltaMovement().multiply(.7, 0, .7);
        if (player != null && player.isAlive() && !player.isSpectator() && player.distanceToSqr(this) < 25 && Math.abs(player.getY()-getY()) < 1.8) {
            Vec3 facing = Vec3.directionFromRotation(0, player.getYRot());
            float side=player.getMainArm()==net.minecraft.world.entity.HumanoidArm.RIGHT?1:-1;
            Vec3 right=Vec3.directionFromRotation(0,player.getYRot()+90);
            Vec3 target = player.position().subtract(facing.scale(.85)).add(right.scale(side*.26));
            Vec3 delta = target.subtract(position()).multiply(1, 0, 1);
            double distance = delta.length();
            Vec3 walking=previousPullerPosition==null?Vec3.ZERO:player.position().subtract(previousPullerPosition).multiply(1,0,1);
            previousPullerPosition=player.position();
            if(walking.horizontalDistanceSqr()>.45*.45)walking=Vec3.ZERO;
            // Feed forward the puller's real step. A spring alone trails by v/k and leaves the handle behind the hand.
            Vec3 correction=distance<.04?Vec3.ZERO:delta.scale(PartyBoxSpecs.desiredSpeed(distance)/distance);
            horizontal=walking.add(correction);
            if(horizontal.horizontalDistance()>.4)horizontal=horizontal.scale(.4/horizontal.horizontalDistance());
            Vec3 pull=player.position().subtract(position());
            if(pull.horizontalDistanceSqr()>.01)setYRot((float)Math.toDegrees(Math.atan2(-pull.x,pull.z)));
        } else {
            if(puller().isPresent()){entityData.set(PULLER,Optional.empty());horizontal=Vec3.ZERO;}
            previousPullerPosition=null;
        }
        Vec3 movement = horizontal.add(0, Math.max(-.8, getDeltaMovement().y - .08), 0);
        setDeltaMovement(movement); move(MoverType.SELF, movement);
        if (verticalCollision) setDeltaMovement(horizontal.x, 0, horizontal.z);
        if (horizontalCollision) setDeltaMovement(0, getDeltaMovement().y, 0);
        // A blocked trolley releases instead of teleporting through a wall or stretching the player's arm.
        if (player != null && player.distanceToSqr(this) > 6.25) entityData.set(PULLER, Optional.empty());
    }
    private void tickPower() {
        long now = level().getGameTime();
        if (powerAt == Long.MIN_VALUE) powerAt = now;
        long elapsed = Math.max(0, now - powerAt); powerAt = now;
        if (!playing()) return;
        long currentPosition=positionTicks(), previousPosition=Math.max(0,currentPosition-elapsed);
        boolean ended=duration>0 && currentPosition>=Math.round(duration*20);
        long chargedTicks=ended?Math.min(elapsed,Math.max(0,Math.round(duration*20)-previousPosition)):elapsed;
        double rate = PartyBoxSpecs.drainPerTick(volume(), bass(), lights());
        double spent = fraction + chargedTicks * rate;
        if (spent >= energy()) {
            long allowed = Math.max(0, (long) Math.floor((energy() - fraction) / rate));
            entityData.set(OFFSET, previousPosition + allowed);
            entityData.set(PLAYING, false); entityData.set(REVISION, revision()+1);
            entityData.set(ENERGY, 0); fraction = 0;
        } else {
            int whole=(int) spent; fraction=spent-whole;
            if (whole > 0) entityData.set(ENERGY, energy()-whole);
        }
        PartyBoxBatteryItem.setEnergy(inventory.getItem(1), energy());
        if (duration <= 0 && tickCount % 20 == 0) {
            String url = DiscPlayback.mediaUrl(disc());
            noteDuration(url == null ? JukeboxSong.fromStack(level().registryAccess(), disc())
                    .map(s -> (double) s.value().lengthInSeconds()).orElse(-1.0) : Streams.duration(url));
        }
        if (playing() && duration > 0 && seconds() >= duration) {
            entityData.set(PLAYING,false);entityData.set(OFFSET,0L);entityData.set(REVISION,revision()+1);
        }
    }
    public void settlePower(){if(!changing && !level().isClientSide && playing())tickPower();}
    public ItemStack portableItem() {
        ItemStack item = new ItemStack(PartyBoxFeature.PARTYBOX.get());
        CompoundTag tag = new CompoundTag(); writeContents(tag);
        tag.putLong("Offset", positionTicks()); tag.putBoolean("Playing", false);
        item.set(PartyBoxFeature.PARTYBOX_STATE.get(), tag);
        return item;
    }
    public void loadPortable(CompoundTag tag) { if (tag != null) readContents(tag, false); }
    private void writeContents(CompoundTag tag) {
        settlePower();
        tag.put("Disc", inventory.getItem(0).saveOptional(registryAccess()));
        tag.put("Battery", inventory.getItem(1).saveOptional(registryAccess()));
        tag.putInt("Energy", energy()); tag.putInt("Volume", volume()); tag.putInt("Bass", bass()); tag.putInt("Lights", lights());
        tag.putLong("Start", entityData.get(START)); tag.putLong("Offset", entityData.get(OFFSET)); tag.putBoolean("Playing", playing());
        tag.putLong("PowerAt", powerAt); tag.putDouble("PowerFraction", fraction); tag.putDouble("Duration", duration);
    }
    private void readContents(CompoundTag tag, boolean world) {
        changing=true;
        inventory.setItem(0, ItemStack.parseOptional(registryAccess(), tag.getCompound("Disc")));
        inventory.setItem(1, ItemStack.parseOptional(registryAccess(), tag.getCompound("Battery")));
        changing=false;
        entityData.set(DISC, inventory.getItem(0).copy()); entityData.set(BATTERY, inventory.getItem(1).copy());
        entityData.set(ENERGY, Math.min(PartyBoxBatteryItem.energy(inventory.getItem(1)), Math.max(0, tag.getInt("Energy"))));
        setVolume(tag.contains("Volume") ? tag.getInt("Volume") : 18); setBass(tag.getInt("Bass")); setLights(tag.getInt("Lights"));
        entityData.set(START, world ? tag.getLong("Start") : level().getGameTime());
        entityData.set(OFFSET, Math.max(0, tag.getLong("Offset")));
        entityData.set(PLAYING, world && tag.getBoolean("Playing") && !disc().isEmpty() && energy()>0);
        powerAt=world ? tag.getLong("PowerAt") : level().getGameTime();
        fraction=Math.clamp(tag.getDouble("PowerFraction"), 0, .999999); duration=tag.getDouble("Duration");
        entityData.set(REVISION, revision()+1); entityData.set(PULLER, Optional.empty());
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) { writeContents(tag); }
    @Override protected void readAdditionalSaveData(CompoundTag tag) { readContents(tag, true); }
}
