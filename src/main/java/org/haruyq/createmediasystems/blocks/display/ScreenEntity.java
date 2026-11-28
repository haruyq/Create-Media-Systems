package org.haruyq.createmediasystems.blocks.display;

import java.util.List;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.Occluders;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * A picture on a wall that the Tuning Wrench sizes: a display's video, an image viewer's image. Its size (in
 * sixteenths, from its block to the right and up as seen from the front) and ratio, its panel, and the light it stops.
 */
public abstract class ScreenEntity extends BlockEntity implements IHaveGoggleInformation, Occluders.Occluder {
    private int width = DisplayBlock.UNIT;
    private int height = DisplayBlock.UNIT;
    private DisplayBlock.Ratio ratio = DisplayBlock.Ratio.WIDE;

    protected ScreenEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        Occluders.add(this);
        // Fills the parts that screens saved before they had any lack, once the chunk has loaded.
        if (level instanceof ServerLevel server) {
            MinecraftServer host = server.getServer();
            host.tell(new TickTask(host.getTickCount(), () -> {
                if (!isRemoved()) {
                    DisplayBlock.fitParts(server, worldPosition, getBlockState(), 0, 0, width, height);
                }
            }));
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        Occluders.remove(this);
    }

    public double width() {
        return width / (double) DisplayBlock.UNIT;
    }

    public double height() {
        return height / (double) DisplayBlock.UNIT;
    }

    public int widthUnits() {
        return width;
    }

    public int heightUnits() {
        return height;
    }

    public DisplayBlock.Ratio ratio() {
        return ratio;
    }

    public Direction facing() {
        return getBlockState().getValue(DisplayBlock.FACING);
    }

    /** A new size (sixteenths) and ratio. */
    public void resize(int width, int height, DisplayBlock.Ratio ratio) {
        int fromWidth = this.width;
        int fromHeight = this.height;
        this.width = DisplayBlock.clampSize(width);
        this.height = DisplayBlock.clampSize(height);
        this.ratio = ratio;
        changed();
        if (level != null) {
            DisplayBlock.fitParts(level, worldPosition, getBlockState(), fromWidth, fromHeight, this.width, this.height);
        }
    }

    /** Whether {@code player} is within {@code reach} of its panel: of a wide screen, of the part before them, not of its block. */
    public boolean near(Player player, double reach) {
        return SableCompat.toWorld(level, box(0, 0)).distanceToSqr(player.position()) <= reach * reach;
    }

    /** The screen of {@code type} at {@code pos}, when it is loaded and {@code player} is {@link #near} it. */
    @Nullable
    public static <T extends ScreenEntity> T near(Player player, BlockPos pos, double reach, Class<T> type) {
        Level level = player.level();
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ScreenEntity screen && type.isInstance(screen)
                && screen.near(player, reach) ? type.cast(screen) : null;
    }

    protected void changed() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Its panel, grown by {@code margin} around and reaching {@code front} out of the picture, in its block space. */
    public AABB box(float margin, float front) {
        return DisplayBlock.box(worldPosition, facing(), width(), height(), margin, front);
    }

    /** Its panel, from its back to just in front of its picture. */
    @Override
    public AABB occluderBox() {
        return box(0, 0.004f);
    }

    /** What it shows, as its goggles say: the image's link, or the Blu-ray player it is linked to. */
    protected abstract Component shows();

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(getBlockState().getBlock().getName()));
        tooltip.add(Component.literal("     ").append(shows()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("     ").append(DisplayBlock.sizeText(width, height, ratio)).withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** Blocks, to a sixteenth, without trailing zeros. */
    public static String format(double blocks) {
        return blocks == Math.rint(blocks) ? Long.toString(Math.round(blocks))
                : String.format(java.util.Locale.ROOT, "%.2f", blocks).replaceAll("0+$", "");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Width16", width);
        tag.putInt("Height16", height);
        tag.putInt("Ratio", ratio.ordinal());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        width = tag.contains("Width16") ? DisplayBlock.clampSize(tag.getInt("Width16")) : DisplayBlock.UNIT;
        height = tag.contains("Height16") ? DisplayBlock.clampSize(tag.getInt("Height16")) : DisplayBlock.UNIT;
        ratio = tag.contains("Ratio") ? DisplayBlock.Ratio.byId(tag.getInt("Ratio")) : DisplayBlock.Ratio.WIDE;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
