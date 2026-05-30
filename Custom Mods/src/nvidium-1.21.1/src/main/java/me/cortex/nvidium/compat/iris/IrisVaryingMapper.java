package me.cortex.nvidium.compat.iris;

import me.cortex.nvidium.Nvidium;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code in} (varying) declarations of a shaderpack's Iris-patched terrain FRAGMENT
 * source and produces the GLSL that Nvidium's mesh shader must emit so its per-vertex {@code out}
 * interface lines up, by name + type + interpolation qualifier, with the inputs the fragment reads.
 *
 * <p>This is the crux of the integration (findings Q4). A mesh shader replaces the conventional
 * vertex stage entirely, so it must declare and write <em>exactly</em> the varyings the fragment
 * consumes. The fragment is the authoritative consumer contract: a mesh {@code out} that is missing
 * any fragment {@code in} (or whose qualifier/type differs) is a hard link error
 * ({@code "<name>" not declared as input from previous stage}). So we drive generation from the
 * fragment's {@code in} set, not the vertex's {@code out} set, and map the <em>standard Iris
 * terrain varyings</em> to Nvidium's per-vertex data, writing safe type-correct defaults for
 * anything we cannot meaningfully supply yet.
 *
 * <h2>Mapped varyings (populated from Nvidium vertex data)</h2>
 * <ul>
 *   <li>UV / texcoord (vec2/vec4): {@code decodeVertexUV(V)} (zw =&gt; 0,1)</li>
 *   <li>lightmap / lmcoord (vec2/vec4): {@code decodeLightUV(V)} (zw =&gt; 0,1)</li>
 *   <li>vertex colour / glcolor / glColorRaw (vec3/vec4): {@code decodeVertexColour(V)}</li>
 *   <li>normal (vec3/vec4): view-space normal = {@code normalize(mat3(gbufferModelView) * nvFaceNormal)}
 *       (Nvidium's world-space face normal rotated into view space, matching the pack)</li>
 *   <li>upVec/eastVec/northVec (vec3): view-space world-axis basis = the columns of
 *       {@code gbufferModelView} (rows 1/0/2), exactly as Complementary's dh_terrain derives them</li>
 *   <li>sunVec (vec3): {@code normalize(sunPosition)} (view-space sun direction)</li>
 *   <li>vertexPos / playerPos / position (vec3/vec4): the eye-relative world position
 *       ({@code nvWorldPos}, supplied camera-relative by the mesh call site)</li>
 * </ul>
 *
 * <h2>Defaulted varyings (Nvidium cannot supply; safe constants)</h2>
 * Everything else the fragment declares is written as a type-appropriate / name-appropriate
 * zero/identity default so the program still LINKS and renders. Intentionally still defaulted this
 * pass (no Nvidium source): POM data ({@code mat}, {@code signMidCoordPos}, {@code absMidCoordPos},
 * {@code midCoord}) and water tangent-space ({@code tangent}, {@code binormal}, {@code viewVector}).
 * The basis vectors / normal / position are now COMPUTED from the gbuffer uniforms (above), no
 * longer placeholder constants.
 *
 * <p>This class performs NO GL calls and touches NO Iris symbols, so it is always safe to load.
 */
final class IrisVaryingMapper {
    private IrisVaryingMapper() {}

    /**
     * A single parsed varying. {@code qualifier} is the interpolation qualifier exactly as it
     * appeared on the fragment {@code in} ({@code ""}, {@code "flat"}, {@code "centroid"},
     * {@code "noperspective"}, {@code "smooth"}); it MUST be reproduced verbatim on the mesh
     * {@code out} or the program will fail to link on a qualifier mismatch.
     */
    record Varying(String qualifier, String type, String name) {}

    // Match a top-level varying-input declaration:
    //   [interp...] in TYPE name[, name2, name3...] ;
    // Anchored at line start (multiline). The leading word MUST be exactly `in` (the `\b` after
    // `in` prevents matching `inout`/`int`/etc. used as function-parameter qualifiers, because
    // there is no word boundary between `in` and a following word char like the `o` in `inout`).
    // The trailing `;` (vs `,`/`)`) further distinguishes a real declaration from a multi-line
    // function parameter such as `inout vec3 color,`.
    private static final Pattern IN_DECL = Pattern.compile(
            "(?m)^\\s*" +
                    "(?<interp>(?:flat|smooth|noperspective|centroid)(?:\\s+(?:flat|smooth|noperspective|centroid))*\\s+)?" +
                    "in\\b\\s+" +
                    "(?<type>\\w+)\\s+" +
                    "(?<names>\\w+(?:\\s*,\\s*\\w+)*)\\s*;");

    /**
     * Extract the simple {@code in} varyings declared at top level in the (patched) fragment
     * source. Interface blocks ({@code in Block { ... }}) and array varyings are intentionally
     * skipped; the standard terrain varyings are all simple scalars/vectors. A single declaration
     * may name several varyings ({@code flat in vec3 a, b, c;}) -- each is emitted separately with
     * the shared qualifier + type. Unparseable sources yield an empty list, which the caller
     * treats as "only standard defaults".
     */
    static List<Varying> parseFragmentIn(String fragmentSource) {
        // Keep insertion order and dedupe by name (a pack may redeclare across #ifdef branches).
        Map<String, Varying> byName = new LinkedHashMap<>();
        if (fragmentSource == null) {
            return new ArrayList<>();
        }
        String stripped = stripComments(fragmentSource);
        Matcher m = IN_DECL.matcher(stripped);
        while (m.find()) {
            String interp = normalizeInterp(m.group("interp"));
            String type = m.group("type");
            String names = m.group("names");
            for (String rawName : names.split(",")) {
                String name = rawName.trim();
                // gl_* are builtins, never user varyings; ignore.
                if (name.startsWith("gl_")) continue;
                // Defensive: only emit GLSL-legal identifiers. The regex already restricts to \w+,
                // but a leading digit would make the spliced declaration illegal GLSL, so drop
                // those (and anything else non-conforming) rather than poison the whole program.
                if (!isLegalGlslIdentifier(name)) continue;
                byName.putIfAbsent(name, new Varying(interp, type, name));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Collapse the captured interpolation prefix to a single canonical token (or ""). */
    private static String normalizeInterp(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        return t;
    }

    /** A GLSL identifier is {@code [A-Za-z_][A-Za-z0-9_]*} (ASCII only; no {@code $}). */
    private static boolean isLegalGlslIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!isAsciiAlphaOrUnderscore(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isAsciiAlphaOrUnderscore(c) && !(c >= '0' && c <= '9')) return false;
        }
        return true;
    }

    private static boolean isAsciiAlphaOrUnderscore(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    /** Strip // and block comments so they don't trip the declaration regex. */
    private static String stripComments(String src) {
        // Block comments first, then line comments.
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    /**
     * Name of the generated writer function the mesh shaders call per emitted vertex. MUST stay in
     * sync with the call sites in the {@code mesh.glsl} / {@code translucent/mesh.glsl} assets.
     *
     * <p>GLSL identifiers may only contain {@code [A-Za-z0-9_]} and must not contain {@code $}
     * (the Java/mixin convention). Using {@code $} here is what made the NVIDIA GLSL compiler
     * choke with {@code "void type not allowed"} / {@code unexpected $undefined}, so the generated
     * name uses {@code _} instead.
     */
    static final String WRITER_FN = "nvidium_writeIrisVaryings";

    /**
     * Build the GLSL fragment to splice into Nvidium's mesh shader: the {@code out} varying
     * declarations plus a {@link #WRITER_FN} function that the mesh main loop calls per emitted
     * vertex. The function takes the output vertex id, the {@code Vertex}, the per-vertex
     * world-space position {@code vec3}, and a best-effort face-normal {@code vec3}.
     */
    static String generateMeshVaryingGlsl(List<Varying> varyings) {
        StringBuilder decls = new StringBuilder();
        StringBuilder body = new StringBuilder();

        // Shaderpack-facing uniforms the computed varyings read. These are the conventional Iris
        // names (NOT the iris_* aliases the Sodium fragment patcher rewrites); IrisProgramBridge
        // registers them on the program's ProgramUniforms via MatrixUniforms / CelestialUniforms so
        // they are actually fed each frame (gbufferModelView from CapturedRenderingState, sunPosition
        // from the celestial model). They are declared here, inside the spliced IRIS_PASS block, so
        // they exist exactly when the writer needs them and never collide with Nvidium's own globals.
        // NOTE on normal winding: nvFaceNormal is whatever Nvidium's per-call-site cross product
        // yields (world space); we transform it into view space but do NOT flip it. If the gate shows
        // inverted lighting on near faces, the winding at the mesh call sites is the lever to flip.
        decls.append("uniform mat4 gbufferModelView;\n");
        decls.append("uniform vec3 sunPosition;\n");

        body.append("void ").append(WRITER_FN)
                .append("(uint nvOutId, Vertex nvV, vec3 nvWorldPos, vec3 nvFaceNormal) {\n");

        int mapped = 0;
        List<String> defaulted = new ArrayList<>();
        // For the post-gen diagnostic: the full out interface, name:type:qualifier.
        List<String> outInterface = new ArrayList<>();

        // Declare each varying WITHOUT an explicit location so the GLSL linker auto-assigns; the
        // mesh outputs link to the fragment's matching `in` declarations by name + type +
        // interpolation qualifier. Mesh per-vertex outputs are unsized arrays indexed by the
        // output vertex id. The interpolation qualifier MUST match the fragment `in` exactly --
        // notably `flat` on integer varyings (an int varying without `flat` is itself an error).
        for (Varying v : varyings) {
            String q = v.qualifier().isEmpty() ? "" : v.qualifier() + " ";
            decls.append(q).append("out ").append(v.type()).append(" ").append(v.name()).append("[];\n");
            outInterface.add(v.name() + ":" + v.type()
                    + ":" + (v.qualifier().isEmpty() ? "(none)" : v.qualifier()));
        }

        for (Varying v : varyings) {
            String assigned = assignmentFor(v);
            if (assigned != null) {
                body.append("    ").append(v.name()).append("[nvOutId] = ").append(assigned).append(";\n");
                mapped++;
            } else {
                body.append("    ").append(v.name()).append("[nvOutId] = ").append(defaultFor(v.name(), v.type()))
                        .append("; // nvidium: no source data, safe default\n");
                defaulted.add(v.type() + " " + v.name());
            }
        }
        body.append("}\n");

        Nvidium.LOGGER.info("Nvidium-Iris: {} fragment in varyings mapped, {} defaulted{}",
                mapped, defaulted.size(),
                defaulted.isEmpty() ? "" : (": " + String.join(", ", defaulted)));
        // Always log the full generated out interface so the next (link) gate can diff it directly
        // against any "not declared as input from previous stage" error.
        Nvidium.LOGGER.info("Nvidium-Iris: generated mesh out interface ({} varyings): {}",
                outInterface.size(), String.join(", ", outInterface));

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
        // Vertex colour. Complementary names this glColorRaw / glColor; OptiFine packs use
        // gl_Color / vaColor etc. We supply the raw decoded colour (alpha = 1).
        if (n.equals("glcolor") || n.equals("glcolorraw") || n.equals("color") || n.equals("colour")
                || n.equals("vertexcolor") || n.equals("vcolor") || n.equals("vcolour")
                || n.equals("glcolour") || n.equals("vacolor")) {
            if (vec4) return "vec4(decodeVertexColour(nvV).rgb, 1.0)";
            if (vec3) return "decodeVertexColour(nvV).rgb";
        }
        // World-basis vectors. Complementary's dh_terrain (the analogous external-terrain handler)
        // derives these straight from the gbuffer model-view: upVec/eastVec/northVec are the
        // (normalized) VIEW-space images of the world +Y/+X/+Z axes, i.e. the columns of
        // gbufferModelView; sunVec is the view-space sun direction. We supply GetSunVector's
        // equivalent with normalize(sunPosition) (Iris' sunPosition is the sun position transformed
        // into VIEW space, same basis as the upVec/eastVec/northVec here, so the lighting dot
        // products SdotU = dot(sunVec, upVec) etc. stay in the right frame).
        if (vec3) {
            if (n.equals("upvec")) return "normalize(gbufferModelView[1].xyz)";
            if (n.equals("eastvec")) return "normalize(gbufferModelView[0].xyz)";
            if (n.equals("northvec")) return "normalize(gbufferModelView[2].xyz)";
            if (n.equals("sunvec")) return "normalize(sunPosition)";
            // Moon vector is antiparallel to the sun in Iris' celestial model.
            if (n.equals("moonvec")) return "normalize(-sunPosition)";
        }
        // Normal. Nvidium hands us a WORLD-space face normal (nvFaceNormal); the pack's terrain
        // fragment expects the conventional Iris VIEW-space normal (Complementary's dh_terrain does
        // `normal = normalize(gl_NormalMatrix * gl_Normal)`). For terrain gl_NormalMatrix is the
        // inverse-transpose of the model-view; since a face normal is unit-length and the model-view
        // is (near-)orthonormal, transforming by mat3(gbufferModelView) matches it. The winding of
        // nvFaceNormal is whatever Nvidium's cross product yields at the call site; it is preserved
        // here (no negation) -- see NOTE in generateMeshVaryingGlsl for the winding caveat.
        if (n.equals("normal") || n.equals("vnormal") || n.equals("worldnormal") || n.equals("viewnormal")) {
            if (vec3) return "normalize(mat3(gbufferModelView) * nvFaceNormal)";
            if (vec4) return "vec4(normalize(mat3(gbufferModelView) * nvFaceNormal), 0.0)";
        }
        // World/eye-space position varyings packs interpolate. Complementary's dh_terrain uses
        // `playerPos = (gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex).xyz`, i.e. the
        // EYE-RELATIVE WORLD position (world coordinates with the camera at the origin; the fragment
        // reconstructs absolute world as playerPos + cameraPosition and uses length(playerPos) for
        // distance). nvWorldPos is already that eye-relative world position (the call site applies
        // the region transform and the subchunk/camera offset before passing it in).
        if (n.equals("worldpos") || n.equals("vertexpos") || n.equals("vposition") || n.equals("position")
                || n.equals("ftvertex") || n.equals("scenepos") || n.equals("playerpos")) {
            if (vec3) return "nvWorldPos";
            if (vec4) return "vec4(nvWorldPos, 1.0)";
        }
        return null;
    }

    /**
     * Name- then type-appropriate safe default literal for a varying we can't supply from Nvidium
     * data. The world-basis vectors (upVec/eastVec/northVec/sunVec) and the normal/position are now
     * computed in {@link #assignmentFor} from the gbuffer uniforms, so they no longer reach here.
     * What remains genuinely unavailable from Nvidium's compact vertex format and is intentionally
     * left defaulted this pass: POM data ({@code mat}, {@code signMidCoordPos}, {@code absMidCoordPos},
     * {@code midCoord}) and water tangent-space ({@code tangent}, {@code binormal}, {@code viewVector}).
     * {@code lightVec} (used by some packs as the sun-or-moon direction) is a reasonable view-space
     * up default; everything else falls back to a type-appropriate zero/identity.
     */
    private static String defaultFor(String name, String type) {
        String n = name.toLowerCase();
        if (type.equals("vec3")) {
            // Day/night light direction; absent the time-of-day branch, the view-space up axis keeps
            // dot(lightVec, upVec) finite and roughly sane.
            if (n.equals("lightvec")) return "normalize(gbufferModelView[1].xyz)";
        }
        return zeroFor(type);
    }

    /** Type-appropriate zero/identity literal. */
    private static String zeroFor(String type) {
        switch (type) {
            case "float": return "0.0";
            case "vec2": return "vec2(0.0)";
            case "vec3": return "vec3(0.0)";
            case "vec4": return "vec4(0.0, 0.0, 0.0, 1.0)";
            case "int": return "0";
            case "uint": return "0u";
            case "bool": return "false";
            case "ivec2": return "ivec2(0)";
            case "ivec3": return "ivec3(0)";
            case "ivec4": return "ivec4(0)";
            case "uvec2": return "uvec2(0u)";
            case "uvec3": return "uvec3(0u)";
            case "uvec4": return "uvec4(0u)";
            case "mat3": return "mat3(1.0)";
            case "mat4": return "mat4(1.0)";
            default: return type + "(0)"; // best-effort constructor
        }
    }
}
