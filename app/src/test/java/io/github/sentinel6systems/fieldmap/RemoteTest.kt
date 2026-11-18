package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.remote.Endpoint
import io.github.sentinel6systems.fieldmap.remote.endpointsFrom
import io.github.sentinel6systems.fieldmap.remote.endpointsTo
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTest {
    @Test
    fun `the box's addresses are read in its order and what is not an address is left out`() {
        // As tools/remote-server.py answers: an address set by hand, the router's, the box's IPv6.
        val json = JSONArray("""[{"host":"tak.example.com","port":8443},{"host":"203.0.113.30","port":41000},
            {"host":"[2001:db8:77::10]","port":8443},{"host":"","port":1},{"host":"192.0.2.10","port":0},{"port":5}]""")
        assertEquals(
            listOf(Endpoint("tak.example.com", 8443), Endpoint("203.0.113.30", 41000), Endpoint("[2001:db8:77::10]", 8443)),
            endpointsFrom(json),
        )
        assertEquals(emptyList<Endpoint>(), endpointsFrom(null))
    }

    @Test
    fun `the addresses kept on the phone read back the same`() {
        val list = listOf(Endpoint("203.0.113.30", 41000), Endpoint("[2001:db8:77::10]", 8443))
        assertEquals(list, endpointsFrom(JSONArray(endpointsTo(list).toString())))
    }
}
