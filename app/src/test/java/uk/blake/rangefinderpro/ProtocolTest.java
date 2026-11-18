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
    @Test public void breakdownShowsSidesAndResult(){
        assertEquals("L1 1.499 m · L2 1.853 m → L3 1.089 m",
            new Protocol.Measurement(5,1.089,1.499,1.853,0,null,null).breakdown());
        assertEquals("L1 1.938 m · L2 1.715 m · L3 1.954 m → L4 1.838 m",
            new Protocol.Measurement(6,1.838,1.938,1.715,1.954,null,null).breakdown());
        assertEquals("L 2.000 m · W 1.000 m",new Protocol.Measurement(3,2.0,2.0,1.0,0,null,null).breakdown());
        assertEquals("L 3.000 m · W 2.000 m · H 1.000 m",new Protocol.Measurement(4,6.0,3.0,2.0,1.0,null,null).breakdown());
        assertNull(new Protocol.Measurement(0,1.5,0,0,0,null,null).breakdown());
        assertNull(new Protocol.Measurement(4,6.0,3.0,2.0,0,null,null).breakdown());
    }
}
