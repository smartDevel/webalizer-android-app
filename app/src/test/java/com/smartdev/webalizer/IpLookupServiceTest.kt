package com.smartdev.webalizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpLookupServiceTest {

    @Test
    fun validatesIpv4AndIpv6WithoutAcceptingHostnames() {
        assertTrue(IpLookupService.isNumericAddress("8.8.8.8"))
        assertTrue(IpLookupService.isNumericAddress("2001:4860:4860::8888"))
        assertFalse(IpLookupService.isNumericAddress("example.org"))
        assertFalse(IpLookupService.isNumericAddress("999.8.8.8"))
    }

    @Test
    fun keepsPrivateAndReservedAddressesLocal() {
        assertFalse(IpLookupService.isPublicAddress("127.0.0.1"))
        assertFalse(IpLookupService.isPublicAddress("192.168.1.12"))
        assertFalse(IpLookupService.isPublicAddress("100.64.0.1"))
        assertFalse(IpLookupService.isPublicAddress("203.0.113.10"))
        assertFalse(IpLookupService.isPublicAddress("fc00::1"))
        assertFalse(IpLookupService.isPublicAddress("2001:db8::1"))
    }

    @Test
    fun acceptsPublicAddresses() {
        assertTrue(IpLookupService.isPublicAddress("8.8.8.8"))
        assertTrue(IpLookupService.isPublicAddress("2001:4860:4860::8888"))
    }
}
