package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShaderLoader {
    public static String parse(ResourceLocation path) {
        return parse(path, shaderConstants -> {});
    }

    public static String parse(ResourceLocation path, Consumer<ShaderConstants.Builder> constantBuilder) {
        var builder = ShaderConstants.builder();
        if (Nvidium.IS_DEBUG) {
            builder.add("DEBUG");
        }

        for (int i = 1; i <= Nvidium.config.statistics_level.ordinal(); i++) {
            builder.add("STATISTICS_"+StatisticsLoggingLevel.values()[i].name());
        }


        if (Nvidium.config.translucency_sorting_level.ordinal() >= TranslucencySortingLevel.SECTIONS.ordinal()) {
            builder.add("TRANSLUCENCY_SORTING_SECTIONS");
        }
        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.QUADS) {
            builder.add("TRANSLUCENCY_SORTING_QUADS");
        }
        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.SODIUM) {
            builder.add("TRANSLUCENCY_SORTING_SODIUM");
        }

        if (Nvidium.config.render_fog) {
            builder.add("RENDER_FOG");
        }

        if (Nvidium.config.use_sodium_vertex_format) {
            builder.add("USE_SODIUM_VERTEX_FORMAT");
        }
        if (Nvidium.config.cull_degenerate_triangles) {
            builder.add("CULL_DEGENERATE_TRIANGLES");
        }
        if (Nvidium.config.use_nv_fragment_shader_barycentric) {
            builder.add("USE_NV_FRAGMENT_SHADER_BARYCENTRIC");
        }

        builder.add("TEXTURE_MAX_SCALE", String.valueOf(NvidiumCompactChunkVertex.TEXTURE_MAX_VALUE));
        constantBuilder.accept(builder);

        // Resolve Nvidium's #import graph using Nvidium's OWN classloader. On
        // NeoForge, Sodium and Nvidium are separate modules, so Sodium's
        // ShaderLoader.getShaderSource (which uses Sodium's classloader) cannot
        // see assets/nvidium/**. We mirror Sodium's recursive #import resolution
        // here, reading resources via this class's classloader (same-module
        // access always works, in dev and in a jar), then hand the fully-
        // resolved, import-free source to Sodium's parser purely for #define
        // injection. All Nvidium shader imports use the nvidium: namespace, so
        // this is self-contained.
        String resolved = String.join("\n", parseShaderSource("#import <"+path.getNamespace()+":"+path.getPath()+">"));
        return ShaderParser.parseShader(resolved, builder.build());
    }

    private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

    // Mirrors Sodium's ShaderParser line/import handling, but resolves resources
    // via Nvidium's own classloader so assets/nvidium/** is visible on NeoForge.
    private static List<String> parseShaderSource(String src) {
        List<String> lines = new LinkedList<>();
        String line;
        try (BufferedReader reader = new BufferedReader(new StringReader(src))) {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#import")) {
                    lines.addAll(resolveImport(line));
                } else {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader sources", e);
        }
        return lines;
    }

    private static List<String> resolveImport(String line) {
        Matcher matcher = IMPORT_PATTERN.matcher(line);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Malformed import statement: " + line);
        }
        return parseShaderSource(getShaderSource(matcher.group("namespace"), matcher.group("path")));
    }

    private static String getShaderSource(String namespace, String path) {
        String full = String.format("/assets/%s/shaders/%s", namespace, path);
        try (InputStream in = ShaderLoader.class.getResourceAsStream(full)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + full);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source for " + full, e);
        }
    }
}
