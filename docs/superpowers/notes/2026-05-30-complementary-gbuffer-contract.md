# Complementary Unbound terrain gbuffer contract vs Nvidium — why terrain renders BLACK

Read-only analysis, 2026-05-30. No code changed. Goal: explain the black terrain under
Complementary Unbound r5.7.1 and give the exact GLSL fix.

## TL;DR — #1 cause of the black

**The solid/cutout mesh shader computes a degenerate (NaN) face normal for 2 of every 3
triangle vertices.**

`<proj>/src/main/resources/assets/nvidium/shaders/terrain/mesh.glsl:99-115` computes the
normal *per emitted vertex* as:

```glsl
void putVertex(uint id, Vertex V) {
    vec3 nvLocalPos = decodeVertexPosition(V) + origin;   // <-- the vertex being emitted
    vec3 p0 = decodeVertexPosition(V0) + origin;          // common vertex 0
    vec3 p2 = decodeVertexPosition(V2) + origin;          // common vertex 2
    vec3 nvFaceNormal = normalize(cross(p2 - p0, nvLocalPos - p0));
    ...
    nvidium_writeIrisVaryings(id, V, nvEyeWorldPos, nvFaceNormal);
}
```

`putVertex` is called once per emitted vertex with `V` = that vertex (mesh.glsl:196, 201):
- Emitting **V0**: `nvLocalPos == p0` ⇒ `cross(p2-p0, 0) = vec3(0)` ⇒ `normalize(vec3(0)) = NaN`.
- Emitting **V2**: `nvLocalPos == p2` ⇒ `cross(p2-p0, p2-p0) = vec3(0)` ⇒ `NaN`.
- Only the unique vertex (V1/V3) gets a finite normal.

`normal` is a smooth (perspective-interpolated) `out vec3`. With 2 of 3 corners NaN, the
interpolated per-fragment normal is NaN almost everywhere. In `DoLighting`
(`shaders/lib/lighting/mainLighting.glsl`) every `dot(normalM, ...)` becomes NaN; the final
line is:

```glsl
finalDiffuse = sqrt(max(finalDiffuse, vec3(0.0)));   // max(NaN,0) -> 0 on NV
color.rgb *= finalDiffuse;                            // -> 0 -> BLACK
```

On NVIDIA `max(NaN, 0.0)` returns `0.0`, so `finalDiffuse` collapses to 0 and albedo is
multiplied to black. Geometry is unaffected because `gl_Position` comes from `pV`
(independent of the normal) — exactly the observed symptom (correct geometry, black shading).

**Proof it's path-specific:** the TRANSLUCENT mesh shader already does it correctly
(`<proj>/.../terrain/translucent/mesh.glsl:77-80`):

```glsl
vec3 q0 = decodeVertexPosition(terrainData[vertexBaseId + 0]) + originAndBaseData.xyz;
vec3 q1 = decodeVertexPosition(terrainData[vertexBaseId + 1]) + originAndBaseData.xyz;
vec3 q2 = decodeVertexPosition(terrainData[vertexBaseId + 2]) + originAndBaseData.xyz;
vec3 nvFaceNormal = normalize(cross(q1 - q0, q2 - q0));   // ONE flat normal, all 4 verts
```

This is the reference fix for the solid/cutout path.

---

## Important framing correction: Complementary terrain is FORWARD-lit in the gbuffer pass

The task brief calls Complementary "deferred", and its *post* stack is — but the terrain
**gbuffer** programs do the full lighting themselves and write the **final lit color** to
colortex0. There is no separate deferred relight of terrain albedo. So "black" must come from
the terrain fragment's own math (albedo × `finalDiffuse`), which is exactly the NaN-normal
collapse above. This is why fixing the normal is decisive rather than cosmetic.

The bridge resolves `ProgramId.TerrainSolid/TerrainCutout/Water`
(`IrisProgramBridge.programIdFor`, lines 534-539) → these are
**`gbuffers_terrain.glsl`** (and the water variant), NOT `dh_terrain.glsl`. `dh_terrain` is
the closest analog (external non-Sodium geometry) but is NOT the program in use; both are
analyzed below.

---

## Q1. Normal: space, encoding, fragment gbuffer write

**Space the fragment expects:** view space. `gbuffers_terrain.glsl` vertex half:
`normal = normalize(gl_NormalMatrix * gl_Normal);` (`gl_NormalMatrix` = inverse-transpose of
the model-view). `dh_terrain.glsl` vertex half is identical:
`normal = normalize(gl_NormalMatrix * gl_Normal);`. The fragment renormalizes implicitly only
via the basis dots; it expects a unit-length **flat** view-space face normal (no normal
mapping unless `CUSTOM_PBR`/`GENERATED_NORMALS` are enabled).

**Encoding into the gbuffer:** there is NO `EncodeNormal`-into-colortex for the lit terrain
output. `gbuffers_terrain.glsl` consumes the normal entirely inside `DoLighting` and writes
**lit color** to colortex0. The only normal that is *written* to a render target is an
optional reflection-normal under a feature flag (see Q2):
`gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);` — world-space, raw
(no octahedral encode), only when `BLOCK_REFLECT_QUALITY >= 2 && RP_MODE != 0`.

**Is Nvidium's space right?** Yes. `normalize(mat3(gbufferModelView) * nvFaceNormal)`
(IrisVaryingMapper.assignmentFor, line 604) is the correct view-space transform: a unit
face normal under a near-orthonormal model-view is transformed the same by
`mat3(gbufferModelView)` as by `gl_NormalMatrix` (inverse-transpose). The SPACE and the
TRANSFORM are correct.

**Winding/sign:** the transform sign is fine; the problem is purely the **degenerate input
`nvFaceNormal`** (Q1 TL;DR). Once the solid path computes one flat normal from three distinct
quad corners (mirroring translucent's `cross(q1-q0, q2-q0)`), the sign matches Sodium's
front-facing CCW quad winding (0,1,2,3) and therefore matches `gl_Normal`'s outward
convention. The translucent path already proves this winding is correct in practice.

---

## Q2. Full gbuffer OUTPUT contract for terrain (`gbuffers_terrain.glsl` fragment)

End of `main()`:

```glsl
/* DRAWBUFFERS:06 */
gl_FragData[0] = color;                                   // colortex0: FINAL LIT albedo color
gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0); // colortex6: material/PBR aux

#if BLOCK_REFLECT_QUALITY >= 2 && RP_MODE != 0
    /* DRAWBUFFERS:064 */
    gl_FragData[2] = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0); // colortex4: world normal
#endif
```

- **colortex0** (`gl_FragData[0]`): the fully shaded color = `texture2D(tex, texCoord).rgb *
  glColor.rgb`, then `* finalDiffuse` inside `DoLighting`. **This is what goes black.**
- **colortex6** (`gl_FragData[1]`): `smoothnessD` (0 for default blocks), `materialMask` (0),
  `skyLightFactor` (from `lmCoordM` + shadow). Nvidium leaves smoothnessD/materialMask at the
  shader defaults (0/0) which is fine; `skyLightFactor` derives from the lightmap varying.
- **colortex4** (`gl_FragData[2]`, conditional): world-space `normalM`. Only emitted with
  reflections on; would be NaN-corrupted by the same normal bug, degrading SSR — but is NOT
  the primary black cause.

`dh_terrain.glsl` is simpler: `/* DRAWBUFFERS:0 */ gl_FragData[0] = color;` (lit color only).

**Ranking missing/wrong outputs by black impact:**
1. **colortex0 lit color (BLACK)** — caused by NaN normal. The whole bug.
2. colortex4 reflection normal (minor) — only with reflections; SSR artifacts, not black.
3. colortex6 material aux (minor) — defaults are benign; at worst slightly wrong specular.
Nvidium does NOT need to add outputs — the patched fragment already writes them; it just
needs to feed a valid normal.

---

## Q3. Albedo path — sampler unit confirmed CORRECT

Fragment: `vec4 color = texture2D(tex, texCoord);` then (non-colorwheel branch)
`color.rgb *= glColor.rgb;`. Sampler is **`tex`** (aliases `texture`/`gtexture`), NOT a unit
literal in source — Iris maps it.

Iris mapping (`claude-reference/.../samplers/IrisSamplers.java:180-193`):
```java
public static final int ALBEDO_TEXTURE_UNIT = 0;   // line 25
public static final int LIGHTMAP_TEXTURE_UNIT = 2; // line 27
...
samplers.addExternalSampler(ALBEDO_TEXTURE_UNIT, "tex", "texture", "gtexture"); // unit 0
samplers.addExternalSampler(LIGHTMAP_TEXTURE_UNIT, "lightmap");                  // unit 2
```
`addExternalSampler` = Iris maps the sampler uniform to the unit but does NOT bind a texture;
the caller must bind the real texture there. Nvidium does exactly that
(`IrisGbufferBinder.java:69-79`): block atlas → unit 0, lightmap → unit 2. **This matches the
Sodium reference path** (`SodiumShader.bindTextures()` lines 177-181 bind 0 and 2 identically;
`buildSamplers` calls `addGbufferOrShadowSamplers(..., hasTexture=true, hasLightmap=true)`).
The bridge uses the same `addGbufferOrShadowSamplers(... true, true, false)` at
`IrisProgramBridge.java:263-264`.

`glColor` = `vec4(decodeVertexColour(nvV).rgb, 1.0)` (mapper line 578). `decodeVertexColour`
returns `vec4(rgb, 1)` already. So `color.rgb *= glColor.rgb` applies the right tint and the
alpha is a benign 1.0. **Albedo is NOT the cause of black** (a fully-white albedo would still
go black through the NaN-normal multiply). Sampler unit binding is correct.

---

## Q4. Lightmap + `mat` material id

**Lightmap:** `lmCoord` is a varying (interpolated `vec2`), fed by `decodeLightUV(nvV)` (mapper
line 570). `DoLighting` consumes `lmCoord` *directly as a coordinate* for smooth lighting
(`lightmap.x` → blocklight curve, `lightmap.y` → sky/ambient). It does NOT sample the
`lightmap` texture for terrain shading here — so the unit-2 lightmap binding is not what
drives terrain brightness (it matters for other passes / `tex_light` is Nvidium's own path).
A correct `lmCoord` gives daytime sky lighting; even `lmCoord=0` outdoors at night falls back
to `GetMinimumLighting` (small but nonzero with cave lighting/night vision). So lightmap is
NOT the black cause.

**`mat` material id:** `flat in int mat`; Nvidium defaults it to `0`. In
`gbuffers_terrain.glsl` the material branches are gated `if (mat >= 10000)` (IPBR) or compared
to literals ≥ 10001 (non-IPBR). `mat == 0` falls through ALL branches → treated as a plain
default block. **Safe.** It does NOT cause black or a special-material misread. (`mat=0` is
not equal to any special id like 10001 "no directional shading", 10005 foliage, 10009 leaves,
etc.) Normally `mat` is derived in-shader from `mc_Entity.x` (`mat = int(mc_Entity.x + 0.5)`),
which Nvidium has no source for; `0` is the correct safe default. No change needed for black.

---

## Q5. Cutout alpha / foliage speckle

`gbuffers_terrain.glsl` fragment: `if (color.a <= 0.00001) discard;` (unconditional, label
6WIR4HT23) — discards fully-transparent texels. The hard cutout threshold for foliage is
applied by Iris's injected **alpha test**, configured per pass in the bridge
(`IrisProgramBridge.java:128-129`): cutout → `AlphaTests.ONE_TENTH_ALPHA` (alpha < 0.1
discard), solid → `ALWAYS`, water → pack override. Iris patches the test into the fragment
during `TransformPatcher.patchSodium`. This relies on the atlas alpha channel being sampled
(`tex`, unit 0) — which is correct (Q3). So cutouts should work once albedo+normal are right;
the "speckled overlay" is consistent with the alpha test being present but every fragment
shading to ~black, making the discard pattern the only visible structure. No dedicated
alpha-test change is needed beyond what the bridge already configures; just ensure cutout pass
keeps `ONE_TENTH_ALPHA` (it does).

---

## Q6. PRIORITIZED FIX LIST (most-likely-to-fix-black first)

1. **Fix the degenerate face normal in the SOLID/CUTOUT mesh shader.**
   File: `<proj>/src/main/resources/assets/nvidium/shaders/terrain/mesh.glsl`, `putVertex`
   (lines 110-113). Replace the per-vertex `cross(p2-p0, nvLocalPos-p0)` (which is `vec3(0)`
   for the two common vertices) with ONE flat face normal computed from three DISTINCT quad
   corners, exactly like the translucent path. The quad's four verts are
   `terrainData[(quadId<<2)+{0,1,2,3}]`; use corners 0,1,2:
   ```glsl
   vec3 q0 = decodeVertexPosition(terrainData[(quadId<<2)+0]) + origin;
   vec3 q1 = decodeVertexPosition(terrainData[(quadId<<2)+1]) + origin;
   vec3 q2 = decodeVertexPosition(terrainData[(quadId<<2)+2]) + origin;
   vec3 nvFaceNormal = normalize(cross(q1 - q0, q2 - q0));
   ```
   (`quadId` is in scope in `main()`; pass it into `putVertex`, or compute the normal once in
   `main()` and hand it to `putVertex`. The value is identical for all vertices of the quad —
   matching the flat-shaded face convention.) This is the single change expected to fix the
   black. Sign/winding matches translucent (front-facing CCW), so no negation needed.

2. **Guard against any residual zero-length normal** (defensive, prevents NaN if a future
   degenerate quad appears): clamp before normalizing, e.g.
   `vec3 n = cross(q1-q0, q2-q0); nvFaceNormal = n == vec3(0) ? vec3(0,1,0) : normalize(n);`
   Keeps `finalDiffuse` finite even on slivers.

3. **No change to albedo/sampler binding** — already correct (atlas→0, lightmap→2, `tex`
   resolves to unit 0). Verified against `IrisSamplers.addLevelSamplers` and `SodiumShader`.
   Do NOT touch `IrisGbufferBinder` units.

4. **No change to `mat`** — default `0` is a valid plain block; leave as-is.

5. **(Optional, post-black) feed `tangent`/`binormal` for TRANSLUCENT/PBR.** Currently zeroed
   (mapper defaults). They only matter if the pack enables `RAIN_PUDDLES>=1`,
   `GENERATED_NORMALS`, or `CUSTOM_PBR` (which build `tbnMatrix` from them). With those off
   they are unused; with them on, zeroed tangent space gives wrong puddle/normal-mapped
   reflections (NOT black). Derive a tangent from the face normal + a stable up reference if
   PBR is enabled. Low priority.

6. **(Optional) verify reflection normal output** (`gl_FragData[2]`, colortex4) once shading
   works — it reuses `normalM`, so the Fix #1 normal also repairs SSR. No separate change.

---

## Uncertainties / where a runtime gate would confirm

- **`max(NaN,0.0) -> 0` collapse.** Highly likely on NVIDIA (IEEE `max` with the NaN as first
  arg returns the second operand on most NV drivers), which is why it goes uniformly black
  rather than garbage-colored. A `-Dnvidium.iris.debug=normal` run BEFORE the fix should show
  black/garbage across faces; AFTER the fix it should show smooth view-space normal colors.
  This is the cheapest confirmation gate. (Note: the current dump files in
  `run/nvidium-iris-dump/` are STALE — they predate the constant-varying injection and show
  all varyings as mesh `out`; don't trust them for the live interface. The live mapper only
  emits the PER_VERTEX subset.)
- **Winding sign after the fix.** ~95% confidence it's correct (translucent uses the same
  `cross(q1-q0,q2-q0)` and renders fine). If, post-fix, lit faces look inverted (lit on the
  shaded side), negate `nvFaceNormal` — the lever is the cross-product operand order at the
  mesh call site, not the view-space transform.
- **Quad-corner indices 0,1,2.** Assumes Sodium's quad layout matches the translucent path's
  assumption (it splits `(quadId<<2)+{0,1,2,3}` the same way; solid `main()` already loads
  `V0=+0`, `V2=+2`). Using +0,+1,+2 is consistent with both. A debug-normal gate confirms.
- The analysis assumes the bridge actually links `gbuffers_terrain` (not `dh_terrain`); the
  brief and `programIdFor` agree it's the Sodium terrain programs. If logs show `dh_terrain`
  resolved, the normal fix still applies identically (same `gl_NormalMatrix*gl_Normal`
  contract).
