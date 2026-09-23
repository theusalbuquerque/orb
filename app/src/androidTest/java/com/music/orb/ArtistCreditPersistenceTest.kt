package com.music.orb

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.orb.data.ArtistCreditResolver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run on an Android test device, with catalogue loading otherwise idle. */
@RunWith(AndroidJUnit4::class)
class ArtistCreditPersistenceTest {
    @Test
    fun blockedDiskDoesNotBlockReadersAndLaterChangesArePersisted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = context.getSharedPreferences("artist_persistence_test", Context.MODE_PRIVATE)
        isolated.edit().clear().commit()
        val field = ArtistCreditResolver::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val original = field.get(ArtistCreditResolver)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val prefix = UUID.randomUUID().toString()
        val first = "$prefix First"
        val second = "$prefix Second"
        val band = "$prefix Mumford & Sons"
        val counting = object : SharedPreferences by isolated {
            override fun edit(): SharedPreferences.Editor {
                val delegate = isolated.edit()
                return object : SharedPreferences.Editor by delegate {
                    // Preserve wrapping when the production writer chains puts.
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        if (writes.incrementAndGet() == 1) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        return delegate.commit()
                    }
                }
            }
        }
        try {
            field.set(ArtistCreditResolver, counting)
            ArtistCreditResolver.registerVerifiedArtist(first)
            assertTrue("Writer did not start", entered.await(5, TimeUnit.SECONDS))
            // A deliberately stalled disk writer must not hold the parser's caller.
            executor.submit {
                repeat(1000) { ArtistCreditResolver.registerVerifiedArtist(first) }
                ArtistCreditResolver.registerVerifiedArtist(second)
                ArtistCreditResolver.registerVerifiedArtist(band)
                assertEquals(listOf(band), ArtistCreditResolver.creditsForCounting(band))
            }.get(2, TimeUnit.SECONDS)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var saved = false
            while (System.nanoTime() < deadline) {
                val json = JSONObject(isolated.getString("verified_artists_v1", "{}")!!)
                saved = json.keys().asSequence().any { json.optString(it) == second }
                if (saved) break
                Thread.sleep(25)
            }
            assertTrue("Update arriving during a write was lost", saved)
            Thread.sleep(750)
            val settledWrites = writes.get()
            repeat(1000) { ArtistCreditResolver.registerVerifiedArtist(first) }
            Thread.sleep(750)
            assertEquals("Unchanged artists triggered more writes", settledWrites, writes.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            field.set(ArtistCreditResolver, original)
        }
    }
}
