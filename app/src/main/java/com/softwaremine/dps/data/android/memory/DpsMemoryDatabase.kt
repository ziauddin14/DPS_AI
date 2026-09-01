package com.softwaremine.dps.data.android.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryDao
import com.softwaremine.dps.data.android.memory.episodic.EpisodicMemoryEntity
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactDao
import com.softwaremine.dps.data.android.memory.semantic.SemanticFactEntity

/**
 * The one Room database in this codebase (M6) — long-term (episodic +
 * semantic) memory only.
 *
 * ## Why Room, when every other store here is SharedPreferences-plus-JSON
 * Every one of the ten existing stores
 * ([com.softwaremine.dps.data.android.memory.PersistentMemoryStore],
 * [com.softwaremine.dps.data.android.productivity.AndroidTaskStore], etc.)
 * reads and rewrites one JSON blob wholesale, an approach each of them
 * justifies specifically because its own data stays small — a handful to a
 * few hundred records, or a single fixed-shape value. Long-term memory's
 * entire purpose is to grow across weeks and months of daily use; applying
 * the same pattern here would mean rewriting an ever-larger blob on every
 * single turn, a real and worsening cost with no ceiling. Room is the
 * correct, minimum-scope tool for exactly this shape of problem — a
 * genuinely growing, queryable, prunable collection — and was already named
 * as the intended Android persistence mechanism in this project's own
 * original architecture notes, never previously needed until now.
 *
 * ## Why this does not replace the other ten stores
 * Deliberately not a platform migration. `ConversationMemory`,
 * `UserPreferences`, execution-recovery/checkpoint state, and every
 * productivity record remain exactly where and how they already are — see
 * each of their own docs for why they are correctly served by their
 * existing pattern. This database exists for the two tables it declares and
 * nothing else.
 *
 * ## Schema versioning — non-negotiable
 * `fallbackToDestructiveMigration()` must never be added to this builder.
 * Doing so would silently erase a user's long-term memory the moment a
 * future schema change ships — precisely the failure this milestone exists
 * to prevent. Every future schema change requires a real
 * [androidx.room.migration.Migration], written against the exported schema
 * history in `android/app/schemas/` (see `app/build.gradle.kts`'s own
 * `room.schemaLocation` configuration), even when [DATABASE_VERSION] has
 * not yet needed to move past its initial value.
 *
 * ## No at-rest encryption (M6, explicit, disclosed decision)
 * This mirrors the current, real posture of all ten existing stores, every
 * one of which is already unencrypted SharedPreferences today, relying on
 * Android's own OS-level full-disk encryption (standard since API 23; this
 * project's `minSdk` is 26). Adding SQLCipher here would mean solving a
 * codebase-wide gap inside one milestone's scope — deferred as its own
 * future decision, not silently treated as solved.
 *
 * ## Testability
 * Room has no JVM-testable seam without Robolectric, which this project's
 * own ADR-009 already rejects — [create]'s real, `Context`-backed
 * construction is exercised only by instrumented tests. Every actual
 * *decision* (retrieval relevance, retention, the privacy guard) lives in
 * [LongTermMemoryStore] as pure functions over plain domain lists
 * ([com.softwaremine.dps.domain.memory.SemanticFact]/[com.softwaremine.dps.domain.memory.EpisodicMemoryEntry]),
 * so that logic stays JVM-tested; only raw storage mechanics are
 * instrumented-only.
 */
@Database(
    entities = [SemanticFactEntity::class, EpisodicMemoryEntity::class],
    version = DpsMemoryDatabase.DATABASE_VERSION,
    exportSchema = true,
)
abstract class DpsMemoryDatabase : RoomDatabase() {

    abstract fun semanticFactDao(): SemanticFactDao
    abstract fun episodicMemoryDao(): EpisodicMemoryDao

    companion object {
        const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "dps_long_term_memory.db"

        /** Real, `Context`-backed construction — matches every other store's `create(context, logger)` call shape, minus the logger Room does not need. */
        fun create(context: Context): DpsMemoryDatabase =
            Room.databaseBuilder(context.applicationContext, DpsMemoryDatabase::class.java, DATABASE_NAME)
                .build()
    }
}
