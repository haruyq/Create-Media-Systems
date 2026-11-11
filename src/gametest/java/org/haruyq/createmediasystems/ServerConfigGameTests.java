package org.haruyq.createmediasystems;

import java.nio.file.Files;
import java.nio.file.Path;

import org.haruyq.createmediasystems.stream.UrlPolicy;
import org.haruyq.createmediasystems.video.VideoFeature;

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
            server.getCommands().performPrefixedCommand(source, CreateMediaSystems.COMMAND + " config allowedHosts add Example.ORG");
            helper.assertTrue(UrlPolicy.isAllowed("https://www.example.org/a"), "added host is allowed");
            server.getCommands().performPrefixedCommand(source, CreateMediaSystems.COMMAND + " config remoteRange 99");
            server.getCommands().performPrefixedCommand(source, CreateMediaSystems.COMMAND + " config apiUrl ftp://nope");
            helper.assertTrue(VideoFeature.REMOTE_RANGE.get() == 99, "remoteRange set");
            helper.assertTrue(ServerConfig.API_URL.get().startsWith("http"), "non-http apiUrl rejected");
            String saved = Files.readString(file);
            helper.assertTrue(saved.contains("example.org") && saved.contains("remoteRange = 99"), "saved to file");

            Files.writeString(file, saved.replace("remoteRange = 99", "remoteRange = 7"));
            server.getCommands().performPrefixedCommand(source, CreateMediaSystems.COMMAND + " config reload");
            helper.assertTrue(VideoFeature.REMOTE_RANGE.get() == 7, "reload reads the file");

            server.getCommands().performPrefixedCommand(source, CreateMediaSystems.COMMAND + " config allowedHosts remove example.org");
            helper.assertFalse(UrlPolicy.isAllowed("https://www.example.org/a"), "removed host is rejected");
        } finally {
            Files.writeString(file, original);
            server.getCommands().performPrefixedCommand(source, CreateMediaSystems.COMMAND + " config reload");
        }
        helper.succeed();
    }
}
