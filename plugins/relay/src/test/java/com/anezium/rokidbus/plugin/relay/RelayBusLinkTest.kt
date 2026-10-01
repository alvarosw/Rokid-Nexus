package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayBusLinkTest {
    private class Service(val instance: Int)

    /**
     * Android's side of it: one service instance, created once something binds it and destroyed
     * once nothing does, with both steps posted to the main looper. Each instance registers one
     * client with the hub for its lifetime.
     */
    private class Process {
        var bindings = 0
        var service: Service? = null
        var instances = 0
        val registrations = mutableListOf<Service>()
        val looper = ArrayDeque<() -> Unit>()
        var bindSucceeds = true
        var bandDetached = 0
        lateinit var link: RelayBusLink<Service>

        fun bind(): Boolean {
            if (!bindSucceeds) return false
            bindings += 1
            looper.addLast(::createIfBound)
            return true
        }

        fun unbind() {
            bindings -= 1
            looper.addLast(::destroyIfUnbound)
        }

        fun run() {
            while (looper.isNotEmpty()) looper.removeFirst()()
            assertTrue("never two registrations", registrations.size <= 1)
        }

        private fun createIfBound() {
            if (bindings == 0 || service != null) return
            val created = Service(++instances)
            service = created
            registrations += created
            link.onServiceCreated(created)
        }

        private fun destroyIfUnbound() {
            val current = service ?: return
            if (bindings > 0) return
            service = null
            if (link.onServiceDestroyed(current)) bandDetached += 1
            registrations -= current
        }
    }

    private val process = Process()
    private val link = RelayBusLink(
        bind = process::bind,
        unbind = process::unbind,
        liveService = { process.service },
    ).also { process.link = it }

    @Test
    fun `a band with nothing else running brings the service up and takes it down`() {
        assertNull(link.acquire())
        process.run()

        assertSame(process.service, link.current())
        assertEquals(1, process.registrations.size)

        link.release()
        process.run()
        assertNull(process.service)
        assertTrue(process.registrations.isEmpty())
    }

    @Test
    fun `a lease starting and ending under a live band keeps the band on the one registration`() {
        link.acquire()
        process.run()
        val bandService = link.current()

        process.bind()
        process.run()
        assertSame(bandService, link.current())
        assertEquals(1, process.instances)

        process.unbind()
        process.run()
        assertSame(bandService, process.service)
        assertSame(bandService, link.current())
        assertEquals(0, process.bandDetached)
    }

    @Test
    fun `a band arriving during the lease talks through the leased service at once`() {
        process.bind()
        process.run()

        val service = link.acquire()

        assertSame(process.service, service)
        process.run()
        assertEquals(1, process.instances)

        link.release()
        process.run()
        assertSame(service, process.service)
    }

    @Test
    fun `a band and a lease ending together take the service down once`() {
        process.bind()
        link.acquire()
        process.run()

        link.release()
        process.unbind()
        process.run()

        assertNull(process.service)
        assertEquals(1, process.instances)
        assertEquals(0, process.bandDetached)
    }

    @Test
    fun `a band coming back before the service went away keeps that instance`() {
        link.acquire()
        process.run()
        val first = link.current()

        link.release()
        link.acquire()
        process.run()

        assertSame(first, link.current())
        assertEquals(1, process.instances)
    }

    @Test
    fun `a band never talks through an instance that is gone`() {
        process.bind()
        process.run()
        val old = link.acquire()
        link.release()
        process.unbind()
        process.run()

        assertNull(link.acquire())
        process.run()

        val fresh = link.current()
        assertEquals(2, fresh?.instance)
        assertFalse(old === fresh)
        assertFalse(link.onServiceDestroyed(old!!))
    }

    @Test
    fun `a service lost under the band detaches it`() {
        link.acquire()
        process.run()

        // The process dying under a held binding is the only way this happens.
        val lost = process.service!!
        process.service = null

        assertTrue(link.onServiceDestroyed(lost))
        assertNull(link.current())
    }

    @Test
    fun `a failed bind holds nothing and releases nothing`() {
        process.bindSucceeds = false

        assertNull(link.acquire())
        assertFalse(link.held)
        link.release()

        assertEquals(0, process.bindings)
        assertTrue(process.looper.isEmpty())
    }
}
