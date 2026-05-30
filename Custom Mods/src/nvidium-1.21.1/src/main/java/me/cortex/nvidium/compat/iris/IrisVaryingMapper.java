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

    /**
     * How a varying gets its value, which decides whether it must travel as a per-vertex mesh
     * {@code out} or can be reconstructed cheaply inside the fragment.
     *
     * <ul>
     *   <li>{@link #PER_VERTEX} — the value genuinely varies per vertex (it is built from Nvidium's
     *       per-vertex inputs {@code nvV} / {@code nvWorldPos} / {@code nvFaceNormal}). MUST be a mesh
     *       {@code out} and written in {@link #WRITER_FN}; nothing else can supply it.</li>
     *   <li>{@link #CONSTANT_UNIFORM} — the value is the same for every vertex in the draw and is
     *       derived from a draw-constant uniform (the world-basis vectors, from {@code gbufferModelView}
     *       / {@code sunPosition}). Does NOT belong in the mesh output; it is reconstructed in the
     *       fragment from a uniform in an injected {@code main()} prologue (global initializers that
     *       reference uniforms are illegal GLSL).</li>
     *   <li>{@link #CONSTANT_LITERAL} — the value is a compile-time literal/identity default
     *       (zero/identity, e.g. {@code mat = 0}, {@code midCoord = vec2(0.0)}). Does NOT belong in
     *       the mesh output; it is reconstructed in the fragment as a global {@code const}.</li>
     * </ul>
     */
    enum Bucket { PER_VERTEX, CONSTANT_UNIFORM, CONSTANT_LITERAL }

    /**
     * A varying together with the GLSL r-value it should receive and which {@link Bucket} that
     * r-value puts it in. {@code expr} is the right-hand side as it would appear in the writer (for
     * {@link Bucket#PER_VERTEX}) or in the fragment-side reconstruction (for the two CONSTANT
     * buckets). For CONSTANT_UNIFORM the {@code expr} references the conventional Iris uniforms; for
     * CONSTANT_LITERAL it is a constant expression suitable for a global {@code const} initializer.
     */
    record Classified(Varying varying, Bucket bucket, String expr) {}

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

    /** The per-vertex Nvidium inputs the writer receives; an assignment that references any of these
     *  genuinely varies per vertex and so must travel as a mesh {@code out}. */
    private static final String[] PER_VERTEX_INPUTS = {"nvV", "nvWorldPos", "nvFaceNormal"};

    /** The shaderpack uniforms a draw-constant (per-draw) varying expression may read. An expression
     *  that references one of these (and no per-vertex input) is reconstructed in the fragment from
     *  that uniform in an injected {@code main()} prologue (a global initializer reading a uniform is
     *  illegal GLSL). Order matters only for the emitted declaration list. */
    static final String[] CONSTANT_UNIFORMS = {"gbufferModelView", "sunPosition"};

    /**
     * Classify every parsed fragment {@code in} into its {@link Bucket} and attach the GLSL r-value
     * it should receive. The classification is driven off the assignment expression the mapper
     * already produces (NOT a hardcoded name list):
     *
     * <ul>
     *   <li>If the expression references any of {@link #PER_VERTEX_INPUTS}
     *       ({@code nvV}/{@code nvWorldPos}/{@code nvFaceNormal}), it is {@link Bucket#PER_VERTEX} —
     *       it must be a mesh output.</li>
     *   <li>Else, if the expression references any of {@link #CONSTANT_UNIFORMS}, it is draw-constant
     *       and uniform-derived ({@link Bucket#CONSTANT_UNIFORM}) — reconstructed in the fragment
     *       from the uniform it reads, in a {@code main()} prologue.</li>
     *   <li>Otherwise the expression is a compile-time literal/identity default
     *       ({@link Bucket#CONSTANT_LITERAL}) — reconstructed in the fragment as a global
     *       {@code const}.</li>
     * </ul>
     *
     * <p>Note the expression is the unified source of truth: even a name that falls through
     * {@link #assignmentFor} to {@link #defaultFor} (e.g. {@code lightVec}, whose default reads
     * {@code gbufferModelView}) is correctly routed to CONSTANT_UNIFORM here rather than mis-emitted
     * as a {@code const} with an illegal uniform initializer.
     */
    static List<Classified> classify(List<Varying> varyings) {
        List<Classified> out = new ArrayList<>();
        for (Varying v : varyings) {
            String assigned = assignmentFor(v);
            String expr = assigned != null ? assigned : defaultFor(v.name(), v.type());
            Bucket bucket;
            if (referencesPerVertexInput(expr)) {
                bucket = Bucket.PER_VERTEX;
            } else if (referencesConstantUniform(expr)) {
                bucket = Bucket.CONSTANT_UNIFORM;
            } else {
                bucket = Bucket.CONSTANT_LITERAL;
            }
            out.add(new Classified(v, bucket, expr));
        }
        return out;
    }

    /** True iff the GLSL expression references one of Nvidium's per-vertex writer inputs as a
     *  whole identifier (word-boundary match, so a substring like {@code nvVx} would not falsely hit). */
    private static boolean referencesPerVertexInput(String expr) {
        for (String input : PER_VERTEX_INPUTS) {
            if (containsIdentifier(expr, input)) return true;
        }
        return false;
    }

    /** True iff the GLSL expression references one of the draw-constant shaderpack uniforms. */
    private static boolean referencesConstantUniform(String expr) {
        for (String u : CONSTANT_UNIFORMS) {
            if (containsIdentifier(expr, u)) return true;
        }
        return false;
    }

    /** Whole-identifier containment test: {@code needle} surrounded by non-identifier chars. */
    private static boolean containsIdentifier(String haystack, String needle) {
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return false;
            char before = idx == 0 ? ' ' : haystack.charAt(idx - 1);
            int after = idx + needle.length();
            char afterCh = after >= haystack.length() ? ' ' : haystack.charAt(after);
            if (!isIdentifierChar(before) && !isIdentifierChar(afterCh)) return true;
            from = idx + needle.length();
        }
    }

    private static boolean isIdentifierChar(char c) {
        return isAsciiAlphaOrUnderscore(c) || (c >= '0' && c <= '9');
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
     *
     * <p>Only {@link Bucket#PER_VERTEX} varyings are emitted as mesh outputs (and written in the
     * writer). Draw-constant varyings (uniform-derived basis vectors, literal/identity defaults) are
     * deliberately NOT emitted here: keeping them out of the per-meshlet output memory is exactly the
     * fix for blowing past {@code GL_MAX_MESH_TOTAL_MEMORY_SIZE_NV} (16 KB on NV). They are instead
     * reconstructed inside the fragment by
     * {@link #injectConstantVaryings(String, List)} so shading is identical.
     */
    static String generateMeshVaryingGlsl(List<Varying> varyings) {
        List<Classified> classified = classify(varyings);
        StringBuilder decls = new StringBuilder();
        StringBuilder body = new StringBuilder();

        // Only PER_VERTEX varyings cross the mesh->fragment boundary now.
        List<Classified> perVertex = new ArrayList<>();
        for (Classified c : classified) {
            if (c.bucket() == Bucket.PER_VERTEX) perVertex.add(c);
        }

        // Shaderpack-facing uniforms the per-vertex assignments read. These are the conventional
        // Iris names (NOT the iris_* aliases the Sodium fragment patcher rewrites); IrisProgramBridge
        // registers them on the program's ProgramUniforms via MatrixUniforms / CelestialUniforms so
        // they are actually fed each frame. We now declare ONLY the uniforms a PER_VERTEX assignment
        // actually references: with the basis vectors moved into the fragment, the mesh shader keeps
        // gbufferModelView (the view-space `normal` still needs mat3(gbufferModelView)) but no longer
        // needs sunPosition (only sunVec used it, and sunVec is now CONSTANT_UNIFORM -> fragment).
        // We emit a uniform only if some PER_VERTEX expr references it, so an unused uniform never
        // lingers.
        // NOTE on normal winding: nvFaceNormal is whatever Nvidium's per-call-site cross product
        // yields (world space); we transform it into view space but do NOT flip it. If the gate shows
        // inverted lighting on near faces, the winding at the mesh call sites is the lever to flip.
        boolean needsModelView = false;
        boolean needsSunPosition = false;
        for (Classified c : perVertex) {
            if (containsIdentifier(c.expr(), "gbufferModelView")) needsModelView = true;
            if (containsIdentifier(c.expr(), "sunPosition")) needsSunPosition = true;
        }
        if (needsModelView) decls.append("uniform mat4 gbufferModelView;\n");
        if (needsSunPosition) decls.append("uniform vec3 sunPosition;\n");

        body.append("void ").append(WRITER_FN)
                .append("(uint nvOutId, Vertex nvV, vec3 nvWorldPos, vec3 nvFaceNormal) {\n");

        // For the post-gen diagnostic: the full out interface, name:type:qualifier.
        List<String> outInterface = new ArrayList<>();

        // Declare each PER_VERTEX varying WITHOUT an explicit location so the GLSL linker
        // auto-assigns; the mesh outputs link to the fragment's matching `in` declarations by name +
        // type + interpolation qualifier. Mesh per-vertex outputs are unsized arrays indexed by the
        // output vertex id. The interpolation qualifier MUST match the fragment `in` exactly --
        // notably `flat` on integer varyings (an int varying without `flat` is itself an error).
        for (Classified c : perVertex) {
            Varying v = c.varying();
            String q = v.qualifier().isEmpty() ? "" : v.qualifier() + " ";
            decls.append(q).append("out ").append(v.type()).append(" ").append(v.name()).append("[];\n");
            outInterface.add(v.name() + ":" + v.type()
                    + ":" + (v.qualifier().isEmpty() ? "(none)" : v.qualifier()));
        }

        for (Classified c : perVertex) {
            body.append("    ").append(c.varying().name()).append("[nvOutId] = ").append(c.expr()).append(";\n");
        }
        body.append("}\n");

        // Tally the constant buckets for the diagnostic (these are now injected into the fragment).
        List<String> injectedUniform = new ArrayList<>();
        List<String> injectedLiteral = new ArrayList<>();
        for (Classified c : classified) {
            if (c.bucket() == Bucket.CONSTANT_UNIFORM) injectedUniform.add(c.varying().type() + " " + c.varying().name());
            else if (c.bucket() == Bucket.CONSTANT_LITERAL) injectedLiteral.add(c.varying().type() + " " + c.varying().name());
        }

        Nvidium.LOGGER.info("Nvidium-Iris: {} per-vertex varyings kept as mesh out; {} uniform-derived + {} literal-default varyings injected into fragment{}{}",
                perVertex.size(), injectedUniform.size(), injectedLiteral.size(),
                injectedUniform.isEmpty() ? "" : (" [uniform: " + String.join(", ", injectedUniform) + "]"),
                injectedLiteral.isEmpty() ? "" : (" [literal: " + String.join(", ", injectedLiteral) + "]"));
        // Always log the full generated out interface so the next (link) gate can diff it directly
        // against any "not declared as input from previous stage" error.
        Nvidium.LOGGER.info("Nvidium-Iris: generated mesh out interface ({} varyings): {}",
                outInterface.size(), String.join(", ", outInterface));

        return decls.append(body).toString();
    }

    /**
     * Transform the patched shaderpack FRAGMENT so the draw-constant varyings no longer arrive as
     * stage inputs (they are no longer emitted by the mesh shader) but are instead reconstructed
     * locally, producing identical shading at a fraction of the per-meshlet output memory.
     *
     * <p>For each {@link Bucket#CONSTANT_UNIFORM} / {@link Bucket#CONSTANT_LITERAL} varying:
     * <ol>
     *   <li>Its {@code in} declaration is removed from the fragment. Multi-variable declarations
     *       ({@code flat in vec3 upVec, sunVec, northVec, eastVec;}) are handled: if every name on the
     *       line is constant the whole declaration is dropped; if the line mixes buckets it is rewritten
     *       to keep only the names that remain mesh outputs (the PER_VERTEX ones).</li>
     *   <li>It is re-introduced as a fragment-scope value:
     *     <ul>
     *       <li>LITERAL: a global {@code const TYPE name = <constexpr>;} (valid — the initializer is a
     *           constant expression).</li>
     *       <li>UNIFORM-derived: a plain global {@code TYPE name;} (a global initializer that reads a
     *           uniform is illegal GLSL), assigned at the top of {@code main()} via an injected
     *           prologue ({@code name = <expr>;}).</li>
     *     </ul></li>
     *   <li>Any {@link #CONSTANT_UNIFORMS} actually referenced by an injected expression is declared
     *       as a {@code uniform} if the fragment does not already declare it (duplicate-guarded).</li>
     * </ol>
     *
     * <p>The PER_VERTEX varyings are left untouched as {@code in} declarations, so the mesh {@code out}
     * interface and the fragment {@code in} interface still match exactly. Returns the original source
     * unchanged if there is nothing to inject.
     */
    static String injectConstantVaryings(String fragmentSource, List<Varying> varyings) {
        if (fragmentSource == null) return null;
        List<Classified> classified = classify(varyings);

        // Partition.
        Map<String, Classified> constantByName = new LinkedHashMap<>();
        for (Classified c : classified) {
            if (c.bucket() != Bucket.PER_VERTEX) {
                constantByName.put(c.varying().name(), c);
            }
        }
        if (constantByName.isEmpty()) return fragmentSource;

        // 1. Remove (or rewrite) the `in` declarations of the constant varyings. We operate on the
        //    real source (comments preserved); we only need to recognize declarations, and the
        //    IN_DECL regex is anchored to line starts so it will not match inside a // comment unless
        //    that comment leads the line, which is harmless (commented-out decls are inert either way).
        StringBuilder sb = new StringBuilder();
        Matcher m = IN_DECL.matcher(fragmentSource);
        int last = 0;
        while (m.find()) {
            sb.append(fragmentSource, last, m.start());
            String interp = m.group("interp");      // may be null
            String type = m.group("type");
            String namesGroup = m.group("names");

            List<String> kept = new ArrayList<>();   // names NOT constant -> stay as `in`
            for (String raw : namesGroup.split(",")) {
                String name = raw.trim();
                if (!constantByName.containsKey(name)) {
                    kept.add(name);
                }
            }
            if (kept.isEmpty()) {
                // Whole declaration is constant -> drop it entirely (replace with nothing).
                // (Leaves the surrounding newline structure intact: m.start()..m.end() spanned only
                // the declaration text, not the trailing newline.)
            } else if (kept.size() == namesGroup.split(",").length) {
                // No constant names on this line -> keep verbatim.
                sb.append(m.group());
            } else {
                // Mixed line -> re-emit only the kept (PER_VERTEX) names with the same qualifier+type.
                String q = (interp == null || interp.trim().isEmpty()) ? "" : interp.trim() + " ";
                sb.append(q).append("in ").append(type).append(" ").append(String.join(", ", kept)).append(";");
            }
            last = m.end();
        }
        sb.append(fragmentSource, last, fragmentSource.length());
        String stripped = sb.toString();

        // 2. Build the injected declarations: uniforms (deduped), literal consts, uniform-derived
        //    globals, and the main() prologue assignments.
        StringBuilder topInject = new StringBuilder();

        // 2a. Uniform declarations, only for uniforms actually referenced by an injected expression
        //     and not already declared in the fragment (duplicate guard).
        for (String u : CONSTANT_UNIFORMS) {
            boolean referenced = false;
            for (Classified c : constantByName.values()) {
                if (c.bucket() == Bucket.CONSTANT_UNIFORM && containsIdentifier(c.expr(), u)) {
                    referenced = true;
                    break;
                }
            }
            if (referenced && !declaresUniform(stripped, u)) {
                topInject.append("uniform ").append(uniformTypeFor(u)).append(" ").append(u).append(";\n");
            }
        }

        // 2b. Literal-default consts and uniform-derived plain globals.
        StringBuilder prologue = new StringBuilder();
        for (Classified c : constantByName.values()) {
            Varying v = c.varying();
            if (c.bucket() == Bucket.CONSTANT_LITERAL) {
                topInject.append("const ").append(v.type()).append(" ").append(v.name())
                        .append(" = ").append(c.expr()).append(";\n");
            } else { // CONSTANT_UNIFORM
                topInject.append(v.type()).append(" ").append(v.name()).append(";\n");
                prologue.append("    ").append(v.name()).append(" = ").append(c.expr()).append(";\n");
            }
        }

        // 3. Splice: the top-injection goes right after the leading preprocessor preamble (the
        //    #version / #extension / #define block). It MUST land after the last #extension directive
        //    (GLSL requires all #extension directives to precede any non-preprocessor token) but
        //    before the first helper function, since those helpers reference the now-global varyings
        //    (a global must be declared before its first textual use) -- exactly where the original
        //    `in` declarations sat. The prologue goes right after `main(){`.
        String withTop = insertAfterPreprocessorPreamble(stripped, topInject.toString());
        String result = prologue.length() == 0 ? withTop : insertMainPrologue(withTop, prologue.toString());

        Nvidium.LOGGER.info("Nvidium-Iris: injected {} constant varyings into fragment ({} as const, {} as uniform-derived globals)",
                constantByName.size(),
                constantByName.values().stream().filter(c -> c.bucket() == Bucket.CONSTANT_LITERAL).count(),
                constantByName.values().stream().filter(c -> c.bucket() == Bucket.CONSTANT_UNIFORM).count());
        return result;
    }

    /** GLSL type of a known constant-uniform name (used to declare it in the fragment if absent). */
    private static String uniformTypeFor(String uniformName) {
        switch (uniformName) {
            case "gbufferModelView": return "mat4";
            case "sunPosition": return "vec3";
            default: return "vec4"; // unreachable for the known set
        }
    }

    /** True iff the source already declares {@code uniform <type> <name>} (any type) at top level,
     *  so we never emit a duplicate declaration. Matches a leading {@code uniform} line declaring the
     *  exact name (single- or multi-variable). */
    private static boolean declaresUniform(String src, String name) {
        // (?m) line mode; allow leading whitespace, the `uniform` keyword, a type word, then a
        // name list that contains `name` as a whole identifier, up to the `;`.
        Pattern p = Pattern.compile("(?m)^\\s*uniform\\s+\\w+\\s+[^;]*\\b"
                + Pattern.quote(name) + "\\b[^;]*;");
        return p.matcher(src).find();
    }

    /**
     * Insert {@code block} immediately after the fragment's leading preprocessor preamble: the
     * {@code #version} directive followed by any run of blank lines and preprocessor directives
     * ({@code #extension}, {@code #define}, {@code #include}, {@code #pragma}, ...). This is the
     * earliest point at which a real declaration is legal (all {@code #extension} directives must
     * precede any non-preprocessor token) while still being before every helper function that uses
     * the injected globals. If no {@code #version} is found the block is prepended (defensive; a
     * patched fragment always has one).
     */
    private static String insertAfterPreprocessorPreamble(String src, String block) {
        if (block == null || block.isEmpty()) return src;
        Matcher mv = Pattern.compile("(?m)^[ \\t]*#version[^\\n]*\\n").matcher(src);
        if (!mv.find()) {
            return block + src;
        }
        int at = mv.end();
        // Advance past any contiguous blank lines and preprocessor-directive lines. A directive line
        // begins (after optional leading whitespace) with '#'. Stop at the first line that is neither
        // blank nor a directive -- that is the start of the declaration section.
        while (at < src.length()) {
            int lineEnd = src.indexOf('\n', at);
            if (lineEnd < 0) lineEnd = src.length(); else lineEnd += 1; // include the newline
            String line = src.substring(at, lineEnd);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                at = lineEnd;
            } else {
                break;
            }
        }
        return src.substring(0, at) + block + src.substring(at);
    }

    /** Insert {@code prologue} immediately after the opening brace of {@code main()}. Robust to
     *  spacing variants ({@code void main() {}, {@code void main(){}, {@code void main( void ) {}). */
    private static String insertMainPrologue(String src, String prologue) {
        if (prologue == null || prologue.isEmpty()) return src;
        // Match `main` then `(...)` then the opening `{`, tolerating arbitrary whitespace and an
        // optional `void` parameter. Capture up to and including the brace so we can splice after it.
        Matcher m = Pattern.compile("\\bmain\\s*\\([^)]*\\)\\s*\\{").matcher(src);
        if (m.find()) {
            int at = m.end();
            return src.substring(0, at) + "\n" + prologue + src.substring(at);
        }
        // Could not find main() -> return unchanged; the prologue assignments are then missing and
        // the fragment will fail to compile referencing unassigned globals, which surfaces clearly at
        // the compile gate. (A patched terrain fragment always has a main().)
        Nvidium.LOGGER.warn("Nvidium-Iris: could not locate main() to inject constant-varying prologue; fragment left unmodified for prologue");
        return src;
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

    /**
     * Generate the {@code in} varying declarations for a debug fragment that links against the mesh
     * stage's {@code out} interface. The generated block mirrors the mesh {@code out} declarations
     * produced by {@link #generateMeshVaryingGlsl} exactly (same qualifier, type, name) but uses
     * {@code in} instead of {@code out []}, so the debug fragment compiles against the same interface
     * the pack fragment would see.
     *
     * <p>Returns a GLSL string of the form:
     * <pre>{@code
     * flat in int mc_Entity;
     * in vec2 texCoord;
     * ...
     * }</pre>
     *
     * <p>Only the {@link Bucket#PER_VERTEX} varyings are declared, because those are the only ones
     * the mesh shader still emits as {@code out}; declaring a constant varying here that the mesh no
     * longer outputs would be a hard link error. The debug modes (normal/pos/uv/lm) all reference
     * per-vertex varyings, so they remain functional.
     */
    static String generateDebugFragmentInDecls(List<Varying> varyings) {
        StringBuilder sb = new StringBuilder();
        for (Classified c : classify(varyings)) {
            if (c.bucket() != Bucket.PER_VERTEX) continue;
            Varying v = c.varying();
            String q = v.qualifier().isEmpty() ? "" : v.qualifier() + " ";
            sb.append(q).append("in ").append(v.type()).append(" ").append(v.name()).append(";\n");
        }
        return sb.toString();
    }

    /** Return true if the given name (case-insensitive) appears in the varying list. */
    static boolean hasVarying(List<Varying> varyings, String name) {
        String lower = name.toLowerCase();
        for (Varying v : varyings) {
            if (v.name().toLowerCase().equals(lower)) return true;
        }
        return false;
    }

    /**
     * Return the actual case-preserved name of a varying matching the given lowercase name, or null.
     * Used so the debug fragment references the exact identifier that was declared.
     */
    static String findVaryingName(List<Varying> varyings, String lowerName) {
        for (Varying v : varyings) {
            if (v.name().toLowerCase().equals(lowerName)) return v.name();
        }
        return null;
    }

    /**
     * Return the full {@link Varying} record whose name matches the given lowercase name, or null.
     * Useful when both the name AND the type are needed (e.g. to generate a type-safe color expression
     * in the debug fragment without extra over-constructor arguments).
     */
    static Varying findVarying(List<Varying> varyings, String lowerName) {
        for (Varying v : varyings) {
            if (v.name().toLowerCase().equals(lowerName)) return v;
        }
        return null;
    }

    /**
     * Return the number of scalar components in a GLSL type used by terrain varyings.
     * Used by {@link IrisProgramBridge} to estimate per-vertex output memory footprint.
     * Returns 1 for unknown types (conservative single-component estimate).
     */
    static int componentCount(String type) {
        if (type == null) return 1;
        switch (type) {
            case "float": case "int": case "uint": case "bool": return 1;
            case "vec2": case "ivec2": case "uvec2": return 2;
            case "vec3": case "ivec3": case "uvec3": return 3;
            case "vec4": case "ivec4": case "uvec4": return 4;
            case "mat3": return 9;
            case "mat4": return 16;
            default: return 1;
        }
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
