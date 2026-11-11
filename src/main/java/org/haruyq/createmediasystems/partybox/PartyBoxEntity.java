package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.power.ChargeableItem;
import java.util.Optional;
import java.util.UUID;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.PlaybackClock;
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
    /** What its battery has paid for of its clock; server only. */
    private PlaybackClock.Drain drain = new PlaybackClock.Drain(Long.MIN_VALUE, 0);
    /** Its track's length in seconds once learned; server only (see clock). */
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
    }
    public ItemStack disc() { return entityData.get(DISC); }
    public int energy() { return entityData.get(ENERGY); }
    public int volume() { return entityData.get(VOLUME); }
    public int bass() { return entityData.get(BASS); }
    public int lights() { return entityData.get(LIGHTS); }
    public int revision() { return entityData.get(REVISION); }
    public boolean playing() { return entityData.get(PLAYING); }
    public Optional<UUID> puller() { return entityData.get(PULLER); }
    /** Its playback clock, as its synced data keeps it for every side; the length it learned is the server's alone. */
    private PlaybackClock clock() {
        return PlaybackClock.of(entityData.get(OFFSET), entityData.get(START), playing(), revision(), length);
    }
    private void keep(PlaybackClock clock) {
        entityData.set(OFFSET, clock.offset()); entityData.set(START, clock.start()); entityData.set(PLAYING, clock.running());
        entityData.set(REVISION, clock.revision()); length = clock.length();
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
        if (!ItemStack.matches(next, disc())) {
            pause();
            PlaybackClock clock = clock(); clock.stop(); clock.forget(); keep(clock);
            entityData.set(DISC, next.copy());
        }
        entityData.set(BATTERY, inventory.getItem(1).copy());
        entityData.set(ENERGY, ChargeableItem.energy(inventory.getItem(1)));
        if (!next.isEmpty() && energy() > 0 && !playing()) play();
    }
    public boolean play() {
        if (disc().isEmpty() || energy() <= 0) return false;
        if (MediaItem.silent(disc()) || !MediaItem.allowed(disc())) return false;
        if (playing()) return true;
        tickPower();
        PlaybackClock clock = clock(); clock.play(level().getGameTime()); keep(clock);
        return true;
    }
    public void pause() {
        if (!playing()) return;
        tickPower();if(!playing())return;
        PlaybackClock clock = clock(); clock.pause(level().getGameTime()); keep(clock);
    }
    /** A listener's client tells its track's length, for a track the server can't tell the length of. */
    public void noteDuration(double seconds) {
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
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        ItemStack held = player.getItemInHand(hand);
        int slot = held.getItem() instanceof BatteryItem ? 1 : MediaItem.fits(held, DiscKind.Slot.PORTABLE) ? 0 : -1;
        if (slot >= 0) {
            if (slot == 0 && !MediaItem.allowed(held)) {
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
    /** Pays its battery for the music played since it last settled, and stops at the end of its track. */
    private void tickPower() {
        PlaybackClock clock = clock();
        if (!clock.running()) { drain.settle(clock, level().getGameTime(), energy(), 0); return; }
        long now = level().getGameTime();
        int energy = drain.settle(clock, now, energy(), PartyBoxSpecs.drainPerTick(volume(), bass(), lights()));
        entityData.set(ENERGY, energy);
        ChargeableItem.setEnergy(inventory.getItem(1), energy);
        if (tickCount % 20 == 0) clock.learn(MediaItem.lengthSeconds(disc(), level().registryAccess()));
        if (clock.running() && clock.ended(now)) clock.stop();
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
        clock().save(tag); drain.save(tag, "PowerFraction");
    }
    private void readContents(CompoundTag tag, boolean world) {
        changing=true;
        inventory.setItem(0, ItemStack.parseOptional(registryAccess(), tag.getCompound("Disc")));
        inventory.setItem(1, ItemStack.parseOptional(registryAccess(), tag.getCompound("Battery")));
        changing=false;
        entityData.set(DISC, inventory.getItem(0).copy()); entityData.set(BATTERY, inventory.getItem(1).copy());
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
