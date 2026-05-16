package milkucha.trmt.migration;

import milkucha.trmt.TRMT;
import net.minecraft.nbt.CompoundTag;

/**
 * Versioned migration entry point for {@link milkucha.trmt.erosion.ErosionPersistentState}'s
 * persisted NBT. Bump {@link #CURRENT_VERSION} whenever the on-disk format changes
 * in an incompatible way, and add a corresponding case to {@link #migrate}.
 *
 * <p>Format-versioning contract for TRMT 1.0+:
 * <ul>
 *   <li>The save's top-level CompoundTag carries an {@code int} field named
 *       {@code "version"}. Absent = legacy version 0 (the 0.x format).</li>
 *   <li>{@link #CURRENT_VERSION} is written on every {@code save()}.</li>
 *   <li>On {@code load()}, {@link #migrate(CompoundTag, int)} is invoked to
 *       step the NBT forward through each version's migration in order until it
 *       reaches the current version.</li>
 *   <li>Migrations MUST be idempotent and MUST NOT throw on a save that's
 *       already at the target version.</li>
 *   <li>Migrations are forward-only — there is no rollback path. A 1.0 client
 *       cannot read a 2.0 save.</li>
 * </ul>
 *
 * <p>If you add a new version, document the change here in javadoc so the
 * migration history is auditable from a single file.
 *
 * <p><b>Version log:</b>
 * <ul>
 *   <li>{@code 0} → pre-1.0 (0.6.x and earlier). No version field present.</li>
 *   <li>{@code 1} → 1.0.0. Version field introduced. No data format changes
 *       vs version 0 — this is purely the introduction of the framework so
 *       future bumps have something to compare against.</li>
 * </ul>
 */
public final class SaveMigrator {

    /** Bump when the on-disk format changes. */
    public static final int CURRENT_VERSION = 1;

    /** NBT key carrying the format version. */
    public static final String VERSION_KEY = "version";

    private SaveMigrator() {}

    /**
     * Reads the {@code version} field from the given NBT and runs every needed
     * migration in order to bring it up to {@link #CURRENT_VERSION}. Mutates
     * {@code nbt} in place.
     *
     * @param nbt the root CompoundTag from {@code ErosionPersistentState.load}
     * @return the version the NBT was migrated FROM (for logging)
     */
    public static int migrateToCurrent(CompoundTag nbt) {
        int from = nbt.contains(VERSION_KEY) ? nbt.getInt(VERSION_KEY) : 0;
        if (from == CURRENT_VERSION) return from;
        if (from > CURRENT_VERSION) {
            TRMT.LOGGER.warn(
                "[TRMT] Save file format version {} is newer than this build's CURRENT_VERSION {}. "
              + "Loading anyway, but data loss is possible. Update the mod.",
                from, CURRENT_VERSION);
            return from;
        }
        for (int v = from; v < CURRENT_VERSION; v++) {
            migrate(nbt, v);
        }
        nbt.putInt(VERSION_KEY, CURRENT_VERSION);
        TRMT.LOGGER.info("[TRMT] Migrated save file from format version {} to {}.", from, CURRENT_VERSION);
        return from;
    }

    /**
     * Apply the single migration step that brings the NBT from {@code from} to
     * {@code from + 1}. Add a new case for each format bump.
     */
    private static void migrate(CompoundTag nbt, int from) {
        switch (from) {
            case 0 -> migrateV0ToV1(nbt);
            default -> throw new IllegalStateException(
                "[TRMT] No migration registered for save format version " + from);
        }
    }

    /**
     * Pre-1.0 (0.6.x and earlier) → 1.0.0. No-op: the on-disk layout is
     * unchanged; this migration exists only to stamp the version field.
     */
    private static void migrateV0ToV1(CompoundTag nbt) {
        // Intentional no-op. Format introduced no breaking changes;
        // the version stamp is applied by migrateToCurrent() after the loop.
    }
}
