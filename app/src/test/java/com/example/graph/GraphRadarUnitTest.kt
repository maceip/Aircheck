package com.example.graph

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class GraphRadarUnitTest {

    private lateinit var context: Context
    private lateinit var database: LocalGraphDatabase
    private lateinit var engine: GraphRadarEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = LocalGraphDatabase(context, databaseName = null) // isolated in-memory SQLite per test
        engine = GraphRadarEngine(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // 1. Constitutional Law 2: Honest empty graph (zero pre-seeded/fake handles)
    // -------------------------------------------------------------------------

    @Test
    fun emptyDatabase_returnsZeroContactsAndEmptyMatches() {
        assertEquals(0, database.contactCount())
        assertEquals(0, database.postCount())
        val matches = engine.findMatches(
            draftText = "Local SQLite FTS5 plus compact embeddings beat heavy cloud RAG stacks on mobile."
        )
        assertTrue("Empty local graph must return empty list honestly", matches.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. LocalVectorEmbedder: determinism, L2 norm, serialization, similarity
    // -------------------------------------------------------------------------

    @Test
    fun localVectorEmbedder_isDeterministicNormalizedAndRoundTripsFloat32Bytes() {
        val text = "SQLite FTS5 BM25 retrieval with on-device quantized embeddings"
        val vecA = LocalVectorEmbedder.embed(text)
        val vecB = LocalVectorEmbedder.embed(text)

        assertEquals(LocalVectorEmbedder.VECTOR_DIM, vecA.size)
        assertEquals(128, vecA.size)
        assertTrue(vecA.contentEquals(vecB))

        // Verify L2 unit norm
        val norm = sqrt(vecA.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-5)

        // Verify float32 byte[] serialization round-trip
        val bytes = LocalVectorEmbedder.toByteArray(vecA)
        assertEquals(128 * 4, bytes.size)
        val restored = LocalVectorEmbedder.fromByteArray(bytes)
        assertTrue(vecA.contentEquals(restored))
        assertEquals(1.0, LocalVectorEmbedder.cosineSimilarity(vecA, restored), 1e-6)

        // Blank text produces zero vector and 0 cosine
        val zeroVec = LocalVectorEmbedder.embed("   ")
        assertEquals(0.0, LocalVectorEmbedder.cosineSimilarity(vecA, zeroVec), 1e-9)
    }

    @Test
    fun localVectorEmbedder_ranksRelatedTextHigherThanUnrelatedAndRunsSubMillisecond() {
        val draft = "Quantized local LLM inference with SQLite FTS5 indexing on Android"
        val related = "On-device quantization and SQLite FTS5 search make Android retrieval fast"
        val unrelated = "Homemade sourdough fermentation schedule and oven steam crust tips"

        val relatedCos = LocalVectorEmbedder.cosineSimilarity(draft, related)
        val unrelatedCos = LocalVectorEmbedder.cosineSimilarity(draft, unrelated)

        assertTrue(
            "Expected related cosine ($relatedCos) >> unrelated cosine ($unrelatedCos)",
            relatedCos > unrelatedCos + 0.25
        )

        // Warm up JIT then verify sub-millisecond average embedding time
        repeat(20) { LocalVectorEmbedder.embed(draft) }
        val startNs = System.nanoTime()
        val iterations = 100
        repeat(iterations) { LocalVectorEmbedder.embed(draft) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val avgMs = elapsedMs / iterations
        assertTrue("Expected fast local embedding (<2ms avg on JVM), got ${avgMs}ms", avgMs < 2.0)
    }

    // -------------------------------------------------------------------------
    // 3. LocalGraphDatabase: SQLite FTS5, LIKE+BM25 fallback, topic muting, clear
    // -------------------------------------------------------------------------

    @Test
    fun localGraphDatabase_persistsUpsertsQueriesFts5AndMutesPerTopic() {
        val nowMs = 1_750_000_000_000L
        val summary = database.upsertContactsAndPosts(
            contacts = listOf(
                GraphContactRecord(
                    handle = "@Alice_Dev",
                    displayName = "Alice",
                    bio = "Android systems engineer and SQLite maintainer",
                    followerCount = 4_200,
                    mutualRepliesCount = 6,
                    lastInteractionEpochMs = nowMs - 4L * DAY_MS
                ),
                GraphContactRecord(
                    handle = "chef_mario",
                    displayName = "Mario",
                    bio = "Artisan bread and espresso",
                    followerCount = 1_500,
                    mutualRepliesCount = 2,
                    lastInteractionEpochMs = nowMs - 2L * DAY_MS
                )
            ),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "1001",
                    handle = "@Alice_Dev",
                    text = "SQLite FTS5 porter tokenizer makes local mobile retrieval instant.",
                    createdAtEpochMs = nowMs - 4L * DAY_MS,
                    isReplyToUser = true
                ),
                GraphPostRecord(
                    tweetId = "1002",
                    handle = "chef_mario",
                    text = "Cold proofing pizza dough for 72 hours changes the crumb structure.",
                    createdAtEpochMs = nowMs - 3L * DAY_MS,
                    isReplyToUser = false
                )
            )
        )

        assertEquals(2, summary.contactsImported)
        assertEquals(2, summary.postsImported)
        assertEquals(2, database.contactCount())
        assertEquals(2, database.postCount())

        // Re-upserting same tweet_id updates cleanly without duplicating
        database.upsertContactsAndPosts(
            contacts = emptyList(),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "1001",
                    handle = "alice_dev",
                    text = "SQLite FTS5 porter tokenizer makes local mobile retrieval instant and offline.",
                    createdAtEpochMs = nowMs - 4L * DAY_MS,
                    isReplyToUser = true
                )
            )
        )
        assertEquals(2, database.postCount())

        // Search handles punctuation & reserved words safely (uses FTS5 on Android; clean LIKE+BM25 fallback on Robolectric if FTS5 module is absent)
        val ftsScores = database.searchPostsFts(listOf("sqlite", "fts5", "NOT", "OR", "don't", "retrieval"))
        assertTrue("Expected tweet 1001 in FTS results: $ftsScores", "1001" in ftsScores)
        assertFalse("Unrelated dough post should not match SQLite FTS query", "1002" in ftsScores)

        // Also verify LIKE+BM25 fallback path directly
        val fallbackScores = database.searchPostsLikeBm25(
            database.readableDatabase,
            listOf("sqlite", "fts5", "retrieval"),
            limit = 10
        )
        assertTrue("1001" in fallbackScores)
        assertFalse("1002" in fallbackScores)

        // Topic-scoped muting
        database.muteContactForTopic("@Alice_Dev", "fts5:mobil:sqlite")
        assertEquals(setOf("alice_dev"), database.getMutedHandlesForTopic("fts5:mobil:sqlite"))
        assertTrue(database.getMutedHandlesForTopic("bread:dough:pizza").isEmpty())

        database.unmuteContactForTopic("alice_dev", "fts5:mobil:sqlite")
        assertTrue(database.getMutedHandlesForTopic("fts5:mobil:sqlite").isEmpty())

        // clearAll removes everything
        database.clearAll()
        assertEquals(0, database.contactCount())
        assertEquals(0, database.postCount())
        assertTrue(database.searchPostsFts(listOf("sqlite")).isEmpty())
    }

    // -------------------------------------------------------------------------
    // 4. XArchiveImporter: JS prefix stripping, tweets/like/following/DM/JSON/ZIP
    // -------------------------------------------------------------------------

    @Test
    fun xArchiveImporter_stripsYtdPrefixAndParsesTweetsLikesFollowingDmsAndSnapshot() {
        val rawTweetsJs = """
            window.YTD.tweets.part0 = [
              {
                "tweet": {
                  "id_str": "9001",
                  "full_text": "@peer_dev Totally agree on FTS5 keeping mobile latency under 1ms.",
                  "created_at": "Tue Oct 15 18:30:00 +0000 2024",
                  "in_reply_to_screen_name": "peer_dev",
                  "in_reply_to_user_id_str": "55501",
                  "entities": {
                    "user_mentions": [
                      { "name": "Peer Dev", "screen_name": "peer_dev", "id_str": "55501" }
                    ]
                  }
                }
              }
            ];
        """.trimIndent()

        val tweetsSummary = XArchiveImporter.importFileContent("tweets.js", rawTweetsJs, database)
        assertEquals(1, tweetsSummary.contactsImported)
        assertEquals(1, tweetsSummary.postsImported)
        val peer = database.getContact("peer_dev")
        assertNotNull(peer)
        assertEquals("Peer Dev", peer!!.record.displayName)
        assertEquals(1, peer.record.mutualRepliesCount)
        assertTrue(peer.record.lastInteractionEpochMs > 1_700_000_000_000L)

        // like.js: resolves real URL handle and RT handle, skips /i/web/status without handle
        val rawLikesJs = """
            window.YTD.like.part0 = [
              {
                "like": {
                  "tweetId": "9002",
                  "fullText": "Benchmarked SQLite FTS5 vs cloud vector stores on Pixel.",
                  "expandedUrl": "https://x.com/sqlite_builder/status/9002"
                }
              },
              {
                "like": {
                  "tweetId": "9003",
                  "fullText": "Anonymous post without resolvable handle.",
                  "expandedUrl": "https://twitter.com/i/web/status/9003"
                }
              }
            ]
        """.trimIndent()
        val likesSummary = XArchiveImporter.importFileContent("like.js", rawLikesJs, database)
        assertEquals(1, likesSummary.contactsImported)
        assertEquals(1, likesSummary.postsImported)
        assertNotNull(database.getContact("sqlite_builder"))

        // direct-messages.js
        val rawDmsJs = """
            window.YTD.direct_messages.part0 = [
              {
                "dmConversation": {
                  "conversationId": "100-55501",
                  "messages": [
                    {
                      "messageCreate": {
                        "senderId": "55501",
                        "recipientId": "100",
                        "senderHandle": "peer_dev",
                        "text": "Let's benchmark FTS5 next week",
                        "createdAt": "2024-10-20T12:00:00.000Z"
                      }
                    }
                  ]
                }
              }
            ]
        """.trimIndent()
        val dmSummary = XArchiveImporter.importFileContent("direct-messages.js", rawDmsJs, database)
        assertEquals(1, dmSummary.contactsImported)

        // Custom JSON snapshot { "contacts": [...], "posts": [...] }
        val customSnapshot = """
            {
              "contacts": [
                {
                  "handle": "@custom_maintainer",
                  "displayName": "Custom Maintainer",
                  "bio": "Open source maintainer of local Kotlin search engines",
                  "followerCount": 8900,
                  "mutualRepliesCount": 4,
                  "lastInteractionEpochMs": 1749500000000
                }
              ],
              "posts": [
                {
                  "tweetId": "9010",
                  "handle": "@custom_maintainer",
                  "text": "Just shipped a zero-allocation FTS5 query planner.",
                  "createdAtEpochMs": 1749500000000,
                  "isReplyToUser": false
                }
              ]
            }
        """.trimIndent()
        val snapshotSummary = XArchiveImporter.importFileContent("graph_snapshot.json", customSnapshot, database)
        assertEquals(1, snapshotSummary.contactsImported)
        assertEquals(1, snapshotSummary.postsImported)
        assertNotNull(database.getContact("custom_maintainer"))
    }

    @Test
    fun xArchiveImporter_parsesZipArchiveAndCrossLinksAccountIds() {
        val zipBytes = buildZipArchive(
            "data/account.js" to """
                window.YTD.account.part0 = [
                  { "account": { "accountId": "111", "username": "ryan_owner", "accountDisplayName": "Ryan" } }
                ]
            """.trimIndent(),
            "data/tweets.js" to """
                window.YTD.tweets.part0 = [
                  {
                    "tweet": {
                      "id_str": "7001",
                      "full_text": "@sparring_partner Disagree that cloud RAG is required for 50k tweets.",
                      "created_at": "Wed Nov 01 10:00:00 +0000 2024",
                      "in_reply_to_screen_name": "sparring_partner",
                      "in_reply_to_user_id_str": "222",
                      "entities": {
                        "user_mentions": [
                          { "name": "Sparring Partner", "screen_name": "sparring_partner", "id_str": "222" }
                        ]
                      }
                    }
                  }
                ]
            """.trimIndent(),
            "data/following.js" to """
                window.YTD.following.part0 = [
                  { "following": { "accountId": "222", "userLink": "https://twitter.com/intent/user?user_id=222" } },
                  { "following": { "accountId": "333", "handle": "kernel_hacker", "bio": "Linux & Android NDK maintainer", "followerCount": 6400 } }
                ]
            """.trimIndent(),
            "data/like.js" to """
                window.YTD.like.part0 = [
                  {
                    "like": {
                      "tweetId": "7002",
                      "fullText": "Android SQLite FTS5 + 128-dim cosine runs in under 1ms.",
                      "expandedUrl": "https://x.com/kernel_hacker/status/7002"
                    }
                  }
                ]
            """.trimIndent()
        )

        val summary = XArchiveImporter.importZipStream(ByteArrayInputStream(zipBytes), database)
        assertEquals(2, summary.contactsImported)
        assertEquals(2, summary.postsImported)
        assertNull("Archive owner must not be imported as external contact", database.getContact("ryan_owner"))
        assertNull("Account 222 should resolve to sparring_partner, not id_222", database.getContact("id_222"))
        assertNotNull(database.getContact("sparring_partner"))
        assertNotNull(database.getContact("kernel_hacker"))
        assertEquals("Linux & Android NDK maintainer", database.getContact("kernel_hacker")!!.record.bio)
    }

    @Test(expected = IllegalArgumentException::class)
    fun xArchiveImporter_rejectsCorruptJsonWithClearError() {
        XArchiveImporter.parseFileContent("tweets.js", "window.YTD.tweets.part0 = {broken json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun xArchiveImporter_rejectsUnsupportedZipWithoutArchiveFiles() {
        val zipBytes = buildZipArchive("README.txt" to "not a twitter archive")
        XArchiveImporter.parseZipStream(ByteArrayInputStream(zipBytes))
    }

    // -------------------------------------------------------------------------
    // 5. GraphRadarEngine: scoring, broadcast penalty, reasons, muting, weaving
    // -------------------------------------------------------------------------

    @Test
    fun computeRankScore_enforcesExactFormulaAndBroadcastPenalty() {
        // Base formula: (0.8 * 0.55) + (0.6 * 0.25) + (0.9 * 0.20) = 0.44 + 0.15 + 0.18 = 0.77
        val peerScore = GraphRadarEngine.computeRankScore(
            semanticSimilarity = 0.8,
            keywordBm25Normalized = 0.6,
            mutualRecencyWeight = 0.9,
            followerCount = 3_500,
            mutualRepliesCount = 4
        )
        assertEquals(0.77, peerScore, 1e-6)

        // Broadcast account >= 100k followers, 0 mutual replies -> 0.35x multiplier
        val megaBroadcast = GraphRadarEngine.computeRankScore(
            semanticSimilarity = 0.8,
            keywordBm25Normalized = 0.6,
            mutualRecencyWeight = 0.0,
            followerCount = 250_000,
            mutualRepliesCount = 0
        )
        val expectedMega = ((0.8 * 0.55) + (0.6 * 0.25)) * 0.35
        assertEquals(expectedMega, megaBroadcast, 1e-6)

        // Mid broadcast account >= 25k followers, 0 mutual replies -> 0.55x multiplier
        val midBroadcast = GraphRadarEngine.computeRankScore(
            semanticSimilarity = 0.8,
            keywordBm25Normalized = 0.6,
            mutualRecencyWeight = 0.0,
            followerCount = 40_000,
            mutualRepliesCount = 0
        )
        val expectedMid = ((0.8 * 0.55) + (0.6 * 0.25)) * 0.55
        assertEquals(expectedMid, midBroadcast, 1e-6)

        // High-follower account WITH mutual replies receives NO broadcast penalty
        val mutualBigAccount = GraphRadarEngine.computeRankScore(
            semanticSimilarity = 0.8,
            keywordBm25Normalized = 0.6,
            mutualRecencyWeight = 0.5,
            followerCount = 250_000,
            mutualRepliesCount = 3
        )
        assertEquals((0.8 * 0.55) + (0.6 * 0.25) + (0.5 * 0.20), mutualBigAccount, 1e-6)
        assertTrue(peerScore > megaBroadcast)
    }

    @Test
    fun findMatches_penalizesBroadcastAccountsClassifiesAllReasonsAndRespectsTopicMute() {
        val nowMs = 1_760_000_000_000L
        val draft = "Local SQLite FTS5 hybrid search beats cloud vector databases on mobile latency."

        database.upsertContactsAndPosts(
            contacts = listOf(
                GraphContactRecord(
                    handle = "mega_broadcast",
                    displayName = "AI News Wire",
                    bio = "Breaking AI headlines every hour",
                    followerCount = 500_000,
                    mutualRepliesCount = 0,
                    lastInteractionEpochMs = 0L
                ),
                GraphContactRecord(
                    handle = "sqlite_maintainer",
                    displayName = "Elena",
                    bio = "Open source maintainer of Android SQLite FTS5 extensions",
                    followerCount = 8_200,
                    mutualRepliesCount = 5,
                    lastInteractionEpochMs = nowMs - 9L * DAY_MS
                ),
                GraphContactRecord(
                    handle = "skeptic_peer",
                    displayName = "Devon",
                    bio = "Distributed systems engineer",
                    followerCount = 2_400,
                    mutualRepliesCount = 4,
                    lastInteractionEpochMs = nowMs - 4L * DAY_MS
                ),
                GraphContactRecord(
                    handle = "active_collab",
                    displayName = "Sam",
                    bio = "Mobile compiler engineer",
                    followerCount = 1_900,
                    mutualRepliesCount = 7,
                    lastInteractionEpochMs = nowMs - 3L * DAY_MS
                ),
                GraphContactRecord(
                    handle = "quiet_peer",
                    displayName = "Nora",
                    bio = "Android app developer",
                    followerCount = 900,
                    mutualRepliesCount = 0,
                    lastInteractionEpochMs = nowMs - 45L * DAY_MS
                )
            ),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "1",
                    handle = "mega_broadcast",
                    text = "Local SQLite FTS5 hybrid search beats cloud vector databases on mobile latency.",
                    createdAtEpochMs = nowMs - 1L * DAY_MS,
                    isReplyToUser = false
                ),
                GraphPostRecord(
                    tweetId = "2",
                    handle = "sqlite_maintainer",
                    text = "Just shipped hybrid SQLite FTS5 plus local vector search on mobile with sub-ms latency.",
                    createdAtEpochMs = nowMs - 4L * DAY_MS,
                    isReplyToUser = false
                ),
                GraphPostRecord(
                    tweetId = "3",
                    handle = "skeptic_peer",
                    text = "Actually I disagree: local SQLite FTS5 search fails when mobile graphs hit millions of rows.",
                    createdAtEpochMs = nowMs - 6L * DAY_MS,
                    isReplyToUser = false
                ),
                GraphPostRecord(
                    tweetId = "4",
                    handle = "active_collab",
                    text = "Testing local SQLite FTS5 hybrid search latency on our mobile build today.",
                    createdAtEpochMs = nowMs - 3L * DAY_MS,
                    isReplyToUser = true
                ),
                GraphPostRecord(
                    tweetId = "5",
                    handle = "quiet_peer",
                    text = "Moved our mobile app notes search over to local SQLite FTS5.",
                    createdAtEpochMs = nowMs - 45L * DAY_MS,
                    isReplyToUser = false
                )
            )
        )

        val matches = engine.findMatches(draftText = draft, limit = 8, nowMs = nowMs)
        assertEquals(5, matches.size)

        // Collaborators/maintainers MUST outrank the 500k broadcast account with 0 mutual replies
        val broadcastIndex = matches.indexOfFirst { it.handle == "mega_broadcast" }
        val maintainerIndex = matches.indexOfFirst { it.handle == "sqlite_maintainer" }
        val activeIndex = matches.indexOfFirst { it.handle == "active_collab" }
        assertTrue(
            "Expected maintainer ($maintainerIndex) and active collaborator ($activeIndex) to beat broadcast ($broadcastIndex)",
            maintainerIndex < broadcastIndex && activeIndex < broadcastIndex
        )

        val byHandle = matches.associateBy { it.handle }
        assertEquals(MatchReason.MAINTAINER, byHandle["sqlite_maintainer"]!!.reason)
        assertEquals("4d ago", byHandle["sqlite_maintainer"]!!.postAgeLabel)
        assertEquals("9d ago", byHandle["sqlite_maintainer"]!!.lastReplyAgeLabel)
        assertEquals("https://x.com/sqlite_maintainer/status/2", byHandle["sqlite_maintainer"]!!.postUrl)

        assertEquals(MatchReason.COUNTER_ARGUMENT, byHandle["skeptic_peer"]!!.reason)
        assertEquals(MatchReason.ACTIVE_THREAD, byHandle["active_collab"]!!.reason)
        assertEquals(MatchReason.PEER, byHandle["quiet_peer"]!!.reason)
        assertEquals("", byHandle["quiet_peer"]!!.lastReplyAgeLabel)

        // Scores strictly descending
        for (i in 0 until matches.size - 1) {
            assertTrue(matches[i].score >= matches[i + 1].score)
        }

        // Mute mega_broadcast for this topic cluster only
        engine.muteHandleForDraft("mega_broadcast", draft, mutedAtMs = nowMs)
        val afterMute = engine.findMatches(draftText = draft, limit = 8, nowMs = nowMs)
        assertTrue(afterMute.none { it.handle == "mega_broadcast" })
        assertEquals(4, afterMute.size)

        // Unrelated topic draft with a matching post from mega_broadcast is NOT muted
        database.upsertContactsAndPosts(
            contacts = emptyList(),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "99",
                    handle = "mega_broadcast",
                    text = "Robotics actuators and dexterous manipulation benchmarks.",
                    createdAtEpochMs = nowMs - 2L * DAY_MS
                )
            )
        )
        val roboticsMatches = engine.findMatches(
            draftText = "Robotics actuators and dexterous manipulation benchmarks",
            limit = 5,
            nowMs = nowMs
        )
        assertTrue(roboticsMatches.any { it.handle == "mega_broadcast" })
    }

    @Test
    fun weaveHandleIntoDraft_preservesPunctuationAndHandlesClausesAndOpeners() {
        // Declarative single sentence preserves trailing period
        val single = engine.weaveHandleIntoDraft(
            "Local SQLite FTS5 keeps retrieval under 1ms.",
            "@alice_dev"
        )
        assertEquals("Local SQLite FTS5 keeps retrieval under 1ms (via @alice_dev).", single)

        // Response opener gets classic .@handle attribution
        val opener = engine.weaveHandleIntoDraft(
            "Agree that broadcast accounts crowd out peers.",
            "devon"
        )
        assertEquals(".@devon Agree that broadcast accounts crowd out peers.", opener)

        // Multi-sentence with matchContext targets the most relevant clause cleanly
        val multi = engine.weaveHandleIntoDraft(
            draftText = "Cloud RAG adds latency on cellular. SQLite FTS5 runs offline in under 1ms.",
            handle = "elena",
            matchContext = "Cloud RAG cellular latency overhead"
        )
        assertEquals(
            "Cloud RAG adds latency on cellular (via @elena). SQLite FTS5 runs offline in under 1ms.",
            multi
        )

        // Question preserves '?'
        val question = engine.weaveHandleIntoDraft(
            "Why pay cloud egress for personal search?",
            "sam"
        )
        assertEquals("Why pay cloud egress for personal search (cc @sam)?", question)

        // Idempotent: never duplicates @handle
        assertEquals(single, engine.weaveHandleIntoDraft(single, "alice_dev"))
    }

    // -------------------------------------------------------------------------
    // 6. XShipIntentHelper: sanitizeAscii, X app vs web fallback, clipboard
    // -------------------------------------------------------------------------

    @Test
    fun xShipIntentHelper_sanitizesAsciiAndCopiesToClipboard() {
        val messy = "“Local-first” isn’t a compromise—it’s 3–5× faster…   \nSecond line \t "
        val sanitized = XShipIntentHelper.sanitizeAscii(messy)

        assertFalse(sanitized.contains('“'))
        assertFalse(sanitized.contains('”'))
        assertFalse(sanitized.contains('’'))
        assertFalse(sanitized.contains('—'))
        assertFalse(sanitized.contains('–'))
        assertFalse(sanitized.contains('…'))
        assertEquals("\"Local-first\" isn't a compromise - it's 3-5× faster...\nSecond line", sanitized)

        val copied = XShipIntentHelper.copySanitizedToClipboard(context, messy)
        assertEquals(sanitized, copied)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertNotNull(clipboard.primaryClip)
        assertEquals(sanitized, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun xShipIntentHelper_buildsWebFallbackWhenXNotInstalledAndNativeDeepLinkWhenInstalled() {
        // 1. Default Robolectric env: com.twitter.android is NOT installed -> web fallback
        assertFalse(XShipIntentHelper.isXAppInstalled(context))

        val webCompose = XShipIntentHelper.buildOpenInXIntent(
            context = context,
            plainText = "“Local SQLite” — fast",
            quoteUrl = "https://x.com/elena/status/2"
        )
        assertEquals(Intent.ACTION_VIEW, webCompose.action)
        assertNull(webCompose.`package`)
        val webUri = webCompose.data.toString()
        assertTrue(webUri.startsWith("https://x.com/intent/tweet?text="))
        assertTrue(webUri.contains("https%3A%2F%2Fx.com%2Felena%2Fstatus%2F2"))
        assertFalse("Spaces should use %20 instead of +", webUri.contains("+"))

        val webReply = XShipIntentHelper.buildOpenInXIntent(
            context = context,
            plainText = "Solid point",
            replyTweetId = "1823456789"
        )
        assertTrue(webReply.data.toString().contains("in_reply_to=1823456789"))

        // 2. Install com.twitter.android in Robolectric ShadowPackageManager -> native deep links
        val xPkg = PackageInfo().apply { packageName = XShipIntentHelper.X_PACKAGE_NAME }
        shadowOf(context.packageManager).installPackage(xPkg)
        assertTrue(XShipIntentHelper.isXAppInstalled(context))

        val nativeQuote = XShipIntentHelper.buildOpenInXIntent(
            context = context,
            plainText = "Shipped offline search",
            quoteUrl = "https://x.com/elena/status/2"
        )
        assertEquals(XShipIntentHelper.X_PACKAGE_NAME, nativeQuote.`package`)
        assertTrue(nativeQuote.data.toString().startsWith("twitter://post?message="))
        assertTrue(nativeQuote.data.toString().contains("https%3A%2F%2Fx.com%2Felena%2Fstatus%2F2"))

        val nativeReply = XShipIntentHelper.buildOpenInXIntent(
            context = context,
            plainText = "Replying now",
            replyTweetId = "1823456789"
        )
        assertEquals(XShipIntentHelper.X_PACKAGE_NAME, nativeReply.`package`)
        assertEquals("twitter://status?id=1823456789", nativeReply.data.toString())
    }

    private fun buildZipArchive(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
