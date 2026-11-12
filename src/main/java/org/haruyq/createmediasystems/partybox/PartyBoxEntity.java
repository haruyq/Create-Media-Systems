package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.power.ChargeableItem;
import java.util.Optional;
import java.util.UUID;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Portable;
import org.haruyq.createmediasystems.stream.PortablePlaylist;
import org.haruyq.createmediasystems.stream.Remotable;
import org.haruyq.createmediasystems.video.RemoteItem;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.jetbrains.annotations.Nullable;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A real collision-aware trolley, with server-owned inventory, battery, puller and playback clock. */
public final class PartyBoxEntity extends Entity implements Portable {
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
    private static final EntityDataAccessor<Integer> TRACK = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> LENGTH = SynchedEntityData.defineId(PartyBoxEntity.class, EntityDataSerializers.FLOAT);
    public final SimpleContainer inventory = new SimpleContainer(2) {
        @Override public void setItem(int slot,ItemStack item) { settlePower();super.setItem(slot,item); }
        @Override public ItemStack removeItem(int slot,int count) { settlePower();return super.removeItem(slot,count); }
        @Override public ItemStack removeItemNoUpdate(int slot) { settlePower();return super.removeItemNoUpdate(slot); }
    };
    private boolean changing;
    /** What its battery has paid for of its clock; server only. */
    private PlaybackClock.Drain drain = new PlaybackClock.Drain(Long.MIN_VALUE, 0);
    private double length = -1;
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
        b.define(TRACK, 0); b.define(LENGTH, -1f);
    }
    public ItemStack disc() { return entityData.get(DISC); }
    public int energy() { return entityData.get(ENERGY); }
    public int volume() { return entityData.get(VOLUME); }
    public int bass() { return entityData.get(BASS); }
    public int lights() { return entityData.get(LIGHTS); }
    @Override public int revision() { return entityData.get(REVISION); }
    public boolean playing() { return entityData.get(PLAYING); }
    public int track() { return entityData.get(TRACK); }
    public int trackCount() { return PortablePlaylist.size(disc()); }
    public double duration() { return level().isClientSide ? entityData.get(LENGTH) : length; }
    @Nullable
    public String currentUrl() { return PortablePlaylist.url(disc(), track()); }
    public Optional<UUID> puller() { return entityData.get(PULLER); }
    private PlaybackClock clock() {
        return PlaybackClock.of(entityData.get(OFFSET), entityData.get(START), playing(), revision(), duration());
    }
    private void keep(PlaybackClock clock) {
        entityData.set(OFFSET, clock.offset()); entityData.set(START, clock.start()); entityData.set(PLAYING, clock.running());
        entityData.set(REVISION, clock.revision()); length = clock.length();
        entityData.set(LENGTH, (float) length);
    }
    public long positionTicks() { return clock().position(level().getGameTime()); }
    public double seconds() { return clock().seconds(level().getGameTime()); }
    public Vec3 soundPosition() { return position().add(0, .34, 0); }
    public void setVolume(int value) { entityData.set(VOLUME, Math.clamp(value, 0, 32)); }
    public void setBass(int value) { entityData.set(BASS, Math.clamp(value, 0, 2)); }
    public void setLights(int value) { entityData.set(LIGHTS, Math.floorMod(value, 4)); }

    private void syncInventory() {
        if (changing || level().isClientSide) return;
        ItemStack next = inventory.getItem(0);
        boolean newDisc = !ItemStack.matches(next, disc());
        boolean withoutBattery = energy() == 0;
        if (newDisc) {
            PlaybackClock clock = clock(); clock.stop(); clock.forget(); keep(clock);
            entityData.set(TRACK, 0);
            entityData.set(DISC, next.copy());
        }
        entityData.set(BATTERY, inventory.getItem(1).copy());
        entityData.set(ENERGY, ChargeableItem.energy(inventory.getItem(1)));
        if (energy() == 0 && playing()) pause();
        if ((newDisc || withoutBattery) && energy() > 0 && !playing()) play();
    }
    public boolean play() {
        if (playing()) return true;
        control(Remotable.Action.PLAY_PAUSE, 0);
        return playing();
    }
    public void pause() {
        if (!playing()) return;
        tickPower();
        if (playing()) {
            PlaybackClock clock = clock();
            clock.pause(level().getGameTime());
            keep(clock);
        }
    }
    public void control(Remotable.Action action, int value) {
        boolean wasPlaying = playing();
        tickPower();
        if (action == Remotable.Action.VOLUME) {
            setVolume(Math.round(Math.clamp(value, 0, 100) * .32f));
            return;
        }
        if (action == Remotable.Action.PLAY_PAUSE && wasPlaying && !playing()) return;
        PlaybackClock clock = clock();
        int track = PortablePlaylist.control(disc(), clock, track(), level().getGameTime(), energy() > 0, action, value);
        entityData.set(TRACK, track);
        keep(clock);
    }
    @Override public boolean takesLengthFrom(ServerPlayer player) { return player.distanceToSqr(this) < 128 * 128; }
    @Override public void learnLength(double seconds) {
        PlaybackClock clock = clock(); clock.learn(seconds); keep(clock);
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
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && held.is(VideoFeature.REMOTE.get())) {
            if (!level().isClientSide && !player.isSpectator()) RemoteItem.pair(this, player, held);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown()
                && player.getOffhandItem().is(VideoFeature.REMOTE.get()) && held.isEmpty()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        int slot = BatteryItem.fits(held) ? 1 : PortablePlaylist.fits(held) ? 0 : -1;
        if (slot >= 0) {
            if (slot == 0 && PortablePlaylist.size(held) > 0 && PortablePlaylist.find(held, 0, 1) < 0) {
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
        PlaybackClock clock = clock();
        long now = level().getGameTime();
        if (!clock.running()) {
            drain.settle(clock, now, energy(), 0);
            if (clock.length() <= 0 && tickCount % 20 == 0) {
                clock.learn(PortablePlaylist.duration(disc(), track(), level().registryAccess()));
                keep(clock);
            }
            return;
        }
        var paid = PortablePlaylist.settle(disc(), level().registryAccess(), clock, drain, now, track(), energy(),
                PartyBoxSpecs.drainPerTick(volume(), bass(), lights()));
        entityData.set(ENERGY, paid.energy());
        ChargeableItem.setEnergy(inventory.getItem(1), paid.energy());
        entityData.set(TRACK, paid.track());
        keep(clock);
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
        clock().save(tag); tag.putInt("Track", track()); drain.save(tag, "PowerFraction");
    }
    private void readContents(CompoundTag tag, boolean world) {
        changing=true;
        inventory.setItem(0, ItemStack.parseOptional(registryAccess(), tag.getCompound("Disc")));
        inventory.setItem(1, ItemStack.parseOptional(registryAccess(), tag.getCompound("Battery")));
        changing=false;
        entityData.set(DISC, inventory.getItem(0).copy()); entityData.set(BATTERY, inventory.getItem(1).copy());
        entityData.set(TRACK, PortablePlaylist.index(disc(), tag.getInt("Track")));
        entityData.set(ENERGY, Math.min(ChargeableItem.energy(inventory.getItem(1)), Math.max(0, tag.getInt("Energy"))));
        setVolume(tag.contains("Volume") ? tag.getInt("Volume") : 18); setBass(tag.getInt("Bass")); setLights(tag.getInt("Lights"));
        // Packed into an item it stood still; in the world its clock ran on while unloaded, and its battery pays for that.
        long now = level().getGameTime();
        PlaybackClock saved = PlaybackClock.read(tag);
        keep(PlaybackClock.of(saved.offset(), world ? saved.start() : now,
                world && saved.running() && !disc().isEmpty() && energy() > 0, revision() + 1, saved.length()));
        drain = world ? PlaybackClock.Drain.read(tag, "PowerFraction", now) : new PlaybackClock.Drain(now, tag.getDouble("PowerFraction"));
        entityData.set(PULLER, Optional.empty());
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) { writeContents(tag); }
    @Override protected void readAdditionalSaveData(CompoundTag tag) { readContents(tag, true); }
}
