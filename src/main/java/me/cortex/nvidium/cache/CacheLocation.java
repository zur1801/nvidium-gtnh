package me.cortex.nvidium.cache;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.World;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import me.cortex.nvidium.Nvidium;

/**
 * Picks the cache directory for the current server and dimension, and wipes a server's cache when anything that
 * changes the texture atlas layout or vertex encoding (mods, resource packs, mipmaps, vertex format) has changed.
 */
public final class CacheLocation {

    private static final int FORMAT_VERSION = 1;

    private CacheLocation() {}

    public static File resolve(World world, int vertexStride) {
        Minecraft mc = Minecraft.getMinecraft();
        File serverDir = new File(new File(mc.mcDataDir, "nvidium-cache"), sanitize(serverName(mc)));
        ensureFingerprint(serverDir, fingerprint(mc, vertexStride));
        return new File(serverDir, "DIM" + world.provider.dimensionId);
    }

    private static String serverName(Minecraft mc) {
        ServerData server = mc.func_147104_D();
        if (server != null && server.serverIP != null) {
            return server.serverIP;
        }
        if (mc.getIntegratedServer() != null) {
            return "singleplayer-" + mc.getIntegratedServer()
                .getFolderName();
        }
        return "unknown";
    }

    private static String sanitize(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9._-]", "_");
    }

    private static String fingerprint(Minecraft mc, int vertexStride) {
        List<String> parts = new ArrayList<>();
        parts.add("format=" + FORMAT_VERSION);
        parts.add("stride=" + vertexStride);
        parts.add("mipmaps=" + mc.gameSettings.mipmapLevels);
        parts.add("packs=" + String.join(",", mc.gameSettings.resourcePacks));
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            parts.add(mod.getModId() + "@" + mod.getVersion());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ensureFingerprint(File serverDir, String fingerprint) {
        File file = new File(serverDir, "fingerprint.txt");
        try {
            if (file.isFile()
                && fingerprint.equals(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim())) {
                return;
            }
            if (serverDir.exists()) {
                Nvidium.LOGGER.info("Mods, resource packs or settings changed; clearing mesh cache {}", serverDir);
                try (Stream<java.nio.file.Path> paths = Files.walk(serverDir.toPath())) {
                    paths.sorted(Comparator.reverseOrder())
                        .forEach(
                            p -> p.toFile()
                                .delete());
                }
            }
            Files.createDirectories(serverDir.toPath());
            Files.write(file.toPath(), fingerprint.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Nvidium.LOGGER.warn("Could not validate mesh cache fingerprint in {}", serverDir, e);
        }
    }
}
