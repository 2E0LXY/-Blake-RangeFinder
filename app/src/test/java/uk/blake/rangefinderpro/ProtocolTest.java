package uk.blake.rangefinderpro;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProtocolTest {
    private static final byte[] PACKET = new byte[]{0,11,2,1,1,0,2,5,36,0,0,4,(byte)210};
    @Test public void parsesMeters() {
        Protocol.Measurement m=Protocol.decode(PACKET);
        assertNotNull(m); assertEquals("Distance",m.kind); assertEquals(1.234,m.value,1e-9);
    }
    @Test public void rejectsInvalidLength() { byte[] b=PACKET.clone();b[1]=10;assertNull(Protocol.decode(b)); }
    @Test public void concatenatedAndFragmentedNotifications() {
        Protocol.Stream stream=new Protocol.Stream(); final int[] count={0};
        Protocol.Stream.Listener l=new Protocol.Stream.Listener(){
            @Override public void onMeasurement(Protocol.Measurement m,String h){count[0]++;assertEquals(1.234,m.value,1e-9);}
            @Override public void onInvalid(String msg){fail(msg);}
        };
        stream.accept(java.util.Arrays.copyOfRange(PACKET,0,6),l);
        assertEquals(0,count[0]);stream.accept(java.util.Arrays.copyOfRange(PACKET,6,PACKET.length),l);
        assertEquals(1,count[0]);stream.accept(PACKET,l);assertEquals(2,count[0]);
    }
    @Test public void rejectsBadCalculations() {
        assertTrue(Double.isNaN(Protocol.area(Double.NaN,2)));
        assertTrue(Double.isNaN(Protocol.volume(2,0,3)));
        assertEquals(15.12,Protocol.area(4.2,3.6),1e-9);
        assertEquals(2.5,Protocol.toleranceMm(10),1e-9);
    }
}
