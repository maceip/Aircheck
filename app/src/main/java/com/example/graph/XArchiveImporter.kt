package com.example.graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Parses real X/Twitter archive exports (`tweets.js`, `like.js`, `following.js`,
 * `direct-messages.js`, `account.js`), `.zip` archive streams, and custom JSON
 * snapshots (`{ "contacts": [...], "posts": [...] }`).
 *
 * Strips `window.YTD.<name>.part<N> = ` JS prefixes cleanly and never fabricates
 * placeholder contacts or posts.
 */
object XArchiveImporter {

    private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024 // 64 MB safety cap per archive entry

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val YTD_PREFIX_REGEX = Regex(
        pattern = "^\\s*(?:\\uFEFF)?window\\.YTD\\.[A-Za-z0-9_]+\\.part\\d+\\s*=\\s*",
        option = RegexOption.IGNORE_CASE
    )

    private val STATUS_URL_HANDLE_REGEX = Regex(
        pattern = "https?://(?:www\\.)?(?:twitter|x)\\.com/([A-Za-z0-9_]{1,15})/status/\\d+",
        option = RegexOption.IGNORE_CASE
    )

    private val PROFILE_URL_HANDLE_REGEX = Regex(
        pattern = "https?://(?:www\\.)?(?:twitter|x)\\.com/([A-Za-z0-9_]{1,15})(?:[/?#]|$)",
        option = RegexOption.IGNORE_CASE
    )

    private val RT_PREFIX_REGEX = Regex(
        pattern = "^RT\\s+@([A-Za-z0-9_]{1,15})\\s*:",
        option = RegexOption.IGNORE_CASE
    )

    private val TWITTER_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)

    private val RESERVED_PATH_SEGMENTS = setOf("i", "intent", "home", "search", "explore", "share", "hashtag")

    private class MutableContactAccumulator(
        val handle: String,
        var displayName: String = "",
        var bio: String = "",
        var followerCount: Int = 0,
        var mutualRepliesCount: Int = 0,
        var lastInteractionEpochMs: Long = 0L
    ) {
        fun mergeFrom(
            displayName: String = "",
            bio: String = "",
            followerCount: Int = 0,
            mutualRepliesDelta: Int = 0,
            mutualRepliesFloor: Int = 0,
            interactionEpochMs: Long = 0L
        ) {
            if (displayName.isNotBlank() && this.displayName.isBlank()) {
                this.displayName = displayName.trim()
            } else if (displayName.isNotBlank()) {
                this.displayName = displayName.trim()
            }
            if (bio.isNotBlank()) {
                this.bio = bio.trim()
            }
            if (followerCount > this.followerCount) {
                this.followerCount = followerCount
            }
            if (mutualRepliesDelta > 0) {
                this.mutualRepliesCount += mutualRepliesDelta
            }
            if (mutualRepliesFloor > this.mutualRepliesCount) {
                this.mutualRepliesCount = mutualRepliesFloor
            }
            if (interactionEpochMs > this.lastInteractionEpochMs) {
                this.lastInteractionEpochMs = interactionEpochMs
            }
        }

        fun toRecord(): GraphContactRecord = GraphContactRecord(
            handle = handle,
            displayName = displayName,
            bio = bio,
            followerCount = followerCount,
            mutualRepliesCount = mutualRepliesCount,
            lastInteractionEpochMs = lastInteractionEpochMs
        )
    }

    private class ParseContext {
        var selfAccountId: String = ""
        var selfHandle: String = ""
        val accountIdToHandle = HashMap<String, String>()
        val contacts = LinkedHashMap<String, MutableContactAccumulator>()
        val posts = LinkedHashMap<String, GraphPostRecord>()

        fun getOrCreateContact(handle: String): MutableContactAccumulator? {
            val clean = normalizeGraphHandle(handle)
            if (clean.isBlank()) return null
            if (selfHandle.isNotBlank() && clean == selfHandle) return null
            return contacts.getOrPut(clean) { MutableContactAccumulator(handle = clean) }
        }

        fun putPost(post: GraphPostRecord) {
            val cleanId = post.tweetId.trim()
            val cleanHandle = normalizeGraphHandle(post.handle)
            val cleanText = post.text.trim()
            if (cleanId.isBlank() || cleanHandle.isBlank() || cleanText.isBlank()) return
            if (selfHandle.isNotBlank() && cleanHandle == selfHandle) return

            val existing = posts[cleanId]
            if (existing == null || post.isReplyToUser || post.createdAtEpochMs > existing.createdAtEpochMs) {
                posts[cleanId] = post.copy(
                    tweetId = cleanId,
                    handle = cleanHandle,
                    text = cleanText,
                    isReplyToUser = post.isReplyToUser || (existing?.isReplyToUser == true)
                )
            }
        }

        fun toSnapshot(): ParsedGraphSnapshot = ParsedGraphSnapshot(
            contacts = contacts.values.map { it.toRecord() },
            posts = posts.values.toList()
        )
    }

    /**
     * Strips `window.YTD.<dataset>.part<N> = ` prefix and trailing semicolon from X archive JS files.
     */
    fun stripYtdPrefix(rawContent: String): String {
        val stripped = YTD_PREFIX_REGEX.replaceFirst(rawContent, "").trim()
        return stripped.removeSuffix(";").trim()
    }

    /**
     * Imports either a `.zip` X archive or a single `.js`/`.json` file into [database].
     */
    fun importFile(file: File, database: LocalGraphDatabase): ImportSummary {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            throw IllegalArgumentException("Archive file is missing or unreadable: ${file.path}")
        }
        return file.inputStream().use { stream ->
            importStream(stream, file.name, database)
        }
    }

    /**
     * Auto-detects ZIP magic bytes (`PK`) or `.zip` filename and imports into [database].
     */
    fun importStream(
        inputStream: InputStream,
        fileName: String,
        database: LocalGraphDatabase
    ): ImportSummary {
        val buffered = BufferedInputStream(inputStream)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()

        val looksLikeZip = (b0 == 0x50 && b1 == 0x4B) || fileName.lowercase().endsWith(".zip")
        return if (looksLikeZip) {
            importZipStream(buffered, database)
        } else {
            val content = readBoundedUtf8(buffered)
            importFileContent(fileName, content, database)
        }
    }

    fun importZipStream(inputStream: InputStream, database: LocalGraphDatabase): ImportSummary {
        val snapshot = parseZipStream(inputStream)
        return database.upsertContactsAndPosts(snapshot.contacts, snapshot.posts)
    }

    fun importFileContent(
        fileName: String,
        rawContent: String,
        database: LocalGraphDatabase
    ): ImportSummary {
        val snapshot = parseFileContent(fileName, rawContent)
        return database.upsertContactsAndPosts(snapshot.contacts, snapshot.posts)
    }

    /**
     * Parses a `.zip` X archive stream, extracting `data/account.js`, `data/tweets.js`,
     * `data/like.js`, `data/following.js`, `data/direct-messages.js` (and multi-part variants
     * or custom `.json` snapshots).
     */
    fun parseZipStream(inputStream: InputStream): ParsedGraphSnapshot {
        val accountEntries = ArrayList<Pair<String, String>>()
        val tweetEntries = ArrayList<Pair<String, String>>()
        val likeEntries = ArrayList<Pair<String, String>>()
        val followingEntries = ArrayList<Pair<String, String>>()
        val dmEntries = ArrayList<Pair<String, String>>()
        val snapshotEntries = ArrayList<Pair<String, String>>()

        var sawAnyZipEntry = false
        ZipInputStream(inputStream).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                sawAnyZipEntry = true
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val normalizedPath = entry.name.replace('\\', '/').lowercase()
                val baseName = normalizedPath.substringAfterLast('/')
                when {
                    isAccountFile(baseName) -> accountEntries.add(entry.name to readBoundedUtf8(zis))
                    isTweetsFile(baseName) -> tweetEntries.add(entry.name to readBoundedUtf8(zis))
                    isLikesFile(baseName) -> likeEntries.add(entry.name to readBoundedUtf8(zis))
                    isFollowingFile(baseName) -> followingEntries.add(entry.name to readBoundedUtf8(zis))
                    isDirectMessagesFile(baseName) -> dmEntries.add(entry.name to readBoundedUtf8(zis))
                    baseName.endsWith(".json") && !baseName.startsWith("manifest") -> {
                        val content = readBoundedUtf8(zis)
                        if (looksLikeCustomSnapshot(content)) {
                            snapshotEntries.add(entry.name to content)
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        if (!sawAnyZipEntry) {
            throw IllegalArgumentException("Unreadable or empty ZIP archive stream.")
        }

        val totalRecognized = accountEntries.size + tweetEntries.size + likeEntries.size +
            followingEntries.size + dmEntries.size + snapshotEntries.size
        if (totalRecognized == 0) {
            throw IllegalArgumentException(
                "Unsupported ZIP archive: no X archive files (tweets.js, following.js, like.js, direct-messages.js, account.js) or JSON snapshots found."
            )
        }

        val ctx = ParseContext()
        // Parse account.js and tweets.js first so selfHandle and accountId -> handle mappings are populated.
        for ((name, raw) in accountEntries) parseElementIntoContext(parseJsonPayload(raw, name), name, ctx)
        for ((name, raw) in tweetEntries) parseElementIntoContext(parseJsonPayload(raw, name), name, ctx)
        for ((name, raw) in likeEntries) parseElementIntoContext(parseJsonPayload(raw, name), name, ctx)
        for ((name, raw) in followingEntries) parseElementIntoContext(parseJsonPayload(raw, name), name, ctx)
        for ((name, raw) in dmEntries) parseElementIntoContext(parseJsonPayload(raw, name), name, ctx)
        for ((name, raw) in snapshotEntries) parseElementIntoContext(parseJsonPayload(raw, name), name, ctx)

        return ctx.toSnapshot()
    }

    fun parseFileContent(fileName: String, rawContent: String): ParsedGraphSnapshot {
        if (rawContent.isBlank()) {
            throw IllegalArgumentException("Archive file '$fileName' is empty.")
        }
        val element = parseJsonPayload(rawContent, fileName)
        val ctx = ParseContext()
        parseElementIntoContext(element, fileName, ctx)
        return ctx.toSnapshot()
    }

    fun parseJsonOrJs(rawContent: String, fileNameHint: String = ""): ParsedGraphSnapshot {
        return parseFileContent(fileNameHint.ifBlank { "snapshot.json" }, rawContent)
    }

    private fun parseJsonPayload(rawContent: String, fileName: String): JsonElement {
        val cleaned = stripYtdPrefix(rawContent)
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("Archive file '$fileName' contains no JSON payload.")
        }
        return try {
            json.parseToJsonElement(cleaned)
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Unreadable X archive JSON in '$fileName': ${ex.message}",
                ex
            )
        }
    }

    private fun parseElementIntoContext(element: JsonElement, fileName: String, ctx: ParseContext) {
        when (element) {
            is JsonObject -> {
                if ("contacts" in element || "posts" in element) {
                    parseCustomSnapshot(element, ctx)
                } else {
                    throw IllegalArgumentException(
                        "Unsupported JSON object in '$fileName': expected 'contacts' and/or 'posts' fields."
                    )
                }
            }
            is JsonArray -> {
                if (element.isEmpty()) return
                val baseName = fileName.replace('\\', '/').substringAfterLast('/').lowercase()
                var recognizedAny = false
                for (item in element) {
                    val obj = item as? JsonObject ?: continue
                    when {
                        "account" in obj -> {
                            recognizedAny = true
                            parseAccountObject(obj["account"] as? JsonObject ?: continue, ctx)
                        }
                        "tweet" in obj -> {
                            recognizedAny = true
                            parseTweetObject(obj["tweet"] as? JsonObject ?: continue, ctx)
                        }
                        "like" in obj -> {
                            recognizedAny = true
                            parseLikeObject(obj["like"] as? JsonObject ?: continue, ctx)
                        }
                        "following" in obj -> {
                            recognizedAny = true
                            parseFollowingObject(obj["following"] as? JsonObject ?: continue, ctx)
                        }
                        "dmConversation" in obj -> {
                            recognizedAny = true
                            parseDmConversationObject(obj["dmConversation"] as? JsonObject ?: continue, ctx)
                        }
                        isTweetsFile(baseName) && ("full_text" in obj || "text" in obj) -> {
                            recognizedAny = true
                            parseTweetObject(obj, ctx)
                        }
                        isLikesFile(baseName) && ("tweetId" in obj || "fullText" in obj) -> {
                            recognizedAny = true
                            parseLikeObject(obj, ctx)
                        }
                        isFollowingFile(baseName) && ("accountId" in obj || "handle" in obj || "screenName" in obj) -> {
                            recognizedAny = true
                            parseFollowingObject(obj, ctx)
                        }
                    }
                }
                if (!recognizedAny) {
                    throw IllegalArgumentException(
                        "Unsupported X archive array in '$fileName': unrecognized entry schema."
                    )
                }
            }
            else -> {
                throw IllegalArgumentException("Unsupported JSON root type in '$fileName'.")
            }
        }
    }

    private fun parseAccountObject(account: JsonObject, ctx: ParseContext) {
        val accountId = account.stringOf("accountId", "account_id", "id_str", "id")
        val username = account.stringOf("username", "screen_name", "handle")
        if (accountId.isNotBlank()) {
            ctx.selfAccountId = accountId
        }
        val cleanSelf = normalizeGraphHandle(username)
        if (cleanSelf.isNotBlank()) {
            ctx.selfHandle = cleanSelf
            if (accountId.isNotBlank()) {
                ctx.accountIdToHandle[accountId] = cleanSelf
            }
        }
    }

    private fun parseTweetObject(tweet: JsonObject, ctx: ParseContext) {
        val tweetId = tweet.stringOf("id_str", "id", "tweetId", "tweet_id")
        val fullText = tweet.stringOf("full_text", "text", "fullText")
        val createdAtMs = parseTimestampMs(tweet["created_at"] ?: tweet["createdAt"] ?: tweet["created_at_ms"])

        // Collect user_mentions metadata (also builds accountId -> screen_name map for following.js / DMs)
        val mentionNames = HashMap<String, String>()
        val mentionedHandles = ArrayList<String>()
        val entities = tweet["entities"] as? JsonObject
        val userMentions = entities?.get("user_mentions") as? JsonArray
        if (userMentions != null) {
            for (entry in userMentions) {
                val mentionObj = entry as? JsonObject ?: continue
                val screenName = normalizeGraphHandle(mentionObj.stringOf("screen_name", "screenName", "handle"))
                val name = mentionObj.stringOf("name", "display_name", "displayName")
                val idStr = mentionObj.stringOf("id_str", "id")
                if (screenName.isNotBlank()) {
                    if (idStr.isNotBlank()) {
                        ctx.accountIdToHandle[idStr] = screenName
                    }
                    if (name.isNotBlank()) {
                        mentionNames[screenName] = name
                    }
                    mentionedHandles.add(screenName)
                }
            }
        }

        val replyToHandle = normalizeGraphHandle(
            tweet.stringOf("in_reply_to_screen_name", "inReplyToScreenName")
        )
        val replyToUserId = tweet.stringOf("in_reply_to_user_id_str", "in_reply_to_user_id")
        if (replyToUserId.isNotBlank() && replyToHandle.isNotBlank()) {
            ctx.accountIdToHandle[replyToUserId] = replyToHandle
        }

        // Check if tweet explicitly names a non-self author (enriched exports / scraped snapshots)
        val userObj = tweet["user"] as? JsonObject
        val explicitAuthor = normalizeGraphHandle(
            userObj?.stringOf("screen_name", "screenName", "handle")
                ?: tweet.stringOf("author_handle", "authorHandle", "screen_name", "handle")
        )

        if (explicitAuthor.isNotBlank() && explicitAuthor != ctx.selfHandle) {
            val authorId = userObj?.stringOf("id_str", "id").orEmpty()
            if (authorId.isNotBlank()) {
                ctx.accountIdToHandle[authorId] = explicitAuthor
            }
            val isReplyToSelf = (ctx.selfHandle.isNotBlank() && replyToHandle == ctx.selfHandle) ||
                (tweet.booleanOf("is_reply_to_user", "isReplyToUser") == true)
            ctx.getOrCreateContact(explicitAuthor)?.mergeFrom(
                displayName = userObj?.stringOf("name", "display_name", "displayName").orEmpty(),
                bio = userObj?.stringOf("description", "bio").orEmpty(),
                followerCount = userObj?.intOf("followers_count", "followerCount", "follower_count") ?: 0,
                mutualRepliesDelta = if (isReplyToSelf) 1 else 0,
                interactionEpochMs = createdAtMs
            )
            if (tweetId.isNotBlank() && fullText.isNotBlank()) {
                ctx.putPost(
                    GraphPostRecord(
                        tweetId = tweetId,
                        handle = explicitAuthor,
                        text = fullText,
                        createdAtEpochMs = createdAtMs,
                        isReplyToUser = isReplyToSelf
                    )
                )
            }
            return
        }

        // Standard X archive tweets.js (archive owner's own tweets & replies)
        if (replyToHandle.isNotBlank() && replyToHandle != ctx.selfHandle) {
            ctx.getOrCreateContact(replyToHandle)?.mergeFrom(
                displayName = mentionNames[replyToHandle].orEmpty(),
                mutualRepliesDelta = 1,
                interactionEpochMs = createdAtMs
            )
            if (tweetId.isNotBlank() && fullText.isNotBlank()) {
                ctx.putPost(
                    GraphPostRecord(
                        tweetId = tweetId,
                        handle = replyToHandle,
                        text = fullText,
                        createdAtEpochMs = createdAtMs,
                        isReplyToUser = true
                    )
                )
            }
        }

        for (mentioned in mentionedHandles) {
            if (mentioned == replyToHandle || mentioned == ctx.selfHandle) continue
            ctx.getOrCreateContact(mentioned)?.mergeFrom(
                displayName = mentionNames[mentioned].orEmpty(),
                interactionEpochMs = createdAtMs
            )
        }
    }

    private fun parseLikeObject(like: JsonObject, ctx: ParseContext) {
        val tweetId = like.stringOf("tweetId", "tweet_id", "id_str", "id")
        val fullText = like.stringOf("fullText", "full_text", "text")
        val expandedUrl = like.stringOf("expandedUrl", "expanded_url", "url")
        val createdAtMs = parseTimestampMs(like["createdAt"] ?: like["created_at"] ?: like["created_at_ms"])

        val explicitHandle = normalizeGraphHandle(
            like.stringOf("handle", "screenName", "screen_name", "authorHandle", "author_handle")
        )
        val urlHandle = extractHandleFromStatusUrl(expandedUrl)
        val rtHandle = extractHandleFromRtPrefix(fullText)

        val resolvedHandle = when {
            explicitHandle.isNotBlank() -> explicitHandle
            urlHandle.isNotBlank() -> urlHandle
            rtHandle.isNotBlank() -> rtHandle
            else -> ""
        }

        if (resolvedHandle.isBlank() || resolvedHandle == ctx.selfHandle) return

        ctx.getOrCreateContact(resolvedHandle)?.mergeFrom(
            displayName = like.stringOf("displayName", "display_name", "name"),
            bio = like.stringOf("bio", "description"),
            followerCount = like.intOf("followerCount", "follower_count", "followers_count") ?: 0,
            interactionEpochMs = createdAtMs
        )

        if (tweetId.isNotBlank() && fullText.isNotBlank()) {
            ctx.putPost(
                GraphPostRecord(
                    tweetId = tweetId,
                    handle = resolvedHandle,
                    text = fullText,
                    createdAtEpochMs = createdAtMs,
                    isReplyToUser = false
                )
            )
        }
    }

    private fun parseFollowingObject(following: JsonObject, ctx: ParseContext) {
        val accountId = following.stringOf("accountId", "account_id", "id_str", "id")
        if (accountId.isNotBlank() && accountId == ctx.selfAccountId) return

        val explicitHandle = normalizeGraphHandle(
            following.stringOf("handle", "screenName", "screen_name", "username")
        )
        val linkHandle = extractHandleFromProfileUrl(following.stringOf("userLink", "user_link", "url"))
        val mappedHandle = if (accountId.isNotBlank()) ctx.accountIdToHandle[accountId].orEmpty() else ""

        val resolvedHandle = when {
            explicitHandle.isNotBlank() -> explicitHandle
            linkHandle.isNotBlank() -> linkHandle
            mappedHandle.isNotBlank() -> mappedHandle
            accountId.isNotBlank() && accountId.all { it.isDigit() } -> "id_$accountId"
            else -> ""
        }

        if (resolvedHandle.isBlank() || resolvedHandle == ctx.selfHandle) return
        if (accountId.isNotBlank() && !resolvedHandle.startsWith("id_")) {
            ctx.accountIdToHandle[accountId] = resolvedHandle
        }

        ctx.getOrCreateContact(resolvedHandle)?.mergeFrom(
            displayName = following.stringOf("displayName", "display_name", "name"),
            bio = following.stringOf("bio", "description"),
            followerCount = following.intOf("followerCount", "follower_count", "followers_count") ?: 0,
            mutualRepliesFloor = following.intOf("mutualRepliesCount", "mutual_replies_count") ?: 0,
            interactionEpochMs = parseTimestampMs(
                following["lastInteractionEpochMs"] ?: following["last_interaction_ms"]
            )
        )
    }

    private fun parseDmConversationObject(conversation: JsonObject, ctx: ParseContext) {
        val conversationId = conversation.stringOf("conversationId", "conversation_id")
        val messages = conversation["messages"] as? JsonArray ?: return

        val convParticipants = conversationId.split("-").filter { it.isNotBlank() }
        var fallbackPeerId = convParticipants.firstOrNull { it != ctx.selfAccountId }.orEmpty()

        for (entry in messages) {
            val wrapper = entry as? JsonObject ?: continue
            val msg = (wrapper["messageCreate"] as? JsonObject) ?: wrapper
            val senderId = msg.stringOf("senderId", "sender_id")
            val recipientId = msg.stringOf("recipientId", "recipient_id")
            val senderHandle = normalizeGraphHandle(msg.stringOf("senderHandle", "sender_handle"))
            val recipientHandle = normalizeGraphHandle(msg.stringOf("recipientHandle", "recipient_handle"))
            val createdAtMs = parseTimestampMs(msg["createdAt"] ?: msg["created_at"] ?: msg["created_at_ms"])

            if (senderId.isNotBlank() && senderHandle.isNotBlank()) {
                ctx.accountIdToHandle[senderId] = senderHandle
            }
            if (recipientId.isNotBlank() && recipientHandle.isNotBlank()) {
                ctx.accountIdToHandle[recipientId] = recipientHandle
            }

            val peerId = when {
                ctx.selfAccountId.isNotBlank() && senderId.isNotBlank() && senderId != ctx.selfAccountId -> senderId
                ctx.selfAccountId.isNotBlank() && recipientId.isNotBlank() && recipientId != ctx.selfAccountId -> recipientId
                fallbackPeerId.isNotBlank() -> fallbackPeerId
                senderId.isNotBlank() -> senderId
                else -> recipientId
            }
            if (fallbackPeerId.isBlank() && peerId.isNotBlank()) {
                fallbackPeerId = peerId
            }

            val peerHandle = when {
                senderHandle.isNotBlank() && senderHandle != ctx.selfHandle -> senderHandle
                recipientHandle.isNotBlank() && recipientHandle != ctx.selfHandle -> recipientHandle
                peerId.isNotBlank() && ctx.accountIdToHandle[peerId] != null -> ctx.accountIdToHandle[peerId]!!
                peerId.isNotBlank() && peerId.all { it.isDigit() } -> "id_$peerId"
                else -> ""
            }
            if (peerHandle.isBlank() || peerHandle == ctx.selfHandle) continue

            ctx.getOrCreateContact(peerHandle)?.mergeFrom(
                mutualRepliesDelta = 1,
                interactionEpochMs = createdAtMs
            )
        }
    }

    private fun parseCustomSnapshot(root: JsonObject, ctx: ParseContext) {
        val contactsArray = root["contacts"] as? JsonArray
        if (contactsArray != null) {
            for (item in contactsArray) {
                val obj = item as? JsonObject ?: continue
                val handle = normalizeGraphHandle(
                    obj.stringOf("handle", "screen_name", "screenName", "username")
                )
                if (handle.isBlank()) continue
                ctx.getOrCreateContact(handle)?.mergeFrom(
                    displayName = obj.stringOf("displayName", "display_name", "name"),
                    bio = obj.stringOf("bio", "description"),
                    followerCount = obj.intOf("followerCount", "follower_count", "followers_count") ?: 0,
                    mutualRepliesFloor = obj.intOf("mutualRepliesCount", "mutual_replies_count") ?: 0,
                    interactionEpochMs = parseTimestampMs(
                        obj["lastInteractionEpochMs"]
                            ?: obj["last_interaction_ms"]
                            ?: obj["lastInteractionMs"]
                    )
                )
            }
        }

        val postsArray = root["posts"] as? JsonArray
        if (postsArray != null) {
            for (item in postsArray) {
                val obj = item as? JsonObject ?: continue
                val tweetId = obj.stringOf("tweetId", "tweet_id", "id_str", "id")
                val handle = normalizeGraphHandle(
                    obj.stringOf("handle", "screen_name", "screenName", "author_handle", "authorHandle")
                )
                val text = obj.stringOf("text", "full_text", "fullText")
                if (tweetId.isBlank() || handle.isBlank() || text.isBlank()) continue

                val createdAtMs = parseTimestampMs(
                    obj["createdAtEpochMs"]
                        ?: obj["created_at_ms"]
                        ?: obj["createdAtMs"]
                        ?: obj["created_at"]
                        ?: obj["createdAt"]
                )
                val isReplyToUser = obj.booleanOf("isReplyToUser", "is_reply_to_user") ?: false

                ctx.getOrCreateContact(handle)?.mergeFrom(
                    mutualRepliesFloor = if (isReplyToUser) 1 else 0,
                    interactionEpochMs = createdAtMs
                )
                ctx.putPost(
                    GraphPostRecord(
                        tweetId = tweetId,
                        handle = handle,
                        text = text,
                        createdAtEpochMs = createdAtMs,
                        isReplyToUser = isReplyToUser
                    )
                )
            }
        }
    }

    private fun extractHandleFromStatusUrl(url: String): String {
        if (url.isBlank()) return ""
        val candidate = STATUS_URL_HANDLE_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return ""
        val clean = normalizeGraphHandle(candidate)
        return if (clean in RESERVED_PATH_SEGMENTS) "" else clean
    }

    private fun extractHandleFromProfileUrl(url: String): String {
        if (url.isBlank()) return ""
        val candidate = PROFILE_URL_HANDLE_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return ""
        val clean = normalizeGraphHandle(candidate)
        return if (clean in RESERVED_PATH_SEGMENTS) "" else clean
    }

    private fun extractHandleFromRtPrefix(text: String): String {
        if (text.isBlank()) return ""
        val candidate = RT_PREFIX_REGEX.find(text.trim())?.groupValues?.getOrNull(1) ?: return ""
        return normalizeGraphHandle(candidate)
    }

    internal fun parseTimestampMs(element: JsonElement?): Long {
        val primitive = element as? JsonPrimitive ?: return 0L
        primitive.longOrNull?.let { num ->
            return when {
                num <= 0L -> 0L
                num < 10_000_000_000L -> num * 1000L // epoch seconds -> ms
                else -> num
            }
        }
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { num ->
            return when {
                num <= 0L -> 0L
                num < 10_000_000_000L -> num * 1000L
                else -> num
            }
        }
        // Try ISO-8601 Instant / OffsetDateTime
        try {
            return Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        // Try Twitter RFC-2822 format: "Tue Oct 15 18:30:00 +0000 2024"
        try {
            return ZonedDateTime.parse(raw, TWITTER_DATE_FORMATTER).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        return 0L
    }

    private fun isAccountFile(baseName: String): Boolean =
        baseName == "account.js" || baseName.startsWith("account-part")

    private fun isTweetsFile(baseName: String): Boolean =
        baseName == "tweets.js" || baseName == "tweet.js" ||
            baseName.startsWith("tweets-part") || baseName.startsWith("tweet-part")

    private fun isLikesFile(baseName: String): Boolean =
        baseName == "like.js" || baseName == "likes.js" ||
            baseName.startsWith("like-part") || baseName.startsWith("likes-part")

    private fun isFollowingFile(baseName: String): Boolean =
        baseName == "following.js" || baseName.startsWith("following-part")

    private fun isDirectMessagesFile(baseName: String): Boolean =
        baseName == "direct-messages.js" || baseName == "direct_messages.js" ||
            baseName.startsWith("direct-messages-part") || baseName.startsWith("direct_messages-part")

    private fun looksLikeCustomSnapshot(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("{") && (trimmed.contains("\"contacts\"") || trimmed.contains("\"posts\""))
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) {
                throw IllegalArgumentException("Archive entry exceeds maximum supported size ($MAX_ENTRY_BYTES bytes).")
            }
            out.write(buf, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun JsonObject.stringOf(vararg keys: String): String {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    private fun JsonObject.intOf(vararg keys: String): Int? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            prim.intOrNull?.let { return it }
            prim.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.booleanOf(vararg keys: String): Boolean? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            prim.booleanOrNull?.let { return it }
            when (prim.contentOrNull?.trim()?.lowercase()) {
                "true", "1" -> return true
                "false", "0" -> return false
            }
        }
        return null
    }
}
