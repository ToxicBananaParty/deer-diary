package me.cortex.nvidium.compat.iris;

import me.cortex.nvidium.Nvidium;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code out} (varying) declarations of a shaderpack's terrain VERTEX source and
 * produces the GLSL that Nvidium's mesh shader must emit so its outputs line up, by name, with
 * the inputs the patched fragment shader will read.
 *
 * <p>This is the crux of the integration (findings Q4). A mesh shader replaces the conventional
 * vertex stage entirely, so it must declare and write exactly the same named varyings the pack's
 * vertex shader would have. There is no single fixed contract across packs, so we parse the
 * pack's own vertex {@code out} list and map the <em>standard Iris terrain varyings</em> to
 * Nvidium's per-vertex data, writing safe defaults for anything we cannot meaningfully supply.
 *
 * <h2>Mapped varyings (populated from Nvidium vertex data)</h2>
 * <ul>
 *   <li>UV / texcoord (vec2/vec4): {@code decodeVertexUV(V)} (zw =&gt; 0,1)</li>
 *   <li>lightmap / lmcoord (vec2/vec4): {@code decodeLightUV(V)} (zw =&gt; 0,1)</li>
 *   <li>vertex colour / glcolor (vec3/vec4): {@code decodeVertexColour(V)} tinted by lightmap</li>
 *   <li>normal (vec3/vec4): face normal derived from the triangle (best-effort; see notes)</li>
 * </ul>
 *
 * <h2>Defaulted varyings (Nvidium cannot supply; safe constants)</h2>
 * Everything else the pack declares (tangents, {@code mc_Entity}, {@code mc_midTexCoord},
 * {@code at_midBlock}, view/world position vectors, custom pack varyings, ...) is written as a
 * type-appropriate zero/identity default so the program still LINKS and runs. Runtime visual
 * correctness for those is out of scope for this compile-gate task and is iterated at the
 * hardware gate.
 *
 * <p>This class performs NO GL calls and touches NO Iris symbols, so it is always safe to load.
 */
final class IrisVaryingMapper {
    private IrisVaryingMapper() {}

    /** A single parsed varying declaration from the pack vertex source. */
    record Varying(String type, String name) {}

    // out [qualifiers] <type> <name> ;   (single-name declarations; we skip blocks/arrays)
    private static final Pattern OUT_DECL = Pattern.compile(
            "(?m)^\\s*(?:flat\\s+|smooth\\s+|noperspective\\s+|centroid\\s+)*out\\s+" +
                    "(?:flat\\s+|smooth\\s+|noperspective\\s+|centroid\\s+)*" +
                    "(?<type>\\w+)\\s+(?<name>\\w+)\\s*;");

    /**
     * Extract the simple {@code out} varyings declared in the pack vertex source. Interface
     * blocks ({@code out Block { ... }}) and array varyings are intentionally skipped; the
     * common terrain varyings are all simple scalars/vectors. Unparseable sources yield an empty
     * list, which the caller treats as "only standard defaults".
     */
    static List<Varying> parseVertexOut(String vertexSource) {
        // Keep insertion order and dedupe by name (a pack may redeclare across #ifdef branches).
        Map<String, Varying> byName = new LinkedHashMap<>();
        if (vertexSource == null) {
            return new ArrayList<>();
        }
        String stripped = stripComments(vertexSource);
        Matcher m = OUT_DECL.matcher(stripped);
        while (m.find()) {
            String type = m.group("type");
            String name = m.group("name");
            // gl_* are builtins, never user varyings; ignore.
            if (name.startsWith("gl_")) continue;
            byName.putIfAbsent(name, new Varying(type, name));
        }
        return new ArrayList<>(byName.values());
    }

    /** Strip // and block comments so they don't trip the declaration regex. */
    private static String stripComments(String src) {
        // Block comments first, then line comments.
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    /**
     * Build the GLSL fragment to splice into Nvidium's mesh shader: the {@code out} varying
     * declarations plus a {@code nvidium$writeIrisVaryings} function that the mesh main loop calls
     * per emitted vertex. {@code outIndexExpr} is the GLSL expression for the mesh output vertex
     * index (e.g. {@code id} or {@code outId}). {@code vertexExpr} is the {@code Vertex} variable
     * expression, {@code worldPosExpr} the per-vertex world-space position {@code vec3}.
     */
    static String generateMeshVaryingGlsl(List<Varying> varyings) {
        StringBuilder decls = new StringBuilder();
        StringBuilder body = new StringBuilder();

        body.append("void nvidium$writeIrisVaryings(uint nvOutId, Vertex nvV, vec3 nvWorldPos, vec3 nvFaceNormal) {\n");

        int mapped = 0;
        List<String> defaulted = new ArrayList<>();

        // Declare each varying WITHOUT an explicit location so the GLSL linker auto-assigns; the
        // mesh outputs link to the fragment's matching `in` declarations by name + type. Mesh
        // per-vertex outputs are arrays indexed by the output vertex id.
        for (Varying v : varyings) {
            decls.append("out ").append(v.type()).append(" ").append(v.name()).append("[];\n");
        }

        for (Varying v : varyings) {
            String assigned = assignmentFor(v);
            if (assigned != null) {
                body.append("    ").append(v.name()).append("[nvOutId] = ").append(assigned).append(";\n");
                mapped++;
            } else {
                body.append("    ").append(v.name()).append("[nvOutId] = ").append(defaultFor(v.type()))
                        .append("; // nvidium: no source data, safe default\n");
                defaulted.add(v.type() + " " + v.name());
            }
        }
        body.append("}\n");

        if (!defaulted.isEmpty()) {
            Nvidium.LOGGER.info("Nvidium-Iris: {} pack varyings mapped, {} defaulted: {}",
                    mapped, defaulted.size(), String.join(", ", defaulted));
        }

        return decls.append(body).toString();
    }

    /**
     * Map a known standard-terrain varying name to a GLSL r-value built from Nvidium's per-vertex
     * data. Returns null if we have no meaningful source for this name (caller defaults it).
     *
     * <p>Names follow the conventional Iris/OptiFine terrain varying vocabulary. We match on the
     * lower-cased name so case variants (texCoord/texcoord) all hit.
     */
    private static String assignmentFor(Varying v) {
        String n = v.name().toLowerCase();
        boolean vec4 = v.type().equals("vec4");
        boolean vec3 = v.type().equals("vec3");
        boolean vec2 = v.type().equals("vec2");

        // Texture / atlas UV.
        if (n.equals("texcoord") || n.equals("texcoords") || n.equals("uv") || n.equals("coord0")
                || n.equals("vtexcoord")) {
            if (vec2) return "decodeVertexUV(nvV)";
            if (vec4) return "vec4(decodeVertexUV(nvV), 0.0, 1.0)";
        }
        // Lightmap UV.
        if (n.equals("lmcoord") || n.equals("lightmapcoord") || n.equals("lmcoords")
                || n.equals("coord1") || n.equals("vlightmap") || n.equals("light")) {
            if (vec2) return "decodeLightUV(nvV)";
            if (vec4) return "vec4(decodeLightUV(nvV), 0.0, 1.0)";
        }
        // Vertex colour (tinted by lightmap, matching Nvidium's computeMultiplier path).
        if (n.equals("glcolor") || n.equals("color") || n.equals("vertexcolor") || n.equals("vcolor")
                || n.equals("vcolour") || n.equals("glcolour")) {
            if (vec4) return "vec4(decodeVertexColour(nvV).rgb, 1.0)";
            if (vec3) return "decodeVertexColour(nvV).rgb";
        }
        // Normal (face normal; per-vertex normals are not stored by Nvidium's compact format).
        if (n.equals("normal") || n.equals("vnormal") || n.equals("worldnormal") || n.equals("viewnormal")) {
            if (vec3) return "nvFaceNormal";
            if (vec4) return "vec4(nvFaceNormal, 0.0)";
        }
        // World/eye-space position varyings some packs interpolate.
        if (n.equals("worldpos") || n.equals("vposition") || n.equals("position") || n.equals("ftvertex")) {
            if (vec3) return "nvWorldPos";
            if (vec4) return "vec4(nvWorldPos, 1.0)";
        }
        return null;
    }

    /** Type-appropriate safe default literal. */
    private static String defaultFor(String type) {
        switch (type) {
            case "float": return "0.0";
            case "vec2": return "vec2(0.0)";
            case "vec3": return "vec3(0.0)";
            case "vec4": return "vec4(0.0, 0.0, 0.0, 1.0)";
            case "int": return "0";
            case "uint": return "0u";
            case "ivec2": return "ivec2(0)";
            case "ivec3": return "ivec3(0)";
            case "ivec4": return "ivec4(0)";
            case "mat3": return "mat3(1.0)";
            case "mat4": return "mat4(1.0)";
            default: return type + "(0)"; // best-effort constructor
        }
    }
}
