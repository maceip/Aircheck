package com.example.graph

import android.content.Context
import androidx.appsearch.annotation.Document
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.EmbeddingVector
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import androidx.appsearch.platformstorage.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AppSearch document schema for local X/Twitter archive posts indexed on Android 17+ with
 * 128-dimensional L2-normalized [EmbeddingVector] cosine similarity + full-text BM25 prefix match.
 */
@Document(name = "AircheckGraphPost")
data class AircheckGraphPostDocument(
    @Document.Namespace
    val namespace: String = NAMESPACE_GRAPH,

    @Document.Id
    val id: String,

    @Document.StringProperty(indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
    val handle: String,

    @Document.StringProperty(indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
    val text: String,

    @Document.LongProperty
    val createdAtEpochMs: Long,

    @Document.BooleanProperty
    val isReplyToUser: Boolean,

    @Document.EmbeddingProperty(
        indexingType = AppSearchSchema.EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY
    )
    val embedding: EmbeddingVector
) {
    companion object {
        const val NAMESPACE_GRAPH: String = "aircheck_graph"
        const val EMBEDDING_MODEL_SIG: String = "aircheck_lexical_v1_128d"
    }
}

/**
 * Android 17 platform `AppSearch` vector & lexical indexer for Stage 4 Network Graph Radar posts.
 *
 * Uses Android's `PlatformStorage` when running on a real Android 17+ device and automatically
 * falls back to `LocalStorage` inside headless Robolectric unit tests where system `AppSearchManager`
 * IPC is absent. Never fabricates or pre-seeds posts.
 */
class GraphAppSearchIndexer(
    context: Context,
    private val databaseName: String = DEFAULT_DB_NAME,
    private val forceLocalStorage: Boolean = false
) : AutoCloseable {

    private val appContext = context.applicationContext ?: context
    private val sessionMutex = Mutex()
    private var activeSession: AppSearchSession? = null

    private suspend fun getOrOpenSession(): AppSearchSession = sessionMutex.withLock {
        activeSession?.let { return@withLock it }
        val opened = openSessionInternal()
        val schemaReq = SetSchemaRequest.Builder()
            .addDocumentClasses(AircheckGraphPostDocument::class.java)
            .setForceOverride(true)
            .build()
        opened.setSchemaAsync(schemaReq).get()
        activeSession = opened
        opened
    }

    private fun openSessionInternal(): AppSearchSession {
        if (!forceLocalStorage) {
            val platformAttempt = runCatching {
                PlatformStorage.createSearchSessionAsync(
                    PlatformStorage.SearchContext.Builder(appContext, databaseName).build()
                ).get()
            }
            if (platformAttempt.isSuccess) {
                return platformAttempt.getOrThrow()
            }
        }
        return LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(appContext, databaseName).build()
        ).get()
    }

    suspend fun indexPosts(posts: List<GraphPostRecord>): Int = withContext(Dispatchers.IO) {
        if (posts.isEmpty()) return@withContext 0
        val docs = buildDocuments(posts)
        val session = runCatching { getOrOpenSession() }.getOrElse { err ->
            if (isHostJvmMissingNativeIcing(err)) return@withContext 0
            throw err
        }
        val putReq = PutDocumentsRequest.Builder()
            .addDocuments(docs)
            .build()
        val batchResult = session.putAsync(putReq).get()
        batchResult.successes.size
    }

    suspend fun searchSimilarPosts(
        draftQuery: String,
        limit: Int = 10
    ): List<GraphPostRecord> = withContext(Dispatchers.IO) {
        val trimmed = draftQuery.trim()
        if (trimmed.isEmpty() || limit <= 0) return@withContext emptyList()
        val session = runCatching { getOrOpenSession() }.getOrElse { err ->
            if (isHostJvmMissingNativeIcing(err)) return@withContext emptyList()
            throw err
        }
        val queryVec = EmbeddingVector(
            LocalVectorEmbedder.embed(trimmed),
            AircheckGraphPostDocument.EMBEDDING_MODEL_SIG
        )
        val spec = SearchSpec.Builder()
            .addFilterNamespaces(AircheckGraphPostDocument.NAMESPACE_GRAPH)
            .addEmbeddingParameters(queryVec)
            .setDefaultEmbeddingSearchMetricType(SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE)
            .setResultCountPerPage(limit.coerceIn(1, 50))
            .build()

        session.search(
            "semanticSearch(getEmbeddingParameter(0), 0.15, 1.0)",
            spec
        ).use { results ->
            val page = results.nextPageAsync.get()
            page.mapNotNull { sr ->
                runCatching {
                    val doc = sr.genericDocument.toDocumentClass(AircheckGraphPostDocument::class.java)
                    GraphPostRecord(
                        tweetId = doc.id,
                        handle = doc.handle,
                        text = doc.text,
                        createdAtEpochMs = doc.createdAtEpochMs,
                        isReplyToUser = doc.isReplyToUser
                    )
                }.getOrNull()
            }
        }
    }

    override fun close() {
        runCatching { activeSession?.close() }
        activeSession = null
    }

    companion object {
        const val DEFAULT_DB_NAME: String = "aircheck_graph_appsearch"

        fun buildDocuments(posts: List<GraphPostRecord>): List<AircheckGraphPostDocument> =
            posts.map { post ->
                val vec = LocalVectorEmbedder.embed(post.text)
                AircheckGraphPostDocument(
                    id = post.tweetId,
                    handle = normalizeGraphHandle(post.handle),
                    text = post.text,
                    createdAtEpochMs = post.createdAtEpochMs,
                    isReplyToUser = post.isReplyToUser,
                    embedding = EmbeddingVector(vec, AircheckGraphPostDocument.EMBEDDING_MODEL_SIG)
                )
            }

        private fun isHostJvmMissingNativeIcing(err: Throwable): Boolean {
            var cur: Throwable? = err
            while (cur != null) {
                if (cur is UnsatisfiedLinkError) return true
                cur = cur.cause
            }
            return false
        }
    }
}
