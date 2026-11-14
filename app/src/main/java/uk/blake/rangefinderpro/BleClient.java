package uk.blake.rangefinderpro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** IR40 BLE transport; discovers GATT characteristics and records diagnostic frames.
 * The device's notification UUID and control commands must be verified on hardware. */
@SuppressLint("MissingPermission")
public final class BleClient {
    public interface Callback {
        void state(String state);
        void device(BluetoothDevice device, int rssi);
        void measurement(Protocol.Measurement reading, String raw);
        void log(String message);
    }
    private final Context context;
    private final Callback callback;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice connectedDevice;
    private final Protocol.Stream stream=new Protocol.Stream();
    private boolean manual=true, reconnect=true, scanning=false;
    private int attempts=0;
    private final LinkedHashMap<String,BluetoothDevice> discovered=new LinkedHashMap<>();
    private final Runnable scanStop=this::stopScan;

    public BleClient(Context ctx,Callback cb){
        context=ctx.getApplicationContext();callback=cb;
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        if(manager!=null)adapter=manager.getAdapter();
    }
    public Map<String,BluetoothDevice> devices(){return new LinkedHashMap<>(discovered);}
    public boolean enabled(){return adapter!=null&&adapter.isEnabled();}
    public boolean permissionGranted(){
        if(Build.VERSION.SDK_INT>=31)
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
                &&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    public void setReconnect(boolean enabled){reconnect=enabled;}
    public void scan(){
        if(!permissionGranted()){callback.state("Bluetooth permission required");return;}
        if(!enabled()){callback.state("Enable Bluetooth first");return;}
        stopScan();discovered.clear();
        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){callback.state("Scanner unavailable");return;}
        try{
            scanner.startScan(scanCallback);
            scanning=true;
            callback.state("Scanning for IR40...");
            handler.postDelayed(scanStop,20000);
        }catch(Exception e){callback.log("Scan failed: "+e.getMessage());callback.state("Scan failed");}
    }
    public void stopScan(){
        handler.removeCallbacks(scanStop);
        if(scanning&&scanner!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
        scanning=false;
    }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int callbackType,ScanResult result){
            BluetoothDevice device=result.getDevice();if(device==null)return;
            String name="";
            try{name=device.getName();}catch(Exception ignored){}
            if(name==null||name.isEmpty())
                if(result.getScanRecord()!=null)name=result.getScanRecord().getDeviceName();
            if(name==null)name="";
            String lower=name.toLowerCase(Locale.ROOT);
            if(!lower.contains("fnirsi")&&!lower.contains("etool")&&!lower.contains("ir40"))return;
            if(discovered.put(device.getAddress(),device)==null)callback.device(device,result.getRssi());
        }
        @Override public void onScanFailed(int code){
            scanning=false;callback.log("BLE scan error: "+code);callback.state("Scan error "+code);
        }
    };
    public void connect(BluetoothDevice d){
        if(!permissionGranted()){callback.state("Bluetooth permission required");return;}
        if(d==null)return;
        manual=false;attempts=0;connectedDevice=d;stopScan();closeGatt();connectNow();
    }
    private void connectNow(){
        if(manual||connectedDevice==null||!permissionGranted())return;
        callback.state("Connecting...");
        stream.reset();
        try{gatt=connectedDevice.connectGatt(context,false,gattCallback,BluetoothDevice.TRANSPORT_LE);}
        catch(Exception e){callback.log("Connection exception "+e);scheduleReconnect();}
    }
    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){
            if(g!=gatt)return;
            if(status==BluetoothGatt.GATT_SUCCESS&&newState==BluetoothProfile.STATE_CONNECTED){
                attempts=0;callback.state("Connected, discovering services...");
                if(!g.discoverServices()){callback.log("Service discovery failed");closeGatt();scheduleReconnect();}
            }else if(newState==BluetoothProfile.STATE_DISCONNECTED||status!=BluetoothGatt.GATT_SUCCESS){
                callback.log("Disconnected, GATT status "+status);
                callback.state("Disconnected");
                closeGatt();
                scheduleReconnect();
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){
            if(g!=gatt)return;
            if(status!=BluetoothGatt.GATT_SUCCESS){callback.state("Discovery error "+status);return;}
            List<BluetoothGattCharacteristic> notify=new ArrayList<>();
            for(BluetoothGattService service:g.getServices()){
                for(BluetoothGattCharacteristic c:service.getCharacteristics()){
                    int props=c.getProperties();
                    callback.log("GATT "+service.getUuid()+" / "+c.getUuid()+" flags="+props);
                    if((props&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0)
                        notify.add(c);
                }
            }
            if(notify.isEmpty()){callback.state("No notification characteristic");return;}
            BluetoothGattCharacteristic c=notify.get(0);
            callback.log("Subscribing to first notifying characteristic "+c.getUuid()+" (hardware validation needed)");
            if(!g.setCharacteristicNotification(c,true)){callback.state("Notification setup failed");return;}
            BluetoothGattDescriptor cccd=c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if(cccd==null){callback.state("CCCD unavailable; no live readings");return;}
            byte[] value=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0
                ?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            try{
                if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(cccd,value);
                else{cccd.setValue(value);g.writeDescriptor(cccd);}
            }catch(Exception e){callback.log("Notification subscription exception "+e);callback.state("Notification error");}
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){
            callback.state(status==BluetoothGatt.GATT_SUCCESS?"Connected - listening for measurements":"Notification failed "+status);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){
            if(Build.VERSION.SDK_INT<33)accept(c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] bytes){accept(bytes);}
        private void accept(byte[] b){
            if(b==null)return;
            stream.accept(b,new Protocol.Stream.Listener(){
                @Override public void onMeasurement(Protocol.Measurement m,String raw){
                    handler.post(()->callback.measurement(m,raw));
                }
                @Override public void onInvalid(String reason){handler.post(()->callback.log(reason));}
            });
        }
    };
    private void closeGatt(){
        BluetoothGatt old=gatt;gatt=null;
        if(old!=null)try{old.disconnect();old.close();}catch(Exception ignored){}
    }
    private void scheduleReconnect(){
        if(manual||!reconnect||attempts>=5)return;
        int n=++attempts, delay=Math.min(16000,1000<<(n-1));
        callback.state("Reconnecting ("+n+"/5)...");
        handler.postDelayed(()->{if(!manual)connectNow();},delay);
    }
    public void disconnect(){
        manual=true;handler.removeCallbacksAndMessages(null);stopScan();closeGatt();
        connectedDevice=null;callback.state("Disconnected");
    }
    public void close(){disconnect();}
}
