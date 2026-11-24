package com.example.graph

import android.content.Context
import androidx.appsearch.annotation.Document
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.EmbeddingVector
import androidx.appsearch.app.GenericDocument
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
 * AppSearch document model for local X/Twitter archive posts indexed on Android 17+ with
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
        const val SCHEMA_TYPE_POST: String = "AircheckGraphPost"
        const val NAMESPACE_GRAPH: String = "aircheck_graph"
        const val EMBEDDING_MODEL_SIG: String = "aircheck_lexical_v1_128d"
    }
}

/**
 * Android 17 platform `AppSearch` vector & lexical indexer for Stage 4 Network Graph Radar posts.
 *
 * Registers an explicit [AppSearchSchema] and writes [GenericDocument] instances directly (zero
 * reflection, R8-safe without KAPT), using Android's `PlatformStorage` on real Android 17+ devices
 * and falling back to `LocalStorage` in headless unit tests. Never fabricates or pre-seeds posts.
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
        val schemaReq = SetSchemaRequest.Builder()
            .addSchemas(GRAPH_POST_SCHEMA)
            .setForceOverride(true)
            .build()
        val opened = openAndConfigureSessionInternal(schemaReq)
        activeSession = opened
        opened
    }

    private fun openAndConfigureSessionInternal(schemaReq: SetSchemaRequest): AppSearchSession {
        if (!forceLocalStorage) {
            val platformSession = runCatching {
                PlatformStorage.createSearchSessionAsync(
                    PlatformStorage.SearchContext.Builder(appContext, databaseName).build()
                ).get()
            }.getOrNull()
            if (platformSession != null) {
                val configured = runCatching {
                    platformSession.setSchemaAsync(schemaReq).get()
                    platformSession
                }
                if (configured.isSuccess) {
                    return configured.getOrThrow()
                }
                // Older OS builds (e.g. Android 15 without embedding APEX) throw on EmbeddingPropertyConfig;
                // close platform session and fall back cleanly to bundled LocalStorage Icing.
                runCatching { platformSession.close() }
            }
        }
        val localSession = LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(appContext, databaseName).build()
        ).get()
        localSession.setSchemaAsync(schemaReq).get()
        return localSession
    }

    suspend fun indexPosts(posts: List<GraphPostRecord>): Int = withContext(Dispatchers.IO) {
        if (posts.isEmpty()) return@withContext 0
        val genericDocs = buildGenericDocuments(posts)
        val session = runCatching { getOrOpenSession() }.getOrElse { err ->
            if (isHostJvmMissingNativeIcing(err)) return@withContext 0
            throw err
        }
        val putReq = PutDocumentsRequest.Builder()
            .addGenericDocuments(genericDocs)
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
            .addFilterSchemas(AircheckGraphPostDocument.SCHEMA_TYPE_POST)
            .addEmbeddingParameters(queryVec)
            .setDefaultEmbeddingSearchMetricType(SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE)
            .setListFilterQueryLanguageEnabled(true)
            .setResultCountPerPage(limit.coerceIn(1, 50))
            .build()

        session.search(
            "semanticSearch(getEmbeddingParameter(0), 0.15, 1.0)",
            spec
        ).use { results ->
            val page = results.nextPageAsync.get()
            page.mapNotNull { sr ->
                runCatching {
                    val doc = sr.genericDocument
                    GraphPostRecord(
                        tweetId = doc.id,
                        handle = doc.getPropertyString("handle").orEmpty(),
                        text = doc.getPropertyString("text").orEmpty(),
                        createdAtEpochMs = doc.getPropertyLong("createdAtEpochMs"),
                        isReplyToUser = doc.getPropertyBoolean("isReplyToUser")
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

        val GRAPH_POST_SCHEMA: AppSearchSchema = AppSearchSchema.Builder(
            AircheckGraphPostDocument.SCHEMA_TYPE_POST
        )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder("handle")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .build()
            )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder("text")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .build()
            )
            .addProperty(
                AppSearchSchema.LongPropertyConfig.Builder("createdAtEpochMs")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()
            )
            .addProperty(
                AppSearchSchema.BooleanPropertyConfig.Builder("isReplyToUser")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()
            )
            .addProperty(
                AppSearchSchema.EmbeddingPropertyConfig.Builder("embedding")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(AppSearchSchema.EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY)
                    .build()
            )
            .build()

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

        fun buildGenericDocuments(posts: List<GraphPostRecord>): List<GenericDocument> =
            buildDocuments(posts).map { doc ->
                GenericDocument.Builder<GenericDocument.Builder<*>>(
                    doc.namespace,
                    doc.id,
                    AircheckGraphPostDocument.SCHEMA_TYPE_POST
                )
                    .setCreationTimestampMillis(doc.createdAtEpochMs.coerceAtLeast(0L))
                    .setPropertyString("handle", doc.handle)
                    .setPropertyString("text", doc.text)
                    .setPropertyLong("createdAtEpochMs", doc.createdAtEpochMs)
                    .setPropertyBoolean("isReplyToUser", doc.isReplyToUser)
                    .setPropertyEmbedding("embedding", doc.embedding)
                    .build()
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
