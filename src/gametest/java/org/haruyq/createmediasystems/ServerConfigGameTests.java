package org.haruyq.createmediasystems;

import java.nio.file.Files;
import java.nio.file.Path;

import org.haruyq.createmediasystems.stream.UrlPolicy;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class ServerConfigGameTests {
    @GameTest(template = "empty")
    public static void configCommandEditsSavesAndReloads(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        Path file = ServerConfig.file().getFullPath();
        String original = Files.readString(file);
        try {
            helper.assertTrue(run(source, "config allowedHosts add Example.ORG") > 0, "the host is added");
            helper.assertTrue(UrlPolicy.isAllowed("https://www.example.org/a"), "added host is allowed");
            helper.assertTrue(run(source, "config remoteRange 99") > 0, "the range is set");
            helper.assertTrue(VideoFeature.REMOTE_RANGE.get() == 99, "remoteRange set");
            helper.assertTrue(run(source, "config apiUrl https://api.example.org") > 0, "an http apiUrl is taken");
            helper.assertTrue(run(source, "config apiUrl ftp://nope") == 0, "a non-http apiUrl is refused");
            helper.assertTrue(ServerConfig.API_URL.get().equals("https://api.example.org"), "and changes nothing");
            String saved = Files.readString(file);
            helper.assertTrue(saved.contains("example.org") && saved.contains("remoteRange = 99"), "saved to file");

            Files.writeString(file, saved.replace("remoteRange = 99", "remoteRange = 7"));
            helper.assertTrue(run(source, "config reload") > 0, "the file is read again");
            helper.assertTrue(VideoFeature.REMOTE_RANGE.get() == 7, "reload reads the file");

            helper.assertTrue(run(source, "config allowedHosts remove example.org") > 0, "the host is removed");
            helper.assertFalse(UrlPolicy.isAllowed("https://www.example.org/a"), "removed host is rejected");
        } finally {
            Files.writeString(file, original);
            run(source, "config reload");
        }
        helper.succeed();
    }

    /** Runs {@code command} under the mod's command; one it doesn't know throws. */
    private static int run(CommandSourceStack source, String command) throws CommandSyntaxException {
        return source.getServer().getCommands().getDispatcher().execute(CreateMediaSystems.COMMAND + " " + command, source);
    }
}
