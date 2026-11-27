package org.haruyq.createmediasystems.api.media;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.display.DisplayBlock;
import org.haruyq.createmediasystems.blocks.display.DisplayFeature;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.VideoSource;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MediaBlockGameTests {
    /** Another mod's block entity, as its mod writes it: here a barrel's, which needs no registering. */
    private static final class Source extends BlockEntity implements MediaBlock {
        private final MediaPlayer media = MediaPlayer.of(this);

        Source(BlockPos pos, BlockState state) {
            super(BlockEntityType.BARREL, pos, state);
        }

        @Override
        public MediaPlayer media() {
            return media;
        }
    }

    private static Source place(GameTestHelper h, BlockPos at) {
        BlockPos pos = h.absolutePos(at);
        h.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        Source source = new Source(pos, h.getLevel().getBlockState(pos));
        h.getLevel().setBlockEntity(source);
        return source;
    }

    @GameTest(batch = "launcher", template = "empty")
    public static void anotherModsBlockPlaysToADisplayLinkedToIt(GameTestHelper h) {
        var level = h.getLevel();
        Source block = place(h, new BlockPos(1, 2, 1));
        BlockPos source = block.getBlockPos();
        MediaPlayer media = block.media();
        BlockPos anchor = h.absolutePos(new BlockPos(6, 2, 3));
        level.setBlockAndUpdate(anchor, DisplayFeature.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING, Direction.NORTH));
        var display = (DisplayBlock.Entity) level.getBlockEntity(anchor);
        h.assertTrue(level.getBlockEntity(source) instanceof VideoSource, "A display quill takes it for a source");
        display.link(source);
        display.relay(level);
        h.assertTrue(!media.playing() && MediaSources.screens(level, source).isEmpty(), "It plays nothing yet");

        // Only what the server allows, as on a disc.
        h.assertTrue(!media.play("https://example.invalid/film.mp4", 1080, null) && !media.play("file:///film.mp4", 1080, null)
                && !media.playing(), "A link the server doesn't allow isn't played");
        String track = new DiscUrl(UUID.randomUUID(), UUID.randomUUID()).toString();
        h.assertTrue(media.play(track, 1080, null) && media.playing(), "One it allows is");
        display.relay(level);
        Streams.Source playing = Streams.source(level, source);
        h.assertTrue(playing != null && playing.url().equals(track) && playing.video() != null
                && playing.video().maxHeight() == 1080 && playing.video().player().equals(source), "As a video from it: " + playing);
        h.assertTrue(MediaSources.screens(level, source).size() == 1, "Its display shows it");
        media.tick();

        // A still picture reaches the display, which every client reads it from.
        String image = "https://cdn.discordapp.com/attachments/1/2/cover.png";
        h.assertTrue(!media.show("https://example.invalid/cover.png") && media.show(image), "An image the server allows is shown");
        display.relay(level);
        h.assertTrue(display.image().equals(image) && display.getUpdateTag(level.registryAccess()).getString("SourceImage").equals(image),
                "The display has it for its clients: " + display.image());

        // Saved and loaded, it plays on: sound alone here, as music is.
        h.assertTrue(media.play(track, 0, null) && Streams.source(level, source).video() == null, "Sound alone has no video");
        CompoundTag saved = new CompoundTag();
        media.save(saved);
        Source other = place(h, new BlockPos(1, 2, 5));
        other.media().load(saved);
        Streams.Source resumed = Streams.source(level, other.getBlockPos());
        h.assertTrue(other.media().playing() && resumed != null && resumed.url().equals(track) && other.image().equals(image),
                "Loaded, it plays what was saved: " + resumed);

        media.stop();
        h.assertTrue(media.show(""), "Its image is taken away");
        display.relay(level);
        h.assertTrue(!media.playing() && MediaSources.screens(level, source).isEmpty() && display.image().isEmpty(),
                "Stopped and cleared, its display shows nothing");
        h.succeed();
    }

    @GameTest(batch = "launcher", template = "empty")
    public static void rawAudioPlaysAsAnyLink(GameTestHelper h) {
        var level = h.getLevel();
        MediaPlayer media = place(h, new BlockPos(1, 2, 1)).media();
        BlockPos source = h.absolutePos(new BlockPos(1, 2, 1));
        String feed = RawAudio.feed(CreateMediaSystems.id("test_feed"));
        h.assertTrue(media.play(feed, 0, null) && Streams.source(level, source).url().equals(feed), "A live feed plays");
        String clip = RawAudio.clip(new byte[] {'O', 'g', 'g', 'S'});
        h.assertTrue(media.play(clip, 0, null) && Streams.source(level, source).url().equals(clip), "So does a clip");
        h.assertTrue(!media.play("cms-clip:nothex", 0, null) && !media.play("cms-raw:Bad Id", 0, null),
                "Malformed ones don't");
        media.stop();
        h.succeed();
    }
}
