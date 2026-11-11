package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.player.Player;
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
public final class DiscProjectile extends ThrowableItemProjectile {
    private static final int UNKNOWN_LIFETIME_TICKS = 20 * 180;
    private static final int MAX_FLIGHT_TICKS = 20 * 5;
    private static final int BLOCK_ATTACHMENT_TICKS = 20 * 60;
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
    private long embeddedAtTick = -1L;
    private long spawnedAtTick = Long.MIN_VALUE;
    private double durationSeconds = -1;
    private long nextDurationCheckTick;
    private LivingEntity attachedTarget;
    private int attachmentDeaths=-1;
    private BlockPos blockAnchor;

    public DiscProjectile(EntityType<? extends DiscProjectile> type, Level level) {
        super(type, level);
    }

    public DiscProjectile(Level level, LivingEntity owner, ItemStack disc) {
        this(level, owner, disc, DiscPlayback.prepare(level, disc));
    }

    private DiscProjectile(Level level, LivingEntity owner, ItemStack disc, DiscPlayback playback) {
        super(CreateMediaSystems.DISC_PROJECTILE.get(), owner, level);
        ItemStack original = disc.copyWithCount(1);
        original.set(CreateMediaSystems.DISC_PLAYBACK.get(), playback);
        setItem(original);
        setNoGravity(true);
        spawnedAtTick = level.getGameTime();
        entityData.set(MUSIC_START_TICK, spawnedAtTick - playback.positionTicks());
    }

    static DiscProjectile forShot(Player shooter, ItemStack disc, int startSeconds, Vec3 forward, boolean aimed) {
        Level level = shooter.level();
        DiscPlayback playback = DiscPlayback.prepare(level, disc).at(startSeconds * 20L);
        DiscProjectile projectile = new DiscProjectile(level, shooter, disc, playback);
        projectile.setPos(shooter.getEyePosition());
        Vec3 direction = aimed ? DiscBallistics.assistAim(projectile, shooter, forward, projectile::canHitEntity) : forward;
        projectile.shoot(direction.x, direction.y, direction.z, 5.2f, aimed ? 0f : .15f);
        return projectile;
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

    /** Server-synchronized start time; a joining listener seeks into the ongoing track. */
    public double elapsedMusicSeconds() {
        return playbackPositionTicks() / 20.0;
    }

    public long playbackPositionTicks() {
        long start = entityData.get(MUSIC_START_TICK);
        return start == Long.MIN_VALUE ? 0 : Math.max(0L, level().getGameTime() - start);
    }

    /** Snapshot this CD's server clock when it leaves the world, without affecting any other CD. */
    public ItemStack playbackItem() {
        ItemStack item = getItem().copy();
        item.set(CreateMediaSystems.DISC_PLAYBACK.get(), DiscPlayback.prepare(level(), item).at(playbackPositionTicks()));
        return item;
    }

    public boolean embedded() {
        return entityData.get(EMBEDDED);
    }

    boolean blockAttachmentExpired() {
        return embedded() && !attached() && embeddedAtTick <= level().getGameTime() - BLOCK_ATTACHMENT_TICKS;
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
    private DiscAttachmentFrame attachmentFrame(LivingEntity target,float partial) {
        return target instanceof EnderDragon dragon && attachmentPart()>=0
                ?DiscAttachmentFrame.atDragonPart(dragon,attachmentPart(),partial):DiscAttachmentFrame.at(target,attachedToHead(),partial);
    }
    public Vec3 attachmentPosition(float partial) {
        var target=attachmentTarget();
        if(target instanceof EnderDragon dragon && attachmentBone()>=0)return DiscDragonAttachment.bone(dragon,attachmentBone(),partial).position(vector(entityData.get(ATTACH_POINT)));
        return target==null?position():attachmentFrame(target,partial).world(vector(entityData.get(ATTACH_POINT)));
    }
    public Vec3 attachmentDirection(float partial) {
        var target=attachmentTarget();
        if(target instanceof EnderDragon dragon && attachmentBone()>=0)return DiscDragonAttachment.bone(dragon,attachmentBone(),partial).direction(vector(entityData.get(ATTACH_FORWARD)));
        return target==null?new Vec3(0,0,1):attachmentFrame(target,partial)
                .worldDirection(vector(entityData.get(ATTACH_FORWARD))).normalize();
    }
    private void attach(LivingEntity target,Vec3 point,Vec3 incoming,boolean head) {
        attachedTarget=target;entityData.set(ATTACHED_TO,Optional.of(target.getUUID()));entityData.set(ATTACHED_ID,target.getId());
        entityData.set(ATTACH_HEAD,head);
        var frame=attachmentFrame(target,1);
        // The swept hit is just outside the skin. Move the blade centre slightly in, leaving its outer half visible.
        var local=frame.local(point.add(incoming.scale(.10)));var direction=frame.localDirection(incoming);
        if(target instanceof EnderDragon dragon && attachmentPart()>=0) {
            var contact=DiscDragonAttachment.contact(dragon,attachmentPart(),point,incoming);entityData.set(ATTACH_BONE,contact.bone());local=contact.point();direction=contact.direction();
        }
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

    /** Loaded discs can outlive the simulation ticks for their chunk. */
    void tickLoaded(boolean checkPlayback) {
        if (attached()) {
            if (tickPlayback()) followAttachment();
        } else if (checkPlayback) {
            tickPlayback();
        } else if (blockAttachmentExpired()) {
            discard();
        }
    }
    /** Client entity order must not leave attached blades a tick behind an animated target. */
    public void refreshAttachedPose(){if(level().isClientSide && attached() && !isRemoved())followAttachment();}

    public DiscCellTier cellTier() { return DiscCellTier.at(entityData.get(CELL_TIER)); }
    public void setCellTier(DiscCellTier tier) { entityData.set(CELL_TIER, tier.level); }
    public float damage() { return cellTier().damage; }
    @Override public ItemStack getPickResult() { return ItemStack.EMPTY; }
    public void noteDuration(double seconds) {
        if (!level().isClientSide && durationSeconds <= 0 && Double.isFinite(seconds) && seconds > 0 && seconds <= 86400)
            durationSeconds = seconds;
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
            if (blockAttachmentExpired()) {
                discard();
                return false;
            }
            if (!getItem().has(CreateMediaSystems.DISC_PLAYBACK.get())) {
                ItemStack original = getItem().copyWithCount(1);
                original.set(CreateMediaSystems.DISC_PLAYBACK.get(), DiscPlayback.prepare(level(), original));
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
            if (durationSeconds < 0 && level().getGameTime() >= nextDurationCheckTick) {
                nextDurationCheckTick = level().getGameTime() + 20;
                durationSeconds = DiscPlayback.duration(level(), getItem());
            }
            boolean ended = durationSeconds > 0 && elapsedMusicSeconds() >= durationSeconds;
            if (ended || durationSeconds <= 0 && level().getGameTime() - spawnedAtTick >= UNKNOWN_LIFETIME_TICKS) {
                recover(ended);
                return false;
            }
        }
        return true;
    }

    @Override
    protected Item getDefaultItem() {
        return CreateMediaSystems.CUSTOM_DISC.get();
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (!level().isClientSide && !embedded()) {
            var struck=hit.getEntity();
            var target=struck instanceof EnderDragonPart part?part.parentMob:struck;
            int partIndex=-1;
            if(struck instanceof EnderDragonPart part) {
                var parts=part.parentMob.getSubEntities();for(int i=0;i<parts.length;i++)if(parts[i]==part){partIndex=i;break;}
            }
            Vec3 incoming=getDeltaMovement().lengthSqr()>1e-8?getDeltaMovement().normalize():getOwner()!=null?getOwner().getLookAngle():new Vec3(0,0,1);
            boolean head=struck instanceof EnderDragonPart part?part==part.parentMob.head
                    :target instanceof LivingEntity living && DiscHitZones.headshot(living,hit.getLocation());
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
                entityData.set(ATTACH_PART,partIndex);
                if(living.getHealth()>0)attach(living,hit.getLocation(),incoming,head);
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
        embeddedAtTick = level().getGameTime();
        entityData.set(EMBEDDED, true);
    }

    private void recover(boolean ended) {
        if (isRemoved()) return;
        // Projections are never obtainable items. The original remains inside the energy cell.
        discard(); // The matching music instance sees its entity disappear and stops.
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("CmsDiscEmbedded", embedded());
        tag.putLong("CmsDiscMusicStart", entityData.get(MUSIC_START_TICK));
        tag.putLong("CmsDiscEmbeddedAt", embeddedAtTick);
        tag.putLong("CmsDiscSpawnedAt", spawnedAtTick);
        tag.putDouble("CmsDiscDuration", durationSeconds);
        tag.putInt("CmsDiscCellTier", cellTier().level);
        if (blockAnchor != null) tag.putLong("CmsDiscBlockAnchor",blockAnchor.asLong());
        attachmentTargetUUID().ifPresent(id->tag.putUUID("CmsDiscAttachedTo",id));
        tag.putBoolean("CmsDiscAttachHead",attachedToHead());tag.putInt("CmsDiscAttachmentDeaths",attachmentDeaths);
        tag.putInt("CmsDiscAttachPart",attachmentPart());
        tag.putInt("CmsDiscAttachBone",attachmentBone());
        writeVector(tag,"CmsDiscAttachPoint",entityData.get(ATTACH_POINT));writeVector(tag,"CmsDiscAttachForward",entityData.get(ATTACH_FORWARD));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(EMBEDDED, tag.getBoolean("CmsDiscEmbedded"));
        entityData.set(MUSIC_START_TICK, tag.contains("CmsDiscMusicStart") ? tag.getLong("CmsDiscMusicStart") : Long.MIN_VALUE);
        embeddedAtTick = tag.contains("CmsDiscEmbeddedAt") ? tag.getLong("CmsDiscEmbeddedAt") : level().getGameTime();
        spawnedAtTick = tag.contains("CmsDiscSpawnedAt") ? tag.getLong("CmsDiscSpawnedAt")
                : entityData.get(MUSIC_START_TICK);
        durationSeconds = tag.contains("CmsDiscDuration") ? tag.getDouble("CmsDiscDuration") : -1;
        blockAnchor = tag.contains("CmsDiscBlockAnchor") ? BlockPos.of(tag.getLong("CmsDiscBlockAnchor")) : null;
        setCellTier(DiscCellTier.at(tag.contains("CmsDiscCellTier") ? tag.getInt("CmsDiscCellTier") : 1));
        entityData.set(ATTACHED_TO,tag.hasUUID("CmsDiscAttachedTo")?Optional.of(tag.getUUID("CmsDiscAttachedTo")):Optional.empty());
        entityData.set(ATTACHED_ID,-1);attachedTarget=null;entityData.set(ATTACH_HEAD,tag.getBoolean("CmsDiscAttachHead"));
        entityData.set(ATTACH_PART,tag.contains("CmsDiscAttachPart")?Math.clamp(tag.getInt("CmsDiscAttachPart"),-1,7):-1);
        entityData.set(ATTACH_BONE,tag.contains("CmsDiscAttachBone")?Math.clamp(tag.getInt("CmsDiscAttachBone"),-1,22):-1);
        entityData.set(ATTACH_POINT,readVector(tag,"CmsDiscAttachPoint",new Vector3f(),attachmentBone()>=0?8:2));
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
