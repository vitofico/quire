package io.theficos.ereader.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.HighlightEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import io.theficos.ereader.data.local.db.compareHighlights
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HighlightSyncerTest {
    private lateinit var db: EReaderDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: SyncClient
    private var scope = "account-a"
    private val json = Json { ignoreUnknownKeys = true }
    private val id = "14300000-0000-4000-8000-000000000001"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        server = MockWebServer().also { it.start() }
        client = SyncClient({ server.url("/").toString() }, OkHttpClient())
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private fun syncer() = HighlightSyncer(client, db.highlightSyncDao(), db.syncStateDao()) {
        HighlightSyncSession(scope, server.url("/").toString(), "Bearer $scope-secret")
    }

    private fun row(owner: String = scope) = HighlightEntity(
        id = id, metadataId = "book", contentHash = "hash", locator = "  {\"href\":\"a.xhtml\"} ",
        text = "Café ☺", colour = "YELLOW", bookTitle = "Retained book", bookAuthor = null,
        createdAt = 10, updatedAt = 10, ownerScope = owner,
    )

    private fun page(items: List<HighlightItemDto> = emptyList(), cursor: String = "0", more: Boolean = false) =
        MockResponse().setBody(json.encodeToString(HighlightPullResponse.serializer(), HighlightPullResponse(items, cursor, more)))

    private fun ack(items: List<HighlightItemDto>) = MockResponse().setBody(json.encodeToString(
        HighlightPushResponse.serializer(), HighlightPushResponse(items.map { HighlightPushResult("accepted", it) }),
    ))

    @Test fun `shared server fixture preserves opaque locator quote metadata and tombstone without document`() = runTest {
        val fixture = json.parseToJsonElement(requireNotNull(javaClass.getResource("/highlight-sync.json")).readText()).jsonObject
        val expected = json.decodeFromJsonElement(HighlightPullResponse.serializer(), fixture.getValue("pull"))
        server.enqueue(MockResponse().setBody(fixture.getValue("pull").toString()))
        assertThat(syncer().runOnce()).isInstanceOf(SyncResult.Success::class.java)
        val stored = db.highlightSyncDao().find(scope, id)!!
        assertThat(stored.toSyncDto()).isEqualTo(expected.items.single())
        assertThat(db.highlightSyncDao().dirty(scope)).isEmpty()
        assertThat(db.syncStateDao().lastPulled("highlights:$scope")).isEqualTo(2)
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer account-a-secret")
    }

    @Test fun `successful endpoint claims and uploads offline rows but never another account's rows`() = runTest {
        val offline = row("")
        db.highlightDao().insert(offline)
        db.highlightDao().insert(row("other-account").copy(id = "14300000-0000-4000-8000-000000000002"))
        server.enqueue(page())
        server.enqueue(ack(listOf(offline.toSyncDto())))
        server.enqueue(page(cursor = "1"))
        assertThat(syncer().runOnce()).isInstanceOf(SyncResult.Success::class.java)
        assertThat(db.highlightSyncDao().unowned()).isEmpty()
        assertThat(db.highlightSyncDao().dirty(scope)).isEmpty()
        server.takeRequest()
        val pushed = json.decodeFromString(HighlightPushBody.serializer(), server.takeRequest().body.readUtf8())
        assertThat(pushed.items.map { it.id }).containsExactly(id)
    }

    @Test fun `unsupported or unauthorized endpoint does not claim offline rows or acknowledge dirty rows`() = runTest {
        for (code in listOf(404, 405, 401)) {
            db.highlightSyncDao().put(row(""))
            server.enqueue(MockResponse().setResponseCode(code))
            val result = syncer().runOnce()
            assertThat(result is SyncResult.Success).isEqualTo(code != 401)
            assertThat(db.highlightSyncDao().unowned()).hasSize(1)
            assertThat(db.syncStateDao().lastPulled("highlights:$scope")).isNull()
        }
    }

    @Test fun `same UUID lives independently per account and cursors do not skip returning account`() = runTest {
        val original = row().copy(syncedAt = 10)
        db.highlightDao().insert(original)
        scope = "account-b"
        val incoming = row().copy(text = "B's private quote")
        server.enqueue(page(listOf(incoming.toSyncDto()), "4"))
        syncer().runOnce()
        assertThat(db.highlightSyncDao().find("account-a", id)).isEqualTo(original)
        assertThat(db.highlightSyncDao().find("account-b", id)!!.text).isEqualTo("B's private quote")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("since")).isEqualTo("0")
        scope = "account-a"
        server.enqueue(page(cursor = "0"))
        syncer().runOnce()
        assertThat(server.takeRequest().requestUrl!!.queryParameter("since")).isEqualTo("0")
    }

    @Test fun `switching account while authenticated preflight is in flight cannot claim or push`() = runTest {
        db.highlightDao().insert(row(""))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                scope = "account-b"
                assertThat(request.getHeader("Authorization")).isEqualTo("Bearer account-a-secret")
                return page()
            }
        }
        syncer().runOnce()
        assertThat(db.highlightSyncDao().unowned()).hasSize(1)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `acknowledgement arriving after an edit cannot mark the new version synced`() = runTest {
        val sent = row()
        db.highlightDao().insert(sent)
        var posts = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "GET" -> page()
                else -> {
                    posts++
                    if (posts == 1) {
                        runBlocking { db.highlightDao().setColour(id, "PINK", 10, scope) }
                        ack(listOf(sent.toSyncDto()))
                    } else MockResponse().setResponseCode(500)
                }
            }
        }
        assertThat(syncer().runOnce()).isInstanceOf(SyncResult.HttpFailure::class.java)
        val dirty = db.highlightSyncDao().dirty(scope).single()
        assertThat(dirty.updatedAt).isEqualTo(11)
        assertThat(dirty.colour).isEqualTo("PINK")
        assertThat(dirty.syncedAt).isNull()
    }

    @Test fun `incomplete acknowledgement is rejected and dirty snapshot retained`() = runTest {
        db.highlightDao().insert(row())
        server.enqueue(page())
        server.enqueue(ack(emptyList()))
        assertThat(syncer().runOnce()).isInstanceOf(SyncResult.HttpFailure::class.java)
        assertThat(db.highlightSyncDao().dirty(scope)).hasSize(1)
    }

    @Test fun `cursor reset after server restore and paged pulls commit one page at a time`() = runTest {
        db.highlightSyncDao().setCursor("highlights:$scope", 100)
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(page(listOf(row().toSyncDto()), "1", true))
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(syncer().runOnce()).isInstanceOf(SyncResult.HttpFailure::class.java)
        assertThat(db.syncStateDao().lastPulled("highlights:$scope")).isEqualTo(1)
        assertThat(db.highlightSyncDao().find(scope, id)).isNotNull()
        assertThat(server.takeRequest().requestUrl!!.queryParameter("since")).isEqualTo("100")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("since")).isEqualTo("0")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("since")).isEqualTo("1")
    }

    @Test fun `equal timestamp tombstone beats dirty live row and stale live response cannot resurrect`() = runTest {
        db.highlightDao().insert(row())
        val deleted = row().copy(deletedAt = 10)
        server.enqueue(page(listOf(deleted.toSyncDto()), "1"))
        syncer().runOnce()
        assertThat(db.highlightSyncDao().find(scope, id)!!.deletedAt).isEqualTo(10)
        server.enqueue(page(listOf(row().toSyncDto()), "2"))
        syncer().runOnce()
        assertThat(db.highlightSyncDao().find(scope, id)!!.deletedAt).isEqualTo(10)
        assertThat(db.highlightSyncDao().dirty(scope)).isEmpty()
    }

    @Test fun `remote edit committed during push is pulled in the same run`() = runTest {
        val sent = row()
        db.highlightDao().insert(sent)
        server.enqueue(page())
        server.enqueue(ack(listOf(sent.toSyncDto())))
        val remote = sent.copy(text = "Concurrent remote edit", updatedAt = 20)
        server.enqueue(page(listOf(remote.toSyncDto()), "2"))
        assertThat(syncer().runOnce()).isInstanceOf(SyncResult.Success::class.java)
        assertThat(db.highlightSyncDao().find(scope, id)!!.text).isEqualTo(remote.text)
        assertThat(db.syncStateDao().lastPulled("highlights:$scope")).isEqualTo(2)
    }

    @Test fun `shared server conflict vectors use the same deterministic comparator`() {
        val fixture = json.parseToJsonElement(requireNotNull(javaClass.getResource("/highlight-sync.json")).readText()).jsonObject
        for (element in fixture.getValue("conflict_cases").jsonArray) {
            val case = element.jsonObject
            val left = json.decodeFromJsonElement(HighlightItemDto.serializer(), case.getValue("left")).toEntity(scope)
            val right = json.decodeFromJsonElement(HighlightItemDto.serializer(), case.getValue("right")).toEntity(scope)
            val actual = compareHighlights(left, right).compareTo(0)
            assertWithMessage(case.getValue("name").jsonPrimitive.content).that(actual)
                .isEqualTo(case.getValue("expected_order").jsonPrimitive.int)
        }
    }

    @Test fun `private highlight redirect is not followed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/leaked")))
        assertThat(syncer().runOnce()).isInstanceOf(SyncResult.HttpFailure::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
