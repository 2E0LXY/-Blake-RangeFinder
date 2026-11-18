package uk.blake.rangefinderpro;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** IR40 framed-data parser, based on inspection of the supplied original APK.
 * Protocol requires validation with a physical rangefinder. */
public final class Protocol {
    private Protocol() {}
    public static final class Measurement {
        public final int mode;
        public final String kind;
        public final double value;
        public final double a, b, c;
        public final Integer unitCode, referenceCode;
        public Measurement(int mode, double value, double a, double b, double c, Integer unitCode, Integer referenceCode) {
            this.mode=mode; this.value=value; this.a=a; this.b=b; this.c=c;
            this.unitCode=unitCode; this.referenceCode=referenceCode;
            switch(mode) {
                case 0: case 1: kind="Distance"; break;
                case 2: kind="Multi-point"; break;
                case 3: kind="Area"; break;
                case 4: kind="Volume"; break;
                case 5: case 6: case 7: kind="Pythagoras"; break;
                default: kind="Mode " + mode;
            }
        }
        public String suffix() { return "Area".equals(kind)?"m²": "Volume".equals(kind)?"m³":"m"; }
        /** Side-by-side values for device area, volume and Pythagoras modes; null when not applicable.
         *  Side order (a, b, c = L1, L2, L3) follows the manual and is unverified on hardware. */
        public String breakdown() {
            switch(mode) {
                case 3: return a>0&&b>0?String.format(Locale.UK,"L %.3f m · W %.3f m",a,b):null;
                case 4: return a>0&&b>0&&c>0?String.format(Locale.UK,"L %.3f m · W %.3f m · H %.3f m",a,b,c):null;
                case 5: return a>0&&b>0?String.format(Locale.UK,"L1 %.3f m · L2 %.3f m → L3 %.3f m",a,b,value):null;
                case 6: case 7: return a>0&&b>0&&c>0?String.format(Locale.UK,"L1 %.3f m · L2 %.3f m · L3 %.3f m → L4 %.3f m",a,b,c,value):null;
                default: return null;
            }
        }
        public String display() { return String.format(Locale.UK,"%.3f %s",value,suffix()); }
    }
    public static Measurement decode(byte[] packet) {
        if(packet==null || packet.length<5) return null;
        int payloadLength=((packet[0]&255)<<8)|(packet[1]&255);
        if(payloadLength != packet.length-2) return null;
        Map<Integer,Double> f=new HashMap<>();
        int off=2, fields=0;
        while(off+3<=packet.length && fields<32) {
            int scope=packet[off++]&255, property=packet[off++]&255, type=packet[off++]&255;
            int size;
            switch(type){case 1:case 17:size=1;break;case 18:size=2;break;case 20:case 36:case 100:size=4;break;default:return null;}
            if(off+size>packet.length) return null;
            long v=0;
            for(int i=0;i<size;i++) v=(v<<8)|(packet[off++]&255);
            if(scope==2){
                double value=v;
                if(property==2||property==3||property==4||property==5||property==9) value/=1000.0;
                f.put(property,value);
            }
            fields++;
        }
        if(off!=packet.length || !f.containsKey(1) || !f.containsKey(5)) return null;
        double val=f.get(5); int mode=f.get(1).intValue();
        if(!Double.isFinite(val)||val<=0) return null;
        if((mode==0||mode==1) && (val<0.05||val>40.0)) return null;
        return new Measurement(mode,val,f.getOrDefault(2,0.0),f.getOrDefault(3,0.0),f.getOrDefault(4,0.0),
                f.containsKey(6)?f.get(6).intValue():null,
                f.containsKey(10)?f.get(10).intValue():null);
    }
    public static final class Stream {
        private byte[] saved=new byte[0];
        public interface Listener { void onMeasurement(Measurement value, String rawHex); void onInvalid(String reason); }
        public void reset(){ saved=new byte[0]; }
        public void accept(byte[] fragment, Listener listener){
            if(fragment==null||fragment.length==0)return;
            if(saved.length+fragment.length>4096){saved=new byte[0];listener.onInvalid("Buffer overrun");}
            byte[] combined=new byte[saved.length+fragment.length];
            System.arraycopy(saved,0,combined,0,saved.length);
            System.arraycopy(fragment,0,combined,saved.length,fragment.length);
            int p=0, frames=0;
            while(combined.length-p>=2 && frames++<20) {
                int n=((combined[p]&255)<<8)|(combined[p+1]&255);
                if(n==0||n>1000){p++;listener.onInvalid("Unrecognised frame length");continue;}
                if(combined.length-p<n+2)break;
                byte[] raw=new byte[n+2];System.arraycopy(combined,p,raw,0,n+2);
                Measurement m=decode(raw);
                if(m!=null)listener.onMeasurement(m,hex(raw)); else listener.onInvalid("Unrecognised packet " + hex(raw));
                p+=n+2;
            }
            saved=new byte[combined.length-p];System.arraycopy(combined,p,saved,0,saved.length);
        }
    }
    public static String hex(byte[] data){
        StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.ROOT,"%02X",b&255));return s.toString();
    }
    public static double toleranceMm(double distanceMeters){return 2.0 + 0.05*distanceMeters;}
    public static double area(double length,double width){return length>0&&width>0&&Double.isFinite(length)&&Double.isFinite(width)?length*width:Double.NaN;}
    public static double volume(double a,double b,double c){return a>0&&b>0&&c>0&&Double.isFinite(a)&&Double.isFinite(b)&&Double.isFinite(c)?a*b*c:Double.NaN;}
}
