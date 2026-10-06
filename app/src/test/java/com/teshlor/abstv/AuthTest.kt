package com.teshlor.abstv

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthTest {
    private var server: MockWebServer? = null
    private val refreshCalls = AtomicInteger()
    private val expired = AtomicInteger()

    @After fun tearDown() { server?.shutdown() }

    private fun start(refresh: (RecordedRequest) -> MockResponse): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/refresh" -> { refreshCalls.incrementAndGet(); refresh(request) }
                "/login" -> MockResponse().setBody(
                    """{"user":{"id":"u","username":"evan","token":"legacy","accessToken":"acc1","refreshToken":"ref1"}}""",
                )
                else -> if (request.getHeader("Authorization") == "Bearer new-acc") MockResponse().setBody("""{"username":"evan","mediaProgress":[]}""")
                else MockResponse().setResponseCode(401)
            }
        }
        s.start()
        server = s
        return s
    }

    private val okRefresh = { _: RecordedRequest ->
        Thread.sleep(150)
        MockResponse().setBody("""{"user":{"username":"evan","accessToken":"new-acc","refreshToken":"new-ref"}}""")
    }

    private fun session(s: MockWebServer, access: String? = "old-acc", refresh: String? = "old-ref"): Pair<AbsApi, MemoryTokenStore> {
        val store = MemoryTokenStore(s.url("/").toString(), access, refresh, "evan")
        return AbsApi(s.url("/").toString(), AuthSession(store) { expired.incrementAndGet() }) to store
    }

    @Test fun loginSendsReturnTokensHeaderAndParsesPair() = runBlocking {
        val s = start(okRefresh)
        val r = AbsApi(s.url("/").toString()).login("evan", "pw")
        assertEquals("acc1", r.accessToken)
        assertEquals("ref1", r.refreshToken)
        assertEquals("evan", r.username)
        assertEquals("true", s.takeRequest().getHeader("x-return-tokens"))
    }

    @Test fun unauthorizedRefreshesRetriesAndSavesRotatedPair() = runBlocking {
        val s = start(okRefresh)
        val (api, store) = session(s)
        val me = api.me()
        assertEquals("evan", me.username)
        assertEquals(1, refreshCalls.get())
        assertEquals("new-acc", store.accessToken)
        assertEquals("new-ref", store.refreshToken)
        val first = s.takeRequest(); assertEquals("Bearer old-acc", first.getHeader("Authorization"))
        val refresh = s.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        assertEquals("old-ref", refresh.getHeader("x-refresh-token"))
        val retry = s.takeRequest(); assertEquals("Bearer new-acc", retry.getHeader("Authorization"))
        assertEquals(0, expired.get())
    }

    @Test fun concurrentUnauthorizedCallsRefreshOnce() {
        val s = start(okRefresh)
        val (api, store) = session(s)
        val n = 6
        val pool = Executors.newFixedThreadPool(n)
        val gate = CountDownLatch(1)
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(n)
        repeat(n) {
            pool.execute {
                gate.await()
                api.client.newCall(Request.Builder().url(api.baseUrl + "/api/me").build()).execute().use { codes += it.code }
                done.countDown()
            }
        }
        gate.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, refreshCalls.get())
        assertEquals(List(n) { 200 }, codes.toList())
        assertEquals("new-ref", store.refreshToken)
    }

    @Test fun refreshRejectedClearsTokensAndSignalsExpiry() = runBlocking {
        val s = start { MockResponse().setResponseCode(401).setBody("""{"error":"expired"}""") }
        val (api, store) = session(s)
        try { api.me(); org.junit.Assert.fail("expected 401") } catch (e: AbsHttpException) { assertEquals(401, e.code) }
        assertEquals(1, expired.get())
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertEquals(s.url("/").toString(), store.server) // server kept for the login form
    }

    @Test fun networkErrorDuringRefreshKeepsSession() = runBlocking {
        val s = start { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START) }
        val (api, store) = session(s)
        try { api.me(); org.junit.Assert.fail("expected failure") } catch (e: AbsHttpException) { assertEquals(401, e.code) }
        assertEquals(0, expired.get())
        assertEquals("old-acc", store.accessToken)
        assertEquals("old-ref", store.refreshToken)
    }

    @Test fun serverErrorDuringRefreshKeepsSession() = runBlocking {
        val s = start { MockResponse().setResponseCode(503) }
        val (api, store) = session(s)
        try { api.me(); org.junit.Assert.fail("expected failure") } catch (e: AbsHttpException) { }
        assertEquals(0, expired.get())
        assertEquals("old-ref", store.refreshToken)
    }

    @Test fun legacyTokenWithoutRefreshExpiresOnUnauthorized() = runBlocking {
        val s = start(okRefresh)
        val (api, store) = session(s, access = "legacy", refresh = null)
        try { api.me(); org.junit.Assert.fail("expected 401") } catch (e: AbsHttpException) { }
        assertEquals(0, refreshCalls.get())
        assertEquals(1, expired.get())
        assertNull(store.accessToken)
    }

    @Test fun trackRequestsCarryBearerHeaderNotQueryToken() {
        val s = start(okRefresh)
        val (api, _) = session(s, access = "new-acc")
        val url = api.trackUrl("/api/items/li-1/file/123")
        assertFalse(url.contains("token"))
        api.client.newCall(Request.Builder().url(url).build()).execute().close()
        val req = s.takeRequest()
        assertEquals("Bearer new-acc", req.getHeader("Authorization"))
        assertNull(req.requestUrl!!.queryParameter("token"))
    }

    @Test fun trackRequestAfterExpiryRefreshesTransparently() {
        val s = start(okRefresh)
        val (api, store) = session(s) // stale access token
        api.client.newCall(Request.Builder().url(api.trackUrl("/api/items/li-1/file/123")).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals("new-acc", store.accessToken)
    }

    @Test fun tokenIsNotSentToOtherHosts() {
        val s = start(okRefresh)
        val other = MockWebServer().also { it.enqueue(MockResponse()); it.start() }
        try {
            val (api, _) = session(s, access = "new-acc")
            api.client.newCall(Request.Builder().url(other.url("/x")).build()).execute().close()
            assertNull(other.takeRequest().getHeader("Authorization"))
        } finally { other.shutdown() }
    }

    @Test fun staleRefreshFailureDoesNotWipeNewerLogin() = runBlocking {
        lateinit var store: MemoryTokenStore
        val s = start {
            // A fresh login lands while the refresh is in flight, then the old refresh token is rejected.
            store.saveTokens("fresh-acc", "fresh-ref")
            MockResponse().setResponseCode(401)
        }
        val pair = session(s); store = pair.second
        try { pair.first.me(); org.junit.Assert.fail("expected 401") } catch (e: AbsHttpException) { }
        assertEquals(0, expired.get())
        assertEquals("fresh-acc", store.accessToken)
        assertEquals("fresh-ref", store.refreshToken)
    }

    @Test fun expiredSessionErrorIsNotOverwrittenByTheTriggering401() {
        assertFalse(shouldShowError(true, AbsHttpException(401)))
        assertTrue(shouldShowError(false, AbsHttpException(401))) // e.g. a bad password on the login form
        assertTrue(shouldShowError(true, AbsHttpException(500)))
        assertTrue(shouldShowError(true, java.io.IOException("offline")))
    }
}
