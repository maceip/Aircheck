package com.example.graph

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.ln

data class StoredContact(
    val record: GraphContactRecord,
    val bioVector: FloatArray
)

data class StoredPost(
    val record: GraphPostRecord,
    val postVector: FloatArray
)

/**
 * Local SQLite persistence layer for the Stage 4 Network Graph Radar.
 *
 * Stores imported X/Twitter contacts, posts, L2-normalized float32 embeddings,
 * per-topic muted handles, and an FTS5 full-text index (`posts_fts`) with
 * automatic LIKE+BM25 fallback only if a host JVM SQLite build lacks FTS5.
 *
 * Never pre-seeds or fabricates contacts or posts.
 */
class LocalGraphDatabase(
    context: Context,
    databaseName: String? = DATABASE_NAME
) : SQLiteOpenHelper(
    context.applicationContext ?: context,
    databaseName,
    null,
    DATABASE_VERSION
) {

    @Volatile
    private var fts5Ready: Boolean? = null

    val isFts5Enabled: Boolean
        get() = ensureFts5Available(readableDatabase)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.enableWriteAheadLogging()
        } catch (_: Throwable) {
            // In-memory databases do not support WAL; ignore safely.
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONTACTS (
                handle TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                bio TEXT NOT NULL,
                follower_count INTEGER NOT NULL,
                mutual_replies_count INTEGER NOT NULL,
                last_interaction_ms INTEGER NOT NULL,
                bio_vec BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_POSTS (
                tweet_id TEXT PRIMARY KEY,
                handle TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                is_reply_to_user INTEGER NOT NULL,
                post_vec BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MUTED_TOPICS (
                handle TEXT NOT NULL,
                topic_key TEXT NOT NULL,
                muted_at_ms INTEGER NOT NULL,
                PRIMARY KEY(handle, topic_key)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_posts_handle ON $TABLE_POSTS(handle)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_posts_created_at ON $TABLE_POSTS(created_at_ms DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_muted_topic_key ON $TABLE_MUTED_TOPICS(topic_key)")

        fts5Ready = tryCreateFts5Table(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 initial release; preserve compatibility on future migrations.
    }

    private fun tryCreateFts5Table(db: SQLiteDatabase): Boolean {
        return try {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_POSTS_FTS
                USING fts5(tweet_id UNINDEXED, handle, text, tokenize='porter unicode61')
                """.trimIndent()
            )
            true
        } catch (_: SQLiteException) {
            false
        }
    }

    private fun ensureFts5Available(db: SQLiteDatabase): Boolean {
        fts5Ready?.let { return it }
        val exists = try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                arrayOf(TABLE_POSTS_FTS)
            ).use { cursor -> cursor.moveToFirst() }
        } catch (_: SQLiteException) {
            false
        }
        val available = if (exists) true else if (!db.isReadOnly) tryCreateFts5Table(db) else false
        fts5Ready = available
        return available
    }

    fun upsertContactsAndPosts(
        contacts: List<GraphContactRecord>,
        posts: List<GraphPostRecord>
    ): ImportSummary {
        val db = writableDatabase
        val useFts5 = ensureFts5Available(db)
        val importedContactHandles = LinkedHashSet<String>()
        val importedPostIds = LinkedHashSet<String>()

        db.beginTransaction()
        try {
            // Merge contacts cleanly so incremental imports enrich rather than erase metadata.
            for (rawContact in contacts) {
                val cleanHandle = normalizeGraphHandle(rawContact.handle)
                if (cleanHandle.isBlank()) continue

                val existing = queryContactRecord(db, cleanHandle)
                val mergedDisplayName = rawContact.displayName.trim()
                    .ifBlank { existing?.displayName.orEmpty() }
                val mergedBio = rawContact.bio.trim()
                    .ifBlank { existing?.bio.orEmpty() }
                val mergedFollowers = if (rawContact.followerCount > 0) {
                    rawContact.followerCount
                } else {
                    existing?.followerCount ?: 0
                }
                val mergedMutualReplies = maxOf(
                    rawContact.mutualRepliesCount.coerceAtLeast(0),
                    existing?.mutualRepliesCount ?: 0
                )
                val mergedLastInteraction = maxOf(
                    rawContact.lastInteractionEpochMs.coerceAtLeast(0L),
                    existing?.lastInteractionEpochMs ?: 0L
                )

                val bioText = "$mergedDisplayName $mergedBio".trim()
                val bioVecBytes = LocalVectorEmbedder.toByteArray(LocalVectorEmbedder.embed(bioText))

                val values = ContentValues().apply {
                    put("handle", cleanHandle)
                    put("display_name", mergedDisplayName)
                    put("bio", mergedBio)
                    put("follower_count", mergedFollowers)
                    put("mutual_replies_count", mergedMutualReplies)
                    put("last_interaction_ms", mergedLastInteraction)
                    put("bio_vec", bioVecBytes)
                }
                db.insertWithOnConflict(TABLE_CONTACTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                importedContactHandles.add(cleanHandle)
            }

            // Upsert posts and keep posts_fts synchronized.
            for (rawPost in posts) {
                val cleanTweetId = rawPost.tweetId.trim()
                val cleanHandle = normalizeGraphHandle(rawPost.handle)
                val cleanText = rawPost.text.trim()
                if (cleanTweetId.isBlank() || cleanHandle.isBlank() || cleanText.isBlank()) continue

                // Guarantee every post's author has an honest contact row if absent.
                if (cleanHandle !in importedContactHandles && queryContactRecord(db, cleanHandle) == null) {
                    val emptyBioVec = LocalVectorEmbedder.toByteArray(FloatArray(LocalVectorEmbedder.VECTOR_DIM))
                    val minimalContact = ContentValues().apply {
                        put("handle", cleanHandle)
                        put("display_name", "")
                        put("bio", "")
                        put("follower_count", 0)
                        put("mutual_replies_count", if (rawPost.isReplyToUser) 1 else 0)
                        put("last_interaction_ms", rawPost.createdAtEpochMs.coerceAtLeast(0L))
                        put("bio_vec", emptyBioVec)
                    }
                    db.insertWithOnConflict(TABLE_CONTACTS, null, minimalContact, SQLiteDatabase.CONFLICT_REPLACE)
                    importedContactHandles.add(cleanHandle)
                }

                val postVecBytes = LocalVectorEmbedder.toByteArray(LocalVectorEmbedder.embed(cleanText))
                val postValues = ContentValues().apply {
                    put("tweet_id", cleanTweetId)
                    put("handle", cleanHandle)
                    put("text", cleanText)
                    put("created_at_ms", rawPost.createdAtEpochMs.coerceAtLeast(0L))
                    put("is_reply_to_user", if (rawPost.isReplyToUser) 1 else 0)
                    put("post_vec", postVecBytes)
                }
                db.insertWithOnConflict(TABLE_POSTS, null, postValues, SQLiteDatabase.CONFLICT_REPLACE)

                if (useFts5) {
                    db.delete(TABLE_POSTS_FTS, "tweet_id = ?", arrayOf(cleanTweetId))
                    val ftsValues = ContentValues().apply {
                        put("tweet_id", cleanTweetId)
                        put("handle", cleanHandle)
                        put("text", cleanText)
                    }
                    db.insert(TABLE_POSTS_FTS, null, ftsValues)
                }

                importedPostIds.add(cleanTweetId)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return ImportSummary(
            contactsImported = importedContactHandles.size,
            postsImported = importedPostIds.size
        )
    }

    fun contactCount(): Int {
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CONTACTS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun postCount(): Int {
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_POSTS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getAllContacts(): List<StoredContact> {
        val result = ArrayList<StoredContact>()
        readableDatabase.rawQuery(
            """
            SELECT handle, display_name, bio, follower_count, mutual_replies_count,
                   last_interaction_ms, bio_vec
            FROM $TABLE_CONTACTS
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val record = GraphContactRecord(
                    handle = cursor.getString(0),
                    displayName = cursor.getString(1),
                    bio = cursor.getString(2),
                    followerCount = cursor.getInt(3),
                    mutualRepliesCount = cursor.getInt(4),
                    lastInteractionEpochMs = cursor.getLong(5)
                )
                val vec = LocalVectorEmbedder.fromByteArray(cursor.getBlob(6))
                result.add(StoredContact(record = record, bioVector = vec))
            }
        }
        return result
    }

    fun getContact(handle: String): StoredContact? {
        val cleanHandle = normalizeGraphHandle(handle)
        if (cleanHandle.isBlank()) return null
        readableDatabase.rawQuery(
            """
            SELECT handle, display_name, bio, follower_count, mutual_replies_count,
                   last_interaction_ms, bio_vec
            FROM $TABLE_CONTACTS
            WHERE handle = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(cleanHandle)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val record = GraphContactRecord(
                handle = cursor.getString(0),
                displayName = cursor.getString(1),
                bio = cursor.getString(2),
                followerCount = cursor.getInt(3),
                mutualRepliesCount = cursor.getInt(4),
                lastInteractionEpochMs = cursor.getLong(5)
            )
            return StoredContact(
                record = record,
                bioVector = LocalVectorEmbedder.fromByteArray(cursor.getBlob(6))
            )
        }
    }

    fun getAllPosts(): List<StoredPost> {
        val result = ArrayList<StoredPost>()
        readableDatabase.rawQuery(
            """
            SELECT tweet_id, handle, text, created_at_ms, is_reply_to_user, post_vec
            FROM $TABLE_POSTS
            ORDER BY created_at_ms DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val record = GraphPostRecord(
                    tweetId = cursor.getString(0),
                    handle = cursor.getString(1),
                    text = cursor.getString(2),
                    createdAtEpochMs = cursor.getLong(3),
                    isReplyToUser = cursor.getInt(4) != 0
                )
                val vec = LocalVectorEmbedder.fromByteArray(cursor.getBlob(5))
                result.add(StoredPost(record = record, postVector = vec))
            }
        }
        return result
    }

    /**
     * Searches posts using SQLite FTS5 `bm25(posts_fts)` when available, returning a map
     * from `tweetId` to positive BM25 relevance score (higher = stronger match).
     * Falls back cleanly to SQL `LIKE` + Okapi BM25 only if FTS5 is unavailable in the host SQLite binary.
     */
    fun searchPostsFts(queryTerms: List<String>, limit: Int = 50): Map<String, Double> {
        if (limit <= 0) return emptyMap()
        val cleanTerms = queryTerms
            .map { NON_ALNUM_REGEX.replace(it.lowercase(), "") }
            .filter { it.length >= 2 }
            .distinct()
            .take(16)
        if (cleanTerms.isEmpty()) return emptyMap()

        val db = readableDatabase
        if (ensureFts5Available(db)) {
            val matchQuery = cleanTerms.joinToString(separator = " OR ") { "\"$it\"" }
            try {
                val scores = LinkedHashMap<String, Double>()
                db.rawQuery(
                    """
                    SELECT tweet_id, bm25($TABLE_POSTS_FTS) AS rank_score
                    FROM $TABLE_POSTS_FTS
                    WHERE $TABLE_POSTS_FTS MATCH ?
                    ORDER BY rank_score ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(matchQuery, limit.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val tweetId = cursor.getString(0)
                        val rawBm25 = cursor.getDouble(1)
                        // SQLite FTS5 bm25() returns negative scores (more negative = better match).
                        val positiveScore = (-rawBm25).coerceAtLeast(1e-6)
                        scores[tweetId] = positiveScore
                    }
                }
                return scores
            } catch (_: SQLiteException) {
                // Fall through to LIKE+BM25 fallback if an environment's FTS5 query fails.
            }
        }

        return searchPostsLikeBm25(db, cleanTerms, limit)
    }

    internal fun searchPostsLikeBm25(
        db: SQLiteDatabase,
        cleanTerms: List<String>,
        limit: Int
    ): Map<String, Double> {
        if (cleanTerms.isEmpty() || limit <= 0) return emptyMap()

        val totalDocs = db.rawQuery("SELECT COUNT(*) FROM $TABLE_POSTS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (totalDocs == 0) return emptyMap()

        val whereClauses = cleanTerms.joinToString(" OR ") {
            "(LOWER(text) LIKE ? OR LOWER(handle) LIKE ?)"
        }
        val args = ArrayList<String>(cleanTerms.size * 2)
        for (term in cleanTerms) {
            val pattern = "%$term%"
            args.add(pattern)
            args.add(pattern)
        }

        val candidates = ArrayList<Pair<String, List<String>>>()
        db.rawQuery(
            "SELECT tweet_id, handle, text FROM $TABLE_POSTS WHERE $whereClauses",
            args.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val tweetId = cursor.getString(0)
                val handle = cursor.getString(1)
                val text = cursor.getString(2)
                val docTokens = LocalVectorEmbedder.tokenizeWords("$handle $text")
                candidates.add(tweetId to docTokens)
            }
        }
        if (candidates.isEmpty()) return emptyMap()

        val avgDocLen = candidates.sumOf { it.second.size }.toDouble() / candidates.size.toDouble()
        val safeAvgLen = avgDocLen.coerceAtLeast(1.0)
        val termStems = cleanTerms.map { LocalVectorEmbedder.lightStem(it) }.distinct()

        // Document frequency across candidate pool
        val docFreq = HashMap<String, Int>(termStems.size)
        for ((_, tokens) in candidates) {
            val docStemSet = tokens.map { LocalVectorEmbedder.lightStem(it) }.toSet()
            for (stem in termStems) {
                if (stem in docStemSet) {
                    docFreq[stem] = (docFreq[stem] ?: 0) + 1
                }
            }
        }

        val k1 = 1.2
        val b = 0.75
        val scored = ArrayList<Pair<String, Double>>(candidates.size)
        for ((tweetId, tokens) in candidates) {
            val tfMap = HashMap<String, Int>()
            for (tok in tokens) {
                val stem = LocalVectorEmbedder.lightStem(tok)
                tfMap[stem] = (tfMap[stem] ?: 0) + 1
            }
            var bm25 = 0.0
            val dl = tokens.size.toDouble().coerceAtLeast(1.0)
            for (stem in termStems) {
                val tf = (tfMap[stem] ?: 0).toDouble()
                if (tf <= 0.0) continue
                val df = (docFreq[stem] ?: 0).toDouble()
                val idf = ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5))).coerceAtLeast(1e-6)
                bm25 += idf * ((tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (dl / safeAvgLen))))
            }
            if (bm25 > 0.0) {
                scored.add(tweetId to bm25)
            }
        }

        scored.sortByDescending { it.second }
        val result = LinkedHashMap<String, Double>()
        for ((tweetId, score) in scored.take(limit)) {
            result[tweetId] = score
        }
        return result
    }

    fun muteContactForTopic(
        handle: String,
        topicKey: String,
        mutedAtMs: Long = System.currentTimeMillis()
    ) {
        val cleanHandle = normalizeGraphHandle(handle)
        val cleanTopic = topicKey.trim().lowercase()
        if (cleanHandle.isBlank() || cleanTopic.isBlank()) return

        val values = ContentValues().apply {
            put("handle", cleanHandle)
            put("topic_key", cleanTopic)
            put("muted_at_ms", mutedAtMs)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_MUTED_TOPICS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun unmuteContactForTopic(handle: String, topicKey: String) {
        val cleanHandle = normalizeGraphHandle(handle)
        val cleanTopic = topicKey.trim().lowercase()
        if (cleanHandle.isBlank() || cleanTopic.isBlank()) return

        writableDatabase.delete(
            TABLE_MUTED_TOPICS,
            "handle = ? AND topic_key = ?",
            arrayOf(cleanHandle, cleanTopic)
        )
    }

    fun getMutedHandlesForTopic(topicKey: String): Set<String> {
        val cleanTopic = topicKey.trim().lowercase()
        if (cleanTopic.isBlank()) return emptySet()

        val muted = LinkedHashSet<String>()
        readableDatabase.rawQuery(
            "SELECT handle FROM $TABLE_MUTED_TOPICS WHERE topic_key = ?",
            arrayOf(cleanTopic)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                muted.add(cursor.getString(0))
            }
        }
        return muted
    }

    fun clearAll() {
        val db = writableDatabase
        val useFts5 = ensureFts5Available(db)
        db.beginTransaction()
        try {
            if (useFts5) {
                db.delete(TABLE_POSTS_FTS, null, null)
            }
            db.delete(TABLE_POSTS, null, null)
            db.delete(TABLE_CONTACTS, null, null)
            db.delete(TABLE_MUTED_TOPICS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun queryContactRecord(db: SQLiteDatabase, cleanHandle: String): GraphContactRecord? {
        return db.rawQuery(
            """
            SELECT handle, display_name, bio, follower_count, mutual_replies_count, last_interaction_ms
            FROM $TABLE_CONTACTS
            WHERE handle = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(cleanHandle)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            GraphContactRecord(
                handle = cursor.getString(0),
                displayName = cursor.getString(1),
                bio = cursor.getString(2),
                followerCount = cursor.getInt(3),
                mutualRepliesCount = cursor.getInt(4),
                lastInteractionEpochMs = cursor.getLong(5)
            )
        }
    }

    companion object {
        const val DATABASE_NAME: String = "aircheck_network_graph.db"
        const val DATABASE_VERSION: Int = 1

        internal const val TABLE_CONTACTS = "contacts"
        internal const val TABLE_POSTS = "posts"
        internal const val TABLE_POSTS_FTS = "posts_fts"
        internal const val TABLE_MUTED_TOPICS = "muted_topics"

        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]")
    }
}
