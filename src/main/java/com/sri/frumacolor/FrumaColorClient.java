package com.sri.frumacolor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FrumaColorClient implements ClientModInitializer {

    /** Saved to config/frumacolor.json */
    public static class Config {
        public Map<String, Integer> colors = new HashMap<>(); // particle id -> 0xRRGGBB
        public int allColor = -1;                              // -1 = off, otherwise tint every particle (test mode)
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("frumacolor.json");
    public static Config config = new Config();

    private static boolean logging = false;
    private static final Set<String> seen = new HashSet<>();

    @Override
    public void onInitializeClient() {
        load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("fcolor")
                // /fcolor add end_rod FF00AA
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("particle", StringArgumentType.word())
                        .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                            .executes(ctx -> {
                                String id = normalize(StringArgumentType.getString(ctx, "particle"));
                                Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                                if (rgb == null) { msg(ctx.getSource(), "Bad hex color. Use e.g. FF00AA"); return 0; }
                                config.colors.put(id, rgb);
                                save();
                                msg(ctx.getSource(), "Set " + id + " to #" + String.format("%06X", rgb));
                                return 1;
                            }))))
                // /fcolor remove end_rod
                .then(ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("particle", StringArgumentType.word())
                        .executes(ctx -> {
                            String id = normalize(StringArgumentType.getString(ctx, "particle"));
                            config.colors.remove(id);
                            save();
                            msg(ctx.getSource(), "Removed " + id);
                            return 1;
                        })))
                // /fcolor list
                .then(ClientCommandManager.literal("list")
                    .executes(ctx -> {
                        if (config.colors.isEmpty()) msg(ctx.getSource(), "No particles set.");
                        config.colors.forEach((k, v) -> msg(ctx.getSource(), k + " -> #" + String.format("%06X", v)));
                        if (config.allColor >= 0) msg(ctx.getSource(), "ALL particles -> #" + String.format("%06X", config.allColor));
                        return 1;
                    }))
                // /fcolor all FF00FF   or   /fcolor all off   (test mode: tints every particle)
                .then(ClientCommandManager.literal("all")
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        config.allColor = -1; save();
                        msg(ctx.getSource(), "Test mode off");
                        return 1;
                    }))
                    .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(ctx -> {
                        Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                        if (rgb == null) { msg(ctx.getSource(), "Bad hex color."); return 0; }
                        config.allColor = rgb; save();
                        msg(ctx.getSource(), "Every particle is now #" + String.format("%06X", rgb));
                        return 1;
                    })))
                // /fcolor log on|off : prints each new particle type spawned near you once
                .then(ClientCommandManager.literal("log")
                    .then(ClientCommandManager.literal("on").executes(ctx -> {
                        logging = true; seen.clear();
                        msg(ctx.getSource(), "Logging on. Use Fruma's M1 and watch chat.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        logging = false;
                        msg(ctx.getSource(), "Logging off");
                        return 1;
                    })))
                // /fcolor entities : lists entities within 6 blocks (stand next to your totem)
                .then(ClientCommandManager.literal("entities")
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.level == null || mc.player == null) return 0;
                        int n = 0;
                        for (Entity e : mc.level.entitiesForRendering()) {
                            if (e == mc.player || e.distanceTo(mc.player) > 6) continue;
                            String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
                            msg(ctx.getSource(), type + " | name=\"" + e.getName().getString()
                                + "\" | glowing=" + e.isCurrentlyGlowing());
                            if (++n >= 15) break;
                        }
                        return 1;
                    })));
        });
    }

    /** Called from the mixin every time a particle is created. */
    public static void onParticle(ParticleOptions options, Particle particle, double x, double y, double z) {
        String id = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()));

        if (logging) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.distanceToSqr(x, y, z) < 100 && seen.add(id)) {
                mc.player.displayClientMessage(Component.literal("[fcolor] particle: " + id), false);
            }
        }

        Integer rgb = config.allColor >= 0 ? Integer.valueOf(config.allColor) : config.colors.get(id);
        if (rgb == null) return;

        if (particle instanceof SingleQuadParticle quad) {
            quad.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
        }
    }

    private static String normalize(String s) {
        s = s.toLowerCase();
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static Integer parseHex(String s) {
        try {
            s = s.startsWith("#") ? s.substring(1) : s;
            if (s.length() != 6) return null;
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void msg(FabricClientCommandSource src, String text) {
        src.sendFeedback(Component.literal("[fcolor] " + text));
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) config = GSON.fromJson(Files.readString(FILE), Config.class);
        } catch (Exception ignored) { }
        if (config == null) config = new Config();
    }

    private static void save() {
        try { Files.writeString(FILE, GSON.toJson(config)); } catch (Exception ignored) { }
    }
}
