package com.baruchiro.podcatcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baruchiro.podcatcher.conv.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device seam the JVM tests cannot cover: Keystore AES/GCM round-trip.
 * Uses a DUMMY value — no real credential ever appears in CI.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {

    @Test
    fun putGetRemove_roundTrips_onRealKeystore() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecretStore(ctx)
        store.remove("test_key")
        assertNull(store.get("test_key"))
        store.put("test_key", "dummy-not-a-real-secret-123")
        assertEquals("dummy-not-a-real-secret-123", store.get("test_key"))
        // Fresh instance reads the same persisted entry (simulated restart).
        assertEquals("dummy-not-a-real-secret-123", SecretStore(ctx).get("test_key"))
        store.remove("test_key")
        assertNull(SecretStore(ctx).get("test_key"))
    }
}
