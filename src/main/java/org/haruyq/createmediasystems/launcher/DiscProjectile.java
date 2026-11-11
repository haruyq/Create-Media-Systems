package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Portable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.portal.DimensionTransition;
import java.util.Optional;
import java.util.UUID;
import org.joml.Vector3f;

/**
 * The DISC is the emitter, not the launcher or shooter. A hit embeds the disc and
 * leaves that SAME entity/audio source in the world until expiry. Projections
 * cannot be picked up; the original, including its media components, stays in the cell.
 */
public final class DiscProjectile extends ThrowableItemProjectile implements Portable {
    private static final int UNKNOWN_LIFETIME_TICKS = 20 * 180;
    private static final int MAX_FLIGHT_TICKS = 20 * 5;
    private static final EntityDataAccessor<Boolean> EMBEDDED =
            SynchedEntityData.defineId(DiscProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> MUSIC_START_TICK =
            SynchedEntityData.defineId(DiscProjectile.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> CELL_TIER =
            SynchedEntityData.defineId(DiscProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> ATTACHED_TO =
            SynchedEntityData.defineId(DiscProjectile.class,EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> ATTACHED_ID =
            SynchedEntityData.defineId(DiscProjectile.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> ATTACH_POINT =
            SynchedEntityData.defineId(DiscProjectile.class,EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> ATTACH_FORWARD =
            SynchedEntityData.defineId(DiscProjectile.class,EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Boolean> ATTACH_HEAD =
            SynchedEntityData.defineId(DiscProjectile.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> ATTACH_PART =
            SynchedEntityData.defineId(DiscProjectile.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTACH_BONE =
            SynchedEntityData.defineId(DiscProjectile.class,EntityDataSerializers.INT);
    private long spawnedAtTick = Long.MIN_VALUE;
    /** Its track's length in seconds once learned; server only (see clock). */
    private double length = -1;
    private long nextDurationCheckTick;
    private LivingEntity attachedTarget;
    private int attachmentDeaths=-1;
    private BlockPos blockAnchor;

    public DiscProjectile(EntityType<? extends DiscProjectile> type, Level level) {
        super(type, level);
    }

    public DiscProjectile(Level level, LivingEntity owner, ItemStack disc) {
        super(LauncherFeature.DISC_PROJECTILE.get(), owner, level);
        ItemStack original = disc.copyWithCount(1);
        DiscPlayback playback = DiscPlayback.prepare(level, original);
        original.set(LauncherFeature.DISC_PLAYBACK.get(), playback);
        setItem(original);
        setNoGravity(true);
        spawnedAtTick = level.getGameTime();
        entityData.set(MUSIC_START_TICK, spawnedAtTick - playback.positionTicks());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(EMBEDDED, false);
        builder.define(MUSIC_START_TICK, Long.MIN_VALUE);
        builder.define(CELL_TIER, 1);
        builder.define(ATTACHED_TO,Optional.empty());builder.define(ATTACHED_ID,-1);
        builder.define(ATTACH_POINT,new Vector3f());builder.define(ATTACH_FORWARD,new Vector3f(0,0,1));
        builder.define(ATTACH_HEAD,false);
        builder.define(ATTACH_PART,-1);
        builder.define(ATTACH_BONE,-1);
    }

    /**
     * Its playback clock: it plays from the start time its synced data keeps (unset: not yet), so a joining listener
     * seeks into the ongoing track; the length it learned is the server's alone.
     */
    private PlaybackClock clock() {
        return PlaybackClock.of(0, entityData.get(MUSIC_START_TICK), true, 0, length);
    }

    public double elapsedMusicSeconds() {
        return clock().seconds(level().getGameTime());
    }

    public long playbackPositionTicks() {
        return clock().position(level().getGameTime());
    }

    /** Snapshot this CD's server clock when it leaves the world, without affecting any other CD. */
    public ItemStack playbackItem() {
        ItemStack item = getItem().copy();
        item.set(LauncherFeature.DISC_PLAYBACK.get(), DiscPlayback.prepare(level(), item).at(playbackPositionTicks()));
        return item;
    }

    public boolean embedded() {
        return entityData.get(EMBEDDED);
    }

    public boolean attached(){return entityData.get(ATTACHED_TO).isPresent();}
    public boolean attachedToHead(){return entityData.get(ATTACH_HEAD);}
    public Optional<UUID> attachmentTargetUUID(){return entityData.get(ATTACHED_TO);}
    public int attachmentTargetId(){return entityData.get(ATTACHED_ID);}
    public int attachmentPart(){return entityData.get(ATTACH_PART);}
    public int attachmentBone(){return entityData.get(ATTACH_BONE);}
    /** A missing/untracked target is silent and invisible; its server clock continues. */
    public boolean soundEnabled(){return !attached() || attachmentTarget()!=null;}
    public LivingEntity attachmentTarget() {
        var id=attachmentTargetUUID();if(id.isEmpty())return null;
        if(attachedTarget!=null && !attachedTarget.isRemoved() && attachedTarget.getUUID().equals(id.get()))return attachedTarget;
        attachedTarget=null;
        if(level().isClientSide) {
            var entity=level().getEntity(attachmentTargetId());
            if(entity instanceof LivingEntity living && entity.getUUID().equals(id.get()))attachedTarget=living;
        } else if(level() instanceof ServerLevel server) {
            var player=server.getServer().getPlayerList().getPlayer(id.get());
            if(player!=null && !player.isRemoved())attachedTarget=player;
            if(attachedTarget==null)for(ServerLevel candidate:server.getServer().getAllLevels()) {
                var entity=candidate.getEntity(id.get());
                if(entity instanceof LivingEntity living && !entity.isRemoved()){attachedTarget=living;break;}
            }
            entityData.set(ATTACHED_ID,attachedTarget==null?-1:attachedTarget.getId());
        }
        return attachedTarget;
    }
    private static Vec3 vector(Vector3f v){return new Vec3(v.x,v.y,v.z);}
    private AttachmentAnchor anchor(){return AttachmentAnchor.of(attachedToHead(),attachmentPart(),attachmentBone());}
    private void keep(AttachmentAnchor anchor) {
        entityData.set(ATTACH_HEAD,anchor.head());entityData.set(ATTACH_PART,anchor.part());entityData.set(ATTACH_BONE,anchor.bone());
    }
    public Vec3 attachmentPosition(float partial) {
        var target=attachmentTarget();
        return target==null?position():anchor().position(target,vector(entityData.get(ATTACH_POINT)),partial);
    }
    public Vec3 attachmentDirection(float partial) {
        var target=attachmentTarget();
        return target==null?new Vec3(0,0,1):anchor().direction(target,vector(entityData.get(ATTACH_FORWARD)),partial);
    }
    private void attach(LivingEntity target,AttachmentAnchor anchor,Vec3 point,Vec3 incoming) {
        attachedTarget=target;entityData.set(ATTACHED_TO,Optional.of(target.getUUID()));entityData.set(ATTACHED_ID,target.getId());
        var placed=anchor.place(target,point,incoming);
        keep(placed.anchor());
        var local=placed.point();var direction=placed.direction();
        entityData.set(ATTACH_POINT,new Vector3f((float)local.x,(float)local.y,(float)local.z));
        entityData.set(ATTACH_FORWARD,new Vector3f((float)direction.x,(float)direction.y,(float)direction.z));
        attachmentDeaths=target instanceof ServerPlayer p?p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)):-1;
        setPos(attachmentPosition(1));
    }
    private boolean followAttachment() {
        if(attachedTarget!=null && (attachedTarget.getHealth()<=0 || attachedTarget.getRemovalReason()==RemovalReason.KILLED
                || attachedTarget.getRemovalReason()==RemovalReason.DISCARDED)) {
            if(!level().isClientSide)discard();return false;
        }
        var target=attachmentTarget();
        if(target==null){setDeltaMovement(Vec3.ZERO);return true;}
        if(!level().isClientSide && (target.getHealth()<=0 || target instanceof ServerPlayer p && attachmentDeaths>=0
                && p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS))!=attachmentDeaths)){discard();return false;}
        Vec3 next=attachmentPosition(1);
        if(target.level()!=level()) {
            if(!level().isClientSide && target.level() instanceof ServerLevel destination) {
                long elapsed=playbackPositionTicks();
                var moved=changeDimension(new DimensionTransition(destination,next,Vec3.ZERO,getYRot(),getXRot(),DimensionTransition.DO_NOTHING));
                if(moved instanceof DiscProjectile disc) {
                    disc.attachedTarget=target;disc.entityData.set(ATTACHED_ID,target.getId());
                    disc.entityData.set(MUSIC_START_TICK,destination.getGameTime()-elapsed);
                }
            }
            return false;
        }
        Vec3 step=next.subtract(position());setPos(next);setDeltaMovement(step.lengthSqr()>4?Vec3.ZERO:step);return true;
    }

    /** Follow even if the old chunk stopped receiving entity ticks as the target moved away. */
    void tickLoaded() {
        if(tickPlayback() && attached())followAttachment();
    }
    /** Client entity order must not leave attached blades a tick behind an animated target. */
    public void refreshAttachedPose(){if(level().isClientSide && attached() && !isRemoved())followAttachment();}
    /** Its clock never changes once launched: always its first revision. */
    @Override public int revision() { return clock().revision(); }

    public DiscCellTier cellTier() { return DiscCellTier.at(entityData.get(CELL_TIER)); }
    public void setCellTier(DiscCellTier tier) { entityData.set(CELL_TIER, tier.level); }
    public float damage() { return cellTier().damage; }
    @Override public ItemStack getPickResult() { return ItemStack.EMPTY; }
    @Override public boolean takesLengthFrom(ServerPlayer player) { return getOwner() == player; }
    @Override public void learnLength(double seconds) {
        if (!level().isClientSide) learn(seconds);
    }
    private void learn(double seconds) {
        PlaybackClock clock = clock(); clock.learn(seconds); length = clock.length();
    }

    @Override
    public void tick() {
        if (!level().isClientSide && !tickPlayback()) return;
        if (embedded()) {
            // Embedded blades cannot raycast and hurt a target again.
            baseTick();
            if(attached())followAttachment();else setDeltaMovement(Vec3.ZERO);
        } else {
            super.tick();
        }
    }

    /** Also called for loaded CDs outside the simulation distance, whose movement is not ticking. */
    boolean tickPlayback() {
        if (isRemoved()) return false;
        if (!level().isClientSide) {
            if (!getItem().has(LauncherFeature.DISC_PLAYBACK.get())) {
                ItemStack original = getItem().copyWithCount(1);
                original.set(LauncherFeature.DISC_PLAYBACK.get(), DiscPlayback.prepare(level(), original));
                setItem(original);
            }
            if (spawnedAtTick == Long.MIN_VALUE) spawnedAtTick = level().getGameTime();
            // Flight has a separate, short lifetime. A missed shot must not slow into a floating speaker.
            long flightAge = level().getGameTime() - spawnedAtTick;
            if (!embedded() && (flightAge >= MAX_FLIGHT_TICKS
                    || flightAge >= 4 && getDeltaMovement().lengthSqr() < .0025)
                    || embedded() && !attached() && !hasBlockSupport()) {
                discard();
                return false;
            }
            if (entityData.get(MUSIC_START_TICK) == Long.MIN_VALUE) {
                entityData.set(MUSIC_START_TICK, level().getGameTime()
                        - DiscPlayback.prepare(level(), getItem()).positionTicks());
            }
            if (length <= 0 && level().getGameTime() >= nextDurationCheckTick) {
                nextDurationCheckTick = level().getGameTime() + 20;
                learn(MediaItem.lengthSeconds(getItem(), level().registryAccess()));
            }
            boolean ended = clock().ended(level().getGameTime());
            if (ended || length <= 0 && level().getGameTime() - spawnedAtTick >= UNKNOWN_LIFETIME_TICKS) {
                // Projections are never obtainable items: the original stays in its cell. Its music sees it go and stops.
                discard();
                return false;
            }
        }
        return true;
    }

    // Fixtures (the launcher game tests and harness, in this package) set what a save would carry through these,
    // never by writing its save format by hand.
    void musicStartedAgo(long ticks) { entityData.set(MUSIC_START_TICK, level().getGameTime() - ticks); }
    void firedAgo(long ticks) { spawnedAtTick = level().getGameTime() - ticks; }
    void stuckHere() { entityData.set(EMBEDDED, true); setDeltaMovement(Vec3.ZERO); setNoGravity(true); }
    /** Its track is {@code seconds} long, as the server learned it; negative while unknown. */
    void trackLasts(double seconds) { length = seconds; }
    double trackLength() { return length; }

    @Override
    protected Item getDefaultItem() {
        return AudioFeature.CUSTOM_DISC.get();
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (!level().isClientSide && !embedded()) {
            var struck=hit.getEntity();
            var strike=AttachmentAnchor.strike(struck,hit.getLocation());
            var target=strike.target();
            Vec3 incoming=getDeltaMovement().lengthSqr()>1e-8?getDeltaMovement().normalize():getOwner()!=null?getOwner().getLookAngle():new Vec3(0,0,1);
            boolean head=strike.head();
            float amount=damage()*(head?DiscHitZones.HEAD_MULTIPLIER:1);
            float before=target instanceof LivingEntity living?living.getHealth():0;
            float absorbedBefore=target instanceof LivingEntity living?living.getAbsorptionAmount():0;
            // Dragon parts are not LivingEntity, but damage must pass through the exact struck part.
            boolean accepted=struck.hurt(DiscHitZones.source(this),amount);
            if(accepted && getOwner() instanceof net.minecraft.server.level.ServerPlayer shooter) {
                float dealt=target instanceof LivingEntity living?Math.max(0,before-living.getHealth()):amount;
                float shield=target instanceof LivingEntity living?Math.max(0,absorbedBefore-living.getAbsorptionAmount()):0;
                boolean broken=absorbedBefore>0 && target instanceof LivingEntity living && living.getAbsorptionAmount()<=0;
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(shooter,new LauncherFeedback.Hit(target.getId(),head,!target.isAlive(),dealt,shield,broken,hit.getLocation().x,hit.getLocation().y,hit.getLocation().z));
            }
            if(target instanceof LivingEntity living) {
                embed(hit);
                if(living.getHealth()>0)attach(living,strike.anchor(),hit.getLocation(),incoming);
                else discard();
            } else discard(); // Boats, frames and other non-living hits cannot become unsupported emitters.
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        if (!level().isClientSide && !embedded()) {
            hit=DiscBallistics.trace(this,position(),position().add(getDeltaMovement()),this::canHitEntity);
            if(hit.getType()==HitResult.Type.MISS)return;
        }
        super.onHit(hit);
        if (!level().isClientSide && hit.getType() == HitResult.Type.BLOCK && !embedded()) {
            blockAnchor = ((BlockHitResult)hit).getBlockPos().immutable();
            embed(hit);
        }
    }

    private boolean supportedBy(BlockPos pos, AABB contact) {
        var shape = level().getBlockState(pos).getCollisionShape(level(),pos);
        for (var box : shape.toAabbs()) if (box.move(pos).intersects(contact)) return true;
        return false;
    }

    private boolean hasBlockSupport() {
        AABB contact = new AABB(position(),position()).inflate(.0625);
        if (blockAnchor != null)
            return !level().hasChunkAt(blockAnchor) || supportedBy(blockAnchor,contact);
        // Migrate older saves using real collision, rather than preserving orphaned embedded discs in air.
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(position().add(-.0625,-.0625,-.0625)),
                BlockPos.containing(position().add(.0625,.0625,.0625)))) {
            if (!level().hasChunkAt(pos)) return true;
            if (supportedBy(pos,contact)) { blockAnchor=pos.immutable(); return true; }
        }
        return false;
    }

    private void embed(HitResult hit) {
        if (embedded()) return;
        Vec3 hitAt = hit.getLocation();
        setPos(hitAt.x, hitAt.y, hitAt.z);
        setDeltaMovement(Vec3.ZERO);
        setNoGravity(true);
        entityData.set(EMBEDDED, true);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("CmsDiscEmbedded", embedded());
        tag.putLong("CmsDiscMusicStart", entityData.get(MUSIC_START_TICK));
        tag.putLong("CmsDiscSpawnedAt", spawnedAtTick);
        tag.putDouble("CmsDiscDuration", length);
        tag.putInt("CmsDiscCellTier", cellTier().level);
        if (blockAnchor != null) tag.putLong("CmsDiscBlockAnchor",blockAnchor.asLong());
        attachmentTargetUUID().ifPresent(id->tag.putUUID("CmsDiscAttachedTo",id));
        anchor().save(tag);tag.putInt("CmsDiscAttachmentDeaths",attachmentDeaths);
        writeVector(tag,"CmsDiscAttachPoint",entityData.get(ATTACH_POINT));writeVector(tag,"CmsDiscAttachForward",entityData.get(ATTACH_FORWARD));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(EMBEDDED, tag.getBoolean("CmsDiscEmbedded"));
        entityData.set(MUSIC_START_TICK, tag.contains("CmsDiscMusicStart") ? tag.getLong("CmsDiscMusicStart") : Long.MIN_VALUE);
        spawnedAtTick = tag.contains("CmsDiscSpawnedAt") ? tag.getLong("CmsDiscSpawnedAt")
                : entityData.get(MUSIC_START_TICK);
        length = tag.contains("CmsDiscDuration") ? tag.getDouble("CmsDiscDuration") : -1;
        blockAnchor = tag.contains("CmsDiscBlockAnchor") ? BlockPos.of(tag.getLong("CmsDiscBlockAnchor")) : null;
        setCellTier(DiscCellTier.at(tag.contains("CmsDiscCellTier") ? tag.getInt("CmsDiscCellTier") : 1));
        entityData.set(ATTACHED_TO,tag.hasUUID("CmsDiscAttachedTo")?Optional.of(tag.getUUID("CmsDiscAttachedTo")):Optional.empty());
        entityData.set(ATTACHED_ID,-1);attachedTarget=null;
        var anchor=AttachmentAnchor.read(tag);keep(anchor);
        entityData.set(ATTACH_POINT,readVector(tag,"CmsDiscAttachPoint",new Vector3f(),anchor.pointBound()));
        var forward=readVector(tag,"CmsDiscAttachForward",new Vector3f(0,0,1),1);
        entityData.set(ATTACH_FORWARD,forward.lengthSquared()<1e-8f?new Vector3f(0,0,1):forward.normalize());
        attachmentDeaths=tag.contains("CmsDiscAttachmentDeaths")?tag.getInt("CmsDiscAttachmentDeaths"):-1;
        if(attached())entityData.set(EMBEDDED,true);
        if (embedded()) {setDeltaMovement(Vec3.ZERO);setNoGravity(true);}
    }
    private static void writeVector(CompoundTag parent,String name,Vector3f vector) {
        var tag=new CompoundTag();tag.putFloat("X",vector.x);tag.putFloat("Y",vector.y);tag.putFloat("Z",vector.z);parent.put(name,tag);
    }
    private static Vector3f readVector(CompoundTag parent,String name,Vector3f fallback,float bound) {
        if(!parent.contains(name))return fallback;var tag=parent.getCompound(name);float x=tag.getFloat("X"),y=tag.getFloat("Y"),z=tag.getFloat("Z");
        return Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z)?new Vector3f(Math.clamp(x,-bound,bound),Math.clamp(y,-bound,bound),Math.clamp(z,-bound,bound)):fallback;
    }
}
