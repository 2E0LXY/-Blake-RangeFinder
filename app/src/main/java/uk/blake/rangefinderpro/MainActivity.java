package uk.blake.rangefinderpro;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.bluetooth.BluetoothDevice;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.print.*;
import android.animation.*;
import android.view.animation.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;

/** Native independent rangefinder client. Hardware protocol still requires IR40 testing. */
public final class MainActivity extends Activity implements BleClient.Callback {
    private static final int NAVY=Color.rgb(13,19,29), CARD=Color.rgb(29,39,55), BLUE=Color.rgb(26,126,241),
        WHITE=Color.WHITE, MUTED=Color.rgb(170,185,205), GREEN=Color.rgb(60,210,153), BORDER=Color.rgb(60,78,99),
        AMBER=Color.rgb(245,176,65), RED=Color.rgb(240,100,100);
    private static final int SAVE_CSV=11,SAVE_PDF=12,SAVE_BACKUP=13,RESTORE_BACKUP=14,PHOTO_PICK=15,SAVE_PHOTO=16,PHOTO_CAPTURE=17,BUSINESS_LOGO=18;
    private static final String READY_PREFIX="Connected - listening";
    private LinearLayout root,content;
    private TextView statusBar;
    private ProjectStore store;
    private BleClient ble;
    private String page="Measure",connection="Disconnected",lastRaw="—";
    private Protocol.Measurement last;
    private int sessionCount=0,sessionPackets=0;
    private double sessionSum=0,sessionMin=Double.NaN,sessionMax=Double.NaN;
    private final StringBuilder log=new StringBuilder();
    private byte[] pendingFile;
    private Uri photoUri;
    private String photoLabel="";private Bitmap photoBmp;private final String[] photoVals={"","",""};private int photoSel=0;
    private EditText lengthInput,widthInput;
    private boolean reconnect=true;
    private String photoProject="";
    private boolean animateNext=false;private ObjectAnimator pulse;private Protocol.Measurement popShown;
    private final float[][] photoPts={{.14f,.90f,.93f,.90f},{.07f,.08f,.07f,.84f},{.72f,.68f,.90f,.46f}};
    private final View.OnTouchListener pressFx=(v,e)->{
        switch(e.getAction()){
            case MotionEvent.ACTION_DOWN:v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();break;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new OvershootInterpolator(2f)).start();break;
        }
        return false;
    };
    private final Handler ui=new Handler(Looper.getMainLooper());
    private boolean userDisconnected=false,askedPermission=false,autoConnect=false;
    private boolean showDetails=false,showConnection=false;
    // Calculator state: 0 = area, 1 = volume, 2 = triangle (Pythagoras)
    private int offsetMm=0;private String accKnown="";private final ArrayList<Double> accReadings=new ArrayList<>();
    private int calcMode=0,calcSel=0,triIdx=-1;
    private final String[] calcVal={"","",""};
    private double triVal=Double.NaN;
    private final ArrayList<Integer> triOrder=new ArrayList<>();
    private String triError=null;
    private final Runnable autoTick=new Runnable(){
        @Override public void run(){autoStart(false);ui.postDelayed(this,8000);}
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        configureSystemBars();
        store=new ProjectStore(this);ble=new BleClient(this,this);
        reconnect=getPreferences(MODE_PRIVATE).getBoolean("reconnect",true);ble.setReconnect(reconnect);
        offsetMm=getPreferences(MODE_PRIVATE).getInt("offset_mm",0);
        String uri=getPreferences(MODE_PRIVATE).getString("photo_uri","");
        if(!uri.isEmpty())photoUri=Uri.parse(uri);
        photoLabel=getPreferences(MODE_PRIVATE).getString("photo_caption","");
        loadPhotoPts();
        render();
    }
    @Override protected void onResume(){super.onResume();ui.removeCallbacks(autoTick);ui.postDelayed(autoTick,400);}
    @Override protected void onPause(){ui.removeCallbacks(autoTick);super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacks(autoTick);ble.close();super.onDestroy();}

    /**
     * API 35 enforces edge-to-edge. Opt in consistently on older Android as well,
     * then explicitly reserve the status bar, display cutout and navigation/IME insets.
     * This keeps our bottom tabs ABOVE both three-button and gesture navigation.
     */
    private void configureSystemBars(){
        Window window=getWindow();
        window.setStatusBarColor(NAVY);
        window.setNavigationBarColor(NAVY);
        if(Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false);
        }else{
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if(Build.VERSION.SDK_INT>=29)window.setNavigationBarContrastEnforced(false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void attachSafeAreaInsets(View container){
        container.setOnApplyWindowInsetsListener((view,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
                left=bars.left;top=bars.top;right=bars.right;
                bottom=SafeArea.bottomInset(bars.bottom,keyboard.bottom);
            }else{
                left=insets.getSystemWindowInsetLeft();
                top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();
                bottom=insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left,top,right,bottom);
            return insets; // Keep dispatching insets to children.
        });
        container.requestApplyInsets();
    }

    private int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private void gap(LinearLayout into,int h){View v=new View(this);into.addView(v,lp(1,h));}
    private LinearLayout card(LinearLayout parent){
        LinearLayout l=vertical();l.setBackground(bg(CARD,14));pad(l,16,16);
        LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(12);parent.addView(l,p);return l;
    }
    private void label(LinearLayout parent,String value){
        TextView t=text(value.toUpperCase(Locale.UK),12,MUTED,true);parent.addView(t);gap(parent,10);
    }
    private void line(LinearLayout parent,String value){parent.addView(text(value,15,WHITE,false));gap(parent,8);}
    private Button button(LinearLayout parent,String name,Runnable run,boolean secondary){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextColor(WHITE);b.setTextSize(15);
        b.setBackground(bg(secondary?BORDER:BLUE,10));b.setOnClickListener(v->run.run());b.setOnTouchListener(pressFx);
        LinearLayout.LayoutParams p=lp(-1,52);p.bottomMargin=dp(8);parent.addView(b,p);return b;
    }
    private EditText input(LinearLayout into,String hint,String preset){
        EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(WHITE);
        e.setHintTextColor(MUTED);e.setBackground(bg(BORDER,8));e.setTextSize(16);
        e.setHint(hint);e.setText(preset);pad(e,12,8);
        LinearLayout.LayoutParams p=lp(-1,48);p.bottomMargin=dp(9);into.addView(e,p);return e;
    }
    private EditText decimal(LinearLayout into,String hint,String preset){
        EditText e=input(into,hint,preset);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }
    private double positive(EditText e){
        try{double v=Double.parseDouble(e.getText().toString().trim());return Double.isFinite(v)&&v>0?v:Double.NaN;}
        catch(Exception ex){return Double.NaN;}
    }
    private void notice(String m){Toast.makeText(this,m,Toast.LENGTH_LONG).show();}
    private void go(String p){page=p;animateNext=true;render();}
    private void staggerIn(LinearLayout box){
        for(int i=0;i<box.getChildCount();i++){
            View v=box.getChildAt(i);v.setAlpha(0f);v.setTranslationY(dp(22));
            v.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(i,10)*55L).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }
    private void section(String title){content.addView(text(title,24,WHITE,true));gap(content,15);}
    private String fmt(double v){return String.format(Locale.UK,"%.3f",v);}

    // ---------- connection status ----------
    private boolean ready(){return connection.startsWith(READY_PREFIX);}
    private boolean busy(){
        return connection.startsWith("Connecting")||connection.startsWith("Connected, discovering")
            ||connection.startsWith("Reconnecting")||connection.startsWith("Scanning");
    }
    private int statusColor(){return ready()?GREEN:busy()?AMBER:RED;}
    private int statusTint(){return ready()?Color.rgb(18,92,64):busy()?Color.rgb(110,78,10):Color.rgb(120,32,32);}
    private void updateStatus(){
        if(statusBar==null)return;
        String t=ready()?"●  Connected to IR40"
            :busy()?(connection.startsWith("Scanning")?"●  Searching for IR40…":"●  Connecting to IR40…")
            :"●  Not connected - tap here";
        statusBar.setText(t);statusBar.setTextColor(WHITE);statusBar.setBackgroundColor(statusTint());
    }
    private void requestBlePermissions(){
        if(Build.VERSION.SDK_INT>=31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},7);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},7);
    }
    /** Finds and connects to the IR40 without any button presses. Called on a timer while not connected. */
    private void autoStart(boolean force){
        if(force)userDisconnected=false;
        if(userDisconnected||ready())return;
        if(!force&&(busy()||ble.isScanning()))return;
        if(!ble.permissionGranted()){
            if(!askedPermission||force){askedPermission=true;requestBlePermissions();}
            return;
        }
        if(!ble.enabled()){
            connection="Bluetooth is switched off";updateStatus();
            if("Measure".equals(page))render();
            if(force)new AlertDialog.Builder(this).setTitle("Bluetooth is off")
                .setMessage("Switch on Bluetooth, then the app will find your IR40 by itself.")
                .setPositiveButton("OK",null).show();
            return;
        }
        String saved=getPreferences(MODE_PRIVATE).getString("last_device","");
        Map<String,BluetoothDevice> found=ble.devices();
        if(!found.isEmpty()&&!found.containsKey(saved)){
            autoConnect=false;ble.connect(found.values().iterator().next());return;
        }
        autoConnect=true;
        ble.scan();
    }

    // ---------- layout ----------
    private void render(){
        if(pulse!=null){pulse.cancel();pulse=null;}
        root=vertical();root.setBackgroundColor(NAVY);setContentView(root);
        attachSafeAreaInsets(root);
        LinearLayout header=horizontal();pad(header,17,9);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.blake_logo_ui);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);header.addView(logo,lp(125,46));
        LinearLayout names=vertical();names.addView(text("RangeFinder Pro",16,WHITE,true));
        names.addView(text("IR40 · 2.4.0 beta",11,MUTED,false));
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,dp(48),1);
        np.leftMargin=dp(12);header.addView(names,np);root.addView(header,lp(-1,70));
        statusBar=text("",14,WHITE,true);statusBar.setGravity(Gravity.CENTER);
        statusBar.setOnClickListener(v->go("Measure"));
        root.addView(statusBar,lp(-1,40));updateStatus();
        if(busy()){pulse=ObjectAnimator.ofFloat(statusBar,"alpha",1f,.55f);pulse.setDuration(750);
            pulse.setRepeatMode(ValueAnimator.REVERSE);pulse.setRepeatCount(ValueAnimator.INFINITE);pulse.start();}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        content=vertical();pad(content,16,16);scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        switch(page){
            case "Calculate":showCalculate();break;
            case "Projects":showProjects();break;
            case "Plan":showPlan();break;
            case "Settings":showSettings();break;
            case "Photo":showPhoto();break;
            case "Accuracy":showAccuracy();break;
            default:showMeasure();
        }
        if(animateNext){animateNext=false;staggerIn(content);}
        // Five equal-width navigation tabs fit without horizontal scrolling.
        // Photo and Settings remain accessible under More.
        LinearLayout nav=horizontal();
        nav.setBackgroundColor(CARD);
        nav.setPadding(dp(5),dp(3),dp(5),dp(3));
        for(String p:new String[]{"Measure","Calculate","Projects","Plan","More"}){
            boolean active=p.equals(page) || ("More".equals(p) &&
                ("Photo".equals(page) || "Settings".equals(page) || "Accuracy".equals(page)));
            TextView tab=text(p,11,active?WHITE:MUTED,active);
            tab.setGravity(Gravity.CENTER);
            tab.setBackground(bg(active?BLUE:CARD,10));
            tab.setContentDescription(p+" tab");tab.setOnTouchListener(pressFx);
            LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(0,dp(52),1);
            item.leftMargin=dp(2);item.rightMargin=dp(2);
            nav.addView(tab,item);
            tab.setOnClickListener(v->{
                if("More".equals(p)){
                    new AlertDialog.Builder(this).setTitle("More")
                        .setItems(new String[]{"Photo annotation","Settings & export","Check accuracy"},
                            (dialog,index)->go(index==0?"Photo":index==1?"Settings":"Accuracy"))
                        .show();
                }else{
                    if(!p.equals(page)){
                        if("Calculate".equals(p)&&calcSel<0)calcSel=firstEmpty();
                        go(p);
                    }
                }
            });
        }
        root.addView(nav,lp(-1,58));
    }

    // ---------- Measure ----------
    private void showMeasure(){
        section("Measure");
        boolean ok=ready(),wait=busy();
        int sc=statusColor();
        LinearLayout st=card(content);
        GradientDrawable box=bg(CARD,14);box.setStroke(dp(2),sc);st.setBackground(box);
        String head=ok?"Connected to IR40":wait?(connection.startsWith("Scanning")?"Searching for your IR40…":"Connecting to IR40…"):"IR40 not connected";
        st.addView(text(head,22,sc,true));gap(st,8);
        String sub=ok?"Press the measure button on your IR40. The reading appears below."
            :wait?"Please wait. Make sure your IR40 is switched on and close to your phone."
            :"Switch on your IR40 and keep it close to your phone."
                +("Disconnected".equals(connection)?"":"\n("+connection+")");
        st.addView(text(sub,15,WHITE,false));gap(st,10);
        if(!ok&&!wait)button(st,"Connect now",()->autoStart(true),false);
        button(st,showConnection?"Hide connection options":"Connection options",()->{showConnection=!showConnection;render();},true);
        if(showConnection){
            button(st,"Search again",()->autoStart(true),true);
            button(st,"Choose a different IR40 ("+ble.devices().size()+" found)",this::chooseDevice,true);
            button(st,"Disconnect",()->{userDisconnected=true;ble.disconnect();last=null;render();},true);
        }
        LinearLayout display=card(content);label(display,"Latest reading");
        TextView big=text(last==null?"—":last.display(),46,WHITE,true);display.addView(big);gap(display,8);
        if(last!=null&&last!=popShown){
            popShown=last;big.setScaleX(1.14f);big.setScaleY(1.14f);big.setPivotX(0f);
            big.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        if(offsetMm!=0)line(display,"Offset "+offsetText()+" applied");
        if(last==null)line(display,"No reading yet. Press the measure button on your IR40.");
        else{
            if(!"Distance".equals(last.kind))line(display,"Type: "+last.kind);
            line(display,store.autoSave()?"Saved automatically to "+projectName(store.active())+".":"Not saved yet.");
            if(!store.autoSave())button(display,"Save this reading",()->{
                try{store.addRecord(last,"IR40");render();}catch(Exception e){notice("Save failed: "+e.getMessage());}
            },false);
        }
        button(content,showDetails?"Hide details":"Show details",()->{showDetails=!showDetails;render();},true);
        if(showDetails){
            if(last!=null){
                LinearLayout tech=card(content);label(tech,"Reading details");
                if("Distance".equals(last.kind))
                    line(tech,"Nominal tolerance: ±"+String.format(Locale.UK,"%.2f mm",Protocol.toleranceMm(last.value)));
                line(tech,"Device unit code: "+(last.unitCode==null?"unknown":last.unitCode));
                line(tech,"Reference code: "+(last.referenceCode==null?"unknown":last.referenceCode));
            }
            LinearLayout stats=card(content);label(stats,"Current session");
            line(stats,"Readings: "+sessionPackets+"   Distances: "+sessionCount);
            line(stats,"Min: "+(Double.isNaN(sessionMin)?"—":fmt(sessionMin)+" m")
                +"   Max: "+(Double.isNaN(sessionMax)?"—":fmt(sessionMax)+" m"));
            line(stats,"Average: "+(sessionCount==0?"—":fmt(sessionSum/sessionCount)+" m"));
            button(stats,"Reset session stats",()->{
                sessionCount=0;sessionPackets=0;sessionSum=0;sessionMin=Double.NaN;sessionMax=Double.NaN;render();
            },true);
            LinearLayout manual=card(content);label(manual,"Type a measurement by hand");
            EditText value=decimal(manual,"Distance in metres","");
            button(manual,"Save typed measurement",()->{
                double v=positive(value);
                if(!Double.isFinite(v)){notice("Enter a valid distance");return;}
                store.addManual(v);notice("Measurement saved");render();
            },false);
        }
        showHistory(5);
    }
    private String unitSuffix(JSONObject r){
        String kind=r.optString("kind");
        return "Area".equals(kind)?"m²":"Volume".equals(kind)?"m³":"m";
    }
    private void showHistory(int limit){
        LinearLayout h=card(content);label(h,"Recent project readings");
        int shown=0;JSONArray records=store.records();
        for(int i=0;i<records.length()&&shown<limit;i++){
            JSONObject o=records.optJSONObject(i);
            if(o==null||!store.active().equals(o.optString("projectId")))continue;
            line(h,o.optString("kind","Distance")+"    "+fmt(o.optDouble("metres"))+" "+unitSuffix(o));shown++;
        }
        if(shown==0)line(h,"No saved measurements yet");
    }
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] g){
        super.onRequestPermissionsResult(req,p,g);
        if(req==7){
            if(ble.permissionGranted())autoStart(true);
            else notice("Bluetooth permission is needed to find your IR40");
            render();
        }
    }
    private void chooseDevice(){
        Map<String,BluetoothDevice> devices=ble.devices();
        if(devices.isEmpty()){notice("No IR40 found yet. Switch it on and tap Search again.");return;}
        ArrayList<BluetoothDevice> values=new ArrayList<>(devices.values());
        String[] names=new String[values.size()];
        for(int i=0;i<names.length;i++){
            BluetoothDevice d=values.get(i);String n="IR40";
            try{if(d.getName()!=null)n=d.getName();}catch(Exception ignored){}
            names[i]=n+"    "+d.getAddress();
        }
        new AlertDialog.Builder(this).setTitle("Connect to IR40")
            .setItems(names,(dialog,which)->{userDisconnected=false;autoConnect=false;ble.connect(values.get(which));render();}).show();
    }

    // ---------- Calculate ----------
    private String[] calcLabels(){
        return calcMode==0?new String[]{"Length","Width"}
            :calcMode==1?new String[]{"Length","Width","Height"}
            :new String[]{"Side A","Side B","Longest side (C)"};
    }
    private int calcNeeded(){return calcMode==2?2:calcLabels().length;}
    private double calcNum(int i){
        try{double v=Double.parseDouble(calcVal[i]);return Double.isFinite(v)&&v>0?v:Double.NaN;}
        catch(Exception e){return Double.NaN;}
    }
    private int filledCount(){
        int n=0;for(int i=0;i<calcLabels().length;i++)if(!calcVal[i].isEmpty())n++;return n;
    }
    private int firstEmpty(){
        if(filledCount()>=calcNeeded())return -1;
        for(int i=0;i<calcLabels().length;i++)if(calcVal[i].isEmpty())return i;
        return -1;
    }
    private void calcClear(){
        calcVal[0]="";calcVal[1]="";calcVal[2]="";triOrder.clear();calcSel=0;triIdx=-1;triVal=Double.NaN;triError=null;
    }
    /** Returns {headline, detail} or null when more boxes need filling. */
    private String[] calcResult(){
        triIdx=-1;triVal=Double.NaN;triError=null;
        if(calcMode==0){
            double l=calcNum(0),w=calcNum(1);
            if(Double.isNaN(l)||Double.isNaN(w))return null;
            return new String[]{fmt(l*w)+" m²","Perimeter "+fmt(2*(l+w))+" m"};
        }
        if(calcMode==1){
            double l=calcNum(0),w=calcNum(1),h=calcNum(2);
            if(Double.isNaN(l)||Double.isNaN(w)||Double.isNaN(h))return null;
            return new String[]{fmt(l*w*h)+" m³","Floor area "+fmt(l*w)+" m²"};
        }
        double a=calcNum(0),b=calcNum(1),c=calcNum(2);
        boolean ha=!Double.isNaN(a),hb=!Double.isNaN(b),hc=!Double.isNaN(c);
        if(ha&&hb){
            triIdx=2;triVal=Math.hypot(a,b);
            return new String[]{"C = "+fmt(triVal)+" m","Right-angled triangle. C is the longest side."};
        }
        if(ha&&hc){
            if(c<=a){triError="The longest side (C) must be longer than A.";return null;}
            triIdx=1;triVal=Math.sqrt(c*c-a*a);
            return new String[]{"B = "+fmt(triVal)+" m","Right-angled triangle. C is the longest side."};
        }
        if(hb&&hc){
            if(c<=b){triError="The longest side (C) must be longer than B.";return null;}
            triIdx=0;triVal=Math.sqrt(c*c-b*b);
            return new String[]{"A = "+fmt(triVal)+" m","Right-angled triangle. C is the longest side."};
        }
        return null;
    }
    /** Stores a value; in Pythagoras mode only the two most recent sides stay "known", the third is calculated. */
    private void setCalcVal(int i,String v){
        calcVal[i]=v;
        if(calcMode!=2)return;
        triOrder.remove(Integer.valueOf(i));triOrder.add(i);
        while(triOrder.size()>2)calcVal[triOrder.remove(0)]="";
    }
    private GradientDrawable tileBg(boolean selected){
        GradientDrawable d=bg(selected?Color.rgb(24,54,96):CARD,14);
        d.setStroke(dp(selected?3:1),selected?BLUE:BORDER);return d;
    }
    private void typeValue(int i,String[] labels){
        EditText e=new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(calcVal[i]);e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(labels[i]+" in metres").setView(e)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("OK",(dialog,which)->{
                double v=positive(e);
                if(!Double.isFinite(v)){notice("Enter a number above zero");return;}
                setCalcVal(i,fmt(v));calcSel=firstEmpty();render();
            }).show();
    }
    private void calcTile(int i,String[] labels){
        boolean sel=i==calcSel,filled=!calcVal[i].isEmpty(),computed=!filled&&triIdx==i;
        LinearLayout tile=horizontal();tile.setBackground(tileBg(sel));pad(tile,16,14);
        LinearLayout left=vertical();
        left.addView(text(labels[i].toUpperCase(Locale.UK),12,MUTED,true));gap(left,4);
        String value=filled?calcVal[i]+" m":computed?fmt(triVal)+" m":"—";
        left.addView(text(value,32,computed?GREEN:WHITE,true));gap(left,4);
        String hint=sel?"● Waiting… press measure on your IR40"
            :filled?"Tap to measure again":computed?"Worked out for you":"Tap to select this box";
        left.addView(text(hint,13,sel?BLUE:MUTED,sel));
        tile.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        TextView type=text("Type",14,WHITE,true);type.setGravity(Gravity.CENTER);
        type.setBackground(bg(BORDER,10));
        type.setOnClickListener(v->typeValue(i,labels));
        tile.addView(type,lp(72,44));
        tile.setOnClickListener(v->{calcSel=i;render();});
        LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(10);content.addView(tile,p);
    }
    private void showCalculate(){
        section("Calculate");
        String[] labels=calcLabels();
        String[] result=calcResult();
        LinearLayout modes=horizontal();
        String[] names={"Area","Volume","Pythagoras"};
        for(int i=0;i<3;i++){
            final int idx=i;boolean on=calcMode==i;
            TextView t=text(names[i],15,on?WHITE:MUTED,on);t.setGravity(Gravity.CENTER);
            t.setBackground(bg(on?BLUE:CARD,10));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);
            p.rightMargin=dp(i<2?8:0);modes.addView(t,p);
            t.setOnClickListener(v->{if(calcMode!=idx){calcMode=idx;calcClear();}render();});
        }
        content.addView(modes,lp(-1,48));gap(content,12);
        LinearLayout how=card(content);
        how.addView(text(calcMode==2
            ?"1. Tap a side of the triangle (or its box) for a length you know\n2. Press the measure button on your IR40\nMeasure any two sides. The third is calculated and filled in for you."
            :"1. Tap a box\n2. Press the measure button on your IR40\nThe box fills in by itself.",15,WHITE,false));
        if(calcMode==2){
            int[] st=new int[3];double[] val=new double[3];
            for(int i=0;i<3;i++){
                if(!calcVal[i].isEmpty()){st[i]=1;val[i]=calcNum(i);}
                else if(triIdx==i){st[i]=2;val[i]=triVal;}
                else val[i]=Double.NaN;
            }
            TriangleView tv=new TriangleView(this,st,val,calcSel,i->{calcSel=i;render();});
            LinearLayout.LayoutParams tp=lp(-1,270);tp.bottomMargin=dp(10);content.addView(tv,tp);
        }
        for(int i=0;i<labels.length;i++)calcTile(i,labels);
        LinearLayout rc=card(content);label(rc,"Result");
        if(result==null){
            rc.addView(text(triError!=null?triError:"Fill in the boxes above to see the answer.",16,triError!=null?RED:MUTED,false));
            gap(rc,10);
        }else{
            rc.addView(text(result[0],36,GREEN,true));gap(rc,8);line(rc,result[1]);
            button(rc,"Save result to project",()->saveCalc(),false);
        }
        button(rc,"Start again",()->{calcClear();render();},true);
    }
    private void saveCalc(){
        try{
            if(calcMode==0){
                double l=calcNum(0),w=calcNum(1);
                store.addRecord(new Protocol.Measurement(3,l*w,l,w,0,null,null),"calculator");
            }else if(calcMode==1){
                double l=calcNum(0),w=calcNum(1),h=calcNum(2);
                store.addRecord(new Protocol.Measurement(4,l*w*h,l,w,h,null,null),"calculator");
            }else{
                double a=triIdx==0?triVal:calcNum(0),b=triIdx==1?triVal:calcNum(1),c=triIdx==2?triVal:calcNum(2);
                store.addRecord(new Protocol.Measurement(5,c,a,b,0,null,null),"calculator");
            }
            notice("Saved to "+projectName(store.active()));
        }catch(Exception e){notice("Save failed: "+e.getMessage());}
    }
    /** Fills the selected box with a live reading, then moves to the next empty box. */
    private void applyMeasurementToCalc(Protocol.Measurement m){
        if(!"Distance".equals(m.kind)){notice("Set the IR40 to normal distance mode, then measure again");return;}
        String[] labels=calcLabels();
        if(calcSel<0||calcSel>=labels.length){notice("Tap a box first, then press measure");return;}
        int filledIdx=calcSel;
        setCalcVal(filledIdx,fmt(m.value));
        Toast.makeText(this,labels[filledIdx]+" = "+fmt(m.value)+" m",Toast.LENGTH_SHORT).show();
        calcSel=-1;
        if(filledCount()<calcNeeded()){
            for(int k=1;k<=labels.length;k++){
                int j=(filledIdx+k)%labels.length;
                if(calcVal[j].isEmpty()){calcSel=j;break;}
            }
        }
        render();
    }

    // ---------- Projects ----------
    private String projectName(String id){
        JSONArray list=store.projects();
        for(int i=0;i<list.length();i++){
            JSONObject v=list.optJSONObject(i);
            if(v!=null&&id.equals(v.optString("id")))return v.optString("name");
        }
        return id;
    }
    private void showProjects(){
        section("Projects");
        LinearLayout c=card(content);label(c,"Current project");line(c,projectName(store.active()));
        EditText e=input(c,"New project / customer name","");
        button(c,"Create project",()->{
            String name=e.getText().toString().trim();
            if(name.isEmpty()){notice("Enter a project name");return;}
            store.createProject(name);render();
        },false);
        JSONArray projects=store.projects();
        for(int i=0;i<projects.length();i++){
            JSONObject project=projects.optJSONObject(i);if(project==null)continue;
            String id=project.optString("id"),name=project.optString("name");
            LinearLayout item=card(content);line(item,name);
            button(item,id.equals(store.active())?"Selected":"Open project",()->{
                store.setActive(id);render();
            },id.equals(store.active()));
        }
        showHistory(100);
    }
    private void showPlan(){
        section("Measured room plan");
        LinearLayout c=card(content);
        label(c,"Rectangle from real dimensions");
        line(c,"Enter measured length and width; pixel-based area estimates are not used.");
        JSONObject plan=store.plans().optJSONObject(store.active());
        String oldL=plan==null?"":String.valueOf(plan.optDouble("length"));
        String oldW=plan==null?"":String.valueOf(plan.optDouble("width"));
        lengthInput=decimal(c,"Length (metres)",oldL);
        widthInput=decimal(c,"Width (metres)",oldW);
        if(plan!=null){
            double l=plan.optDouble("length"),w=plan.optDouble("width");
            if(l>0&&w>0){
                c.addView(text("Area "+fmt(l*w)+" m²   Perimeter "+fmt(2*(l+w))+" m",17,GREEN,true));
                gap(c,12);c.addView(new RoomView(this,l,w),lp(-1,230));gap(c,12);
            }
        }
        button(c,"Save measured plan",()->{
            double l=positive(lengthInput),w=positive(widthInput);
            if(!Double.isFinite(Protocol.area(l,w))){notice("Enter two valid dimensions");return;}
            store.setPlan(l,w);render();
        },false);
        button(c,"Use last distance as length",()->{
            if(last==null||!"Distance".equals(last.kind)){notice("Take a distance measurement first");return;}
            lengthInput.setText(fmt(last.value));
        },true);
        button(c,"Use last distance as width",()->{
            if(last==null||!"Distance".equals(last.kind)){notice("Take a distance measurement first");return;}
            widthInput.setText(fmt(last.value));
        },true);
    }
    /** Right-angled triangle. A = vertical, B = horizontal, C = hypotenuse. Tap a side to select it. */
    private static final class TriangleView extends View {
        private final int[] state;private final double[] val;private final int sel;
        private final java.util.function.IntConsumer onTap;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] seg=new float[12];
        TriangleView(Context c,int[] st,double[] v,int selected,java.util.function.IntConsumer tap){
            super(c);state=st;val=v;sel=selected;onTap=tap;
        }
        private float d(float n){return n*getResources().getDisplayMetrics().density;}
        private int colour(int i){
            if(i==sel)return Color.rgb(26,126,241);
            return state[i]==2?Color.rgb(60,210,153):state[i]==1?Color.WHITE:Color.rgb(120,138,160);
        }
        private String label(int i){
            String n=i==0?"A":i==1?"B":"C";
            return Double.isNaN(val[i])?n+" = ?":String.format(Locale.UK,"%s = %.3f m",n,val[i]);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            // Shape follows the numbers once both legs are known, otherwise a 3:4:5 placeholder.
            double a=3,b=4;
            if(!Double.isNaN(val[0])&&!Double.isNaN(val[1])){a=val[0];b=val[1];}
            else if(!Double.isNaN(val[0])&&!Double.isNaN(val[2])&&val[2]>val[0]){a=val[0];b=Math.sqrt(val[2]*val[2]-a*a);}
            else if(!Double.isNaN(val[1])&&!Double.isNaN(val[2])&&val[2]>val[1]){b=val[1];a=Math.sqrt(val[2]*val[2]-b*b);}
            double ratio=Math.max(0.33,Math.min(3.0,a/b));
            paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(d(16));
            float wA=paint.measureText(label(0)),wC=paint.measureText(label(2));
            float left=wA+d(24),right=getWidth()-Math.max(d(24),wC/2),top=d(34),bottom=getHeight()-d(46);
            float availW=right-left,availH=bottom-top;
            float w=availW,h=(float)(w*ratio);
            if(h>availH){h=availH;w=(float)(h/ratio);}
            float x0=left+(availW-w)/2,y0=bottom;           // right-angle corner
            float x1=x0,y1=y0-h;                              // top of A
            float x2=x0+w,y2=y0;                              // end of B
            seg[0]=x0;seg[1]=y0;seg[2]=x1;seg[3]=y1;          // A
            seg[4]=x0;seg[5]=y0;seg[6]=x2;seg[7]=y2;          // B
            seg[8]=x1;seg[9]=y1;seg[10]=x2;seg[11]=y2;        // C
            Path fill=new Path();fill.moveTo(x0,y0);fill.lineTo(x1,y1);fill.lineTo(x2,y2);fill.close();
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(24,36,54));c.drawPath(fill,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
            for(int i=0;i<3;i++){
                paint.setColor(colour(i));paint.setStrokeWidth(d(i==sel?7:5));
                c.drawLine(seg[i*4],seg[i*4+1],seg[i*4+2],seg[i*4+3],paint);
            }
            paint.setColor(Color.rgb(170,185,205));paint.setStrokeWidth(d(2));
            float q=d(14);Path sq=new Path();sq.moveTo(x0,y0-q);sq.lineTo(x0+q,y0-q);sq.lineTo(x0+q,y0);c.drawPath(sq,paint);
            paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(d(16));
            paint.setColor(colour(0));paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(label(0),x0-d(12),(y0+y1)/2+d(6),paint);
            paint.setColor(colour(1));paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(label(1),(x0+x2)/2,y0+d(28),paint);
            // C label: offset outwards from the right-angle corner
            float mx=(x1+x2)/2,my=(y1+y2)/2,nx=mx-x0,ny=my-y0,len=(float)Math.hypot(nx,ny);
            if(len>0){nx/=len;ny/=len;}
            paint.setColor(colour(2));paint.setTextAlign(Paint.Align.CENTER);
            float lx=Math.min(getWidth()-wC/2-d(4),Math.max(wC/2+d(4),mx+nx*d(30)));
            c.drawText(label(2),lx,my+ny*d(30)+d(6),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_DOWN)return true;
            if(e.getAction()!=MotionEvent.ACTION_UP)return true;
            int best=-1;float bestD=d(56);
            for(int i=0;i<3;i++){
                float dist=segDist(e.getX(),e.getY(),seg[i*4],seg[i*4+1],seg[i*4+2],seg[i*4+3]);
                if(dist<bestD){bestD=dist;best=i;}
            }
            if(best>=0){performClick();onTap.accept(best);}
            return true;
        }
        @Override public boolean performClick(){return super.performClick();}
        private static float segDist(float px,float py,float ax,float ay,float bx,float by){
            float dx=bx-ax,dy=by-ay,l2=dx*dx+dy*dy;
            float t=l2==0?0:Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/l2));
            return (float)Math.hypot(px-(ax+t*dx),py-(ay+t*dy));
        }
    }
    private static final class RoomView extends View {
        private final double l,w;private final Paint paint=new Paint(3);
        RoomView(Context c,double length,double width){super(c);l=length;w=width;}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float a=getWidth()-70,b=getHeight()-70;
            float scale=(float)Math.min(a/l,b/w);
            float rw=(float)(l*scale),rh=(float)(w*scale);
            float x=(getWidth()-rw)/2,y=(getHeight()-rh)/2;
            paint.setColor(Color.rgb(225,233,245));paint.setStyle(Paint.Style.FILL);
            c.drawRect(x,y,x+rw,y+rh,paint);
            paint.setColor(Color.rgb(30,45,62));paint.setStrokeWidth(8);
            paint.setStyle(Paint.Style.STROKE);c.drawRect(x,y,x+rw,y+rh,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(25,90,180));
            paint.setTextSize(28);c.drawText(String.format(Locale.UK,"%.2f m",l),x,y-10,paint);
            c.save();c.rotate(-90,x-12,y+rh);
            c.drawText(String.format(Locale.UK,"%.2f m",w),x-12,y+rh,paint);c.restore();
        }
    }

    // ---------- Offset and accuracy check ----------
    private Protocol.Measurement adjust(Protocol.Measurement m){
        if(offsetMm==0||!"Distance".equals(m.kind))return m;
        double v=m.value+offsetMm/1000.0;
        if(!(v>0))return m;
        return new Protocol.Measurement(m.mode,v,m.a,m.b,m.c,m.unitCode,m.referenceCode);
    }
    private void setOffset(int mm){
        offsetMm=mm;getPreferences(MODE_PRIVATE).edit().putInt("offset_mm",mm).apply();
    }
    private String offsetText(){return (offsetMm>0?"+":"")+offsetMm+" mm";}
    private void askOffset(){
        EditText e=new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            |android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(offsetMm==0?"":String.valueOf(offsetMm));e.setHint("e.g. 100 or -25");
        new AlertDialog.Builder(this).setTitle("Offset in millimetres")
            .setMessage("Added to every distance reading. Use a positive number to measure from further back, for example 100 for a tripod mount. Limit 500 mm either way.")
            .setView(e).setNegativeButton("Cancel",null)
            .setPositiveButton("OK",(dialog,which)->{
                try{
                    int v=(int)Math.round(Double.parseDouble(e.getText().toString().trim()));
                    if(Math.abs(v)>500){notice("Offset must be between -500 and 500 mm");return;}
                    setOffset(v);render();
                }catch(Exception ex){notice("Enter a number in millimetres");}
            }).show();
    }
    private double accKnownValue(){
        try{double v=Double.parseDouble(accKnown);return Double.isFinite(v)&&v>0?v:Double.NaN;}
        catch(Exception e){return Double.NaN;}
    }
    private void addAccuracyReading(Protocol.Measurement m){
        if(!"Distance".equals(m.kind)){notice("Set the IR40 to normal distance mode, then measure again");return;}
        if(Double.isNaN(accKnownValue())){notice("Enter the known length first");return;}
        if(accReadings.size()>=10)accReadings.remove(0);
        accReadings.add(m.value);
        render();
    }
    private void showAccuracy(){
        section("Check accuracy");
        LinearLayout how=card(content);
        how.addView(text("1. Pick a length you know is right, such as a tape-checked 2 m\n2. Enter it below\n3. Press measure on your IR40 a few times from the same spot",15,WHITE,false));
        double known=accKnownValue();
        LinearLayout kc=horizontal();kc.setBackground(tileBg(Double.isNaN(known)));pad(kc,16,14);
        LinearLayout left=vertical();
        left.addView(text("KNOWN LENGTH",12,MUTED,true));gap(left,4);
        left.addView(text(Double.isNaN(known)?"—":fmt(known)+" m",32,WHITE,true));
        kc.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        TextView type=text(Double.isNaN(known)?"Enter":"Change",14,WHITE,true);
        type.setGravity(Gravity.CENTER);type.setBackground(bg(BLUE,10));
        type.setOnClickListener(v->{
            EditText e=new EditText(this);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e.setText(accKnown);e.setSelectAllOnFocus(true);
            new AlertDialog.Builder(this).setTitle("Known length in metres").setView(e)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("OK",(dialog,which)->{
                    double val=positive(e);
                    if(!Double.isFinite(val)){notice("Enter a number above zero");return;}
                    accKnown=fmt(val);accReadings.clear();render();
                }).show();
        });
        kc.addView(type,lp(84,44));
        LinearLayout.LayoutParams kp=lp(-1,-2);kp.bottomMargin=dp(10);content.addView(kc,kp);
        LinearLayout rc=card(content);label(rc,"Readings");
        if(accReadings.isEmpty())
            line(rc,Double.isNaN(known)?"Enter the known length, then measure.":"Press measure on your IR40.");
        double tol=Double.isNaN(known)?0:Protocol.toleranceMm(known),sum=0;
        for(int i=0;i<accReadings.size();i++){
            double d=(accReadings.get(i)-known)*1000.0;sum+=d;
            boolean within=Math.abs(d)<=tol;
            rc.addView(text((i+1)+".  "+fmt(accReadings.get(i))+" m    "
                +String.format(Locale.UK,"%+.1f mm",d)+(within?"   ✓":"   ✗"),16,within?GREEN:RED,false));
            gap(rc,6);
        }
        if(!accReadings.isEmpty()&&!Double.isNaN(known)){
            double avg=sum/accReadings.size();boolean pass=Math.abs(avg)<=tol;
            gap(rc,6);
            rc.addView(text(pass?"PASS":"OUTSIDE TOLERANCE",30,pass?GREEN:RED,true));gap(rc,8);
            line(rc,String.format(Locale.UK,"Average error %+.1f mm. Allowed ±%.1f mm at %.3f m.",avg,tol,known));
            if(!pass)line(rc,"Check the reference point (rear, front or tripod), the offset in Settings, and that the laser hits a flat surface. If it still fails, contact Blake UK support.");
            if(offsetMm!=0)line(rc,"Includes your "+offsetText()+" offset.");
        }
        button(rc,"Clear readings",()->{accReadings.clear();render();},true);
    }

    // ---------- Take a photo ----------
    private void takePhoto(){
        PhotoProvider.captureFile(this).delete();
        Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT,PhotoProvider.captureUri());
        i.setClipData(ClipData.newRawUri("photo",PhotoProvider.captureUri()));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(i,PHOTO_CAPTURE);}
        catch(ActivityNotFoundException e){notice("No camera app found. Use Choose from gallery instead.");}
    }
    /** A new photo goes straight onto the annotation page, ready for its first measurement. */
    private void useNewPhoto(Uri uri){
        photoUri=uri;photoBmp=null;photoProject=store.active();
        photoVals[0]="";photoVals[1]="";photoVals[2]="";photoSel=0;resetPhotoPts();
        getPreferences(MODE_PRIVATE).edit().putString("photo_uri",uri.toString()).apply();
        Toast.makeText(this,"Photo added. Press measure on your IR40 for Width.",Toast.LENGTH_LONG).show();
        go("Photo");
    }
    private void handleCapture(int result){
        File src=PhotoProvider.captureFile(this);
        if(result!=RESULT_OK)return;
        if(src.length()==0){notice("The camera did not return a photo. Try again, or use Choose from gallery.");return;}
        try{
            File dir=new File(getFilesDir(),"photos");dir.mkdirs();
            File dest=new File(dir,"IMG_"+System.currentTimeMillis()+".jpg");
            try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(dest)){
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))>0)out.write(buf,0,n);
            }
            src.delete();
            File[] all=dir.listFiles();
            if(all!=null&&all.length>10){
                Arrays.sort(all,(x,y)->Long.compare(x.lastModified(),y.lastModified()));
                for(int k=0;k<all.length-10;k++)all[k].delete();
            }
            useNewPhoto(Uri.fromFile(dest));
        }catch(IOException e){notice("Cannot save photo: "+e.getMessage());}
    }

    // ---------- Photo annotation ----------
    private static final String[] DIM_NAMES={"Width","Height","Depth"};
    private Bitmap loadPhoto(int maxSide)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(photoUri)){BitmapFactory.decodeStream(in,null,bounds);}
        int sample=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/sample>maxSide)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;
        Bitmap b;
        try(InputStream in=getContentResolver().openInputStream(photoUri)){b=BitmapFactory.decodeStream(in,null,opts);}
        if(b==null)throw new IOException("Unable to read photo");
        int rot=0;
        try(InputStream in=getContentResolver().openInputStream(photoUri)){
            int ori=new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);
            rot=ori==6?90:ori==3?180:ori==8?270:0;
        }catch(Exception ignored){}
        if(rot!=0){
            Matrix mx=new Matrix();mx.postRotate(rot);
            b=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),mx,true);
        }
        return b;
    }
    private void loadPhotoPts(){
        String v=getPreferences(MODE_PRIVATE).getString("photo_pts","");
        String[] t=v.split(",");
        if(t.length!=12)return;
        try{for(int k=0;k<12;k++)photoPts[k/4][k%4]=Math.max(0f,Math.min(1f,Float.parseFloat(t[k])));}
        catch(NumberFormatException ignored){}
    }
    private void savePhotoPts(){
        StringBuilder b=new StringBuilder();
        for(int k=0;k<12;k++){if(k>0)b.append(',');b.append(photoPts[k/4][k%4]);}
        getPreferences(MODE_PRIVATE).edit().putString("photo_pts",b.toString()).apply();
    }
    private void resetPhotoPts(){
        float[][] def={{.14f,.90f,.93f,.90f},{.07f,.08f,.07f,.84f},{.72f,.68f,.90f,.46f}};
        for(int a=0;a<3;a++)System.arraycopy(def[a],0,photoPts[a],0,4);
        savePhotoPts();
    }
    private void arrowHead(Canvas c,Paint p,float tipX,float tipY,float dirX,float dirY,float size){
        float nx=-dirY,ny=dirX;
        Path t=new Path();t.moveTo(tipX,tipY);
        t.lineTo(tipX-dirX*size*1.7f+nx*size*.62f,tipY-dirY*size*1.7f+ny*size*.62f);
        t.lineTo(tipX-dirX*size*1.7f-nx*size*.62f,tipY-dirY*size*1.7f-ny*size*.62f);
        t.close();c.drawPath(t,p);
    }
    /** Engineering style dimension line: end bars, filled arrowheads, dark outline for any background. */
    private void dimLine(Canvas c,Paint shadow,Paint line,float x1,float y1,float x2,float y2,float tick){
        float dx=x2-x1,dy=y2-y1,len=(float)Math.hypot(dx,dy);
        if(len<tick*3)return;
        float ux=dx/len,uy=dy/len,ah=tick*.62f;
        Paint sf=new Paint(shadow);sf.setStyle(Paint.Style.FILL_AND_STROKE);sf.setStrokeJoin(Paint.Join.ROUND);
        Paint lf=new Paint(line);lf.setStyle(Paint.Style.FILL);
        float nx=-uy*tick,ny=ux*tick;
        for(int pass=0;pass<2;pass++){
            Paint sp=pass==0?shadow:line,fp=pass==0?sf:lf;
            c.drawLine(x1+ux*ah*1.5f,y1+uy*ah*1.5f,x2-ux*ah*1.5f,y2-uy*ah*1.5f,sp);
            c.drawLine(x1-nx,y1-ny,x1+nx,y1+ny,sp);
            c.drawLine(x2-nx,y2-ny,x2+nx,y2+ny,sp);
            arrowHead(c,fp,x1,y1,-ux,-uy,ah);
            arrowHead(c,fp,x2,y2,ux,uy,ah);
        }
    }
    private void pillLabel(Canvas c,Paint pill,Paint txt,String s,float cx,float cy,float ts,int w,int h){
        float tw=txt.measureText(s),pad=ts*0.5f,half=tw/2+pad;
        cx=Math.max(half+4,Math.min(w-half-4,cx));
        cy=Math.max(ts*0.5f+pad+4,Math.min(h-ts*0.5f-pad-4,cy));
        RectF r=new RectF(cx-half,cy-ts*0.5f-pad,cx+half,cy+ts*0.5f+pad);
        Paint edge=new Paint(Paint.ANTI_ALIAS_FLAG);edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.rgb(255,224,0));edge.setStrokeWidth(Math.max(2f,ts/12f));
        c.drawRoundRect(r,ts*0.6f,ts*0.6f,pill);
        c.drawRoundRect(r,ts*0.6f,ts*0.6f,edge);
        c.drawText(s,cx,cy+ts*0.35f,txt);
    }
    /** Draws the dimension lines and labels, centred on each line. Shared by the live preview and the JPEG export. */
    private void drawDims(Canvas c,int w,int h,String[] v,String caption){
        float base=Math.min(w,h);
        float stroke=Math.max(4f,base/190f),ts=Math.max(22f,base/20f),tick=base/42f;
        Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);line.setStrokeCap(Paint.Cap.ROUND);
        Paint shadow=new Paint(line);
        shadow.setColor(Color.argb(215,0,0,0));shadow.setStrokeWidth(stroke*2.5f);
        line.setColor(Color.rgb(255,224,0));line.setStrokeWidth(stroke);
        Paint pill=new Paint(Paint.ANTI_ALIAS_FLAG);pill.setColor(Color.argb(225,10,14,22));
        Paint txt=new Paint(Paint.ANTI_ALIAS_FLAG);txt.setColor(Color.WHITE);txt.setTextSize(ts);
        txt.setTypeface(Typeface.DEFAULT_BOLD);txt.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<3;i++){
            if(v[i].isEmpty())continue;
            float[] q=photoPts[i];
            float x1=q[0]*w,y1=q[1]*h,x2=q[2]*w,y2=q[3]*h;
            dimLine(c,shadow,line,x1,y1,x2,y2,tick);
            pillLabel(c,pill,txt,DIM_NAMES[i]+" "+v[i]+" m",(x1+x2)/2f,(y1+y2)/2f,ts,w,h);
        }
        if(caption!=null&&!caption.isEmpty())
            pillLabel(c,pill,txt,caption,w/2f,base*0.07f+ts*0.6f,ts,w,h);
    }
    private final class AnnotView extends View{
        private final Bitmap bmp;private int dragDim=-1,dragEnd=0;
        private final Paint hp=new Paint(Paint.ANTI_ALIAS_FLAG);
        AnnotView(Context c,Bitmap b){super(c);bmp=b;}
        @Override protected void onMeasure(int wSpec,int hSpec){
            int w=MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w,Math.round(w*(float)bmp.getHeight()/bmp.getWidth()));
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            c.drawBitmap(bmp,null,new Rect(0,0,getWidth(),getHeight()),null);
            drawDims(c,getWidth(),getHeight(),photoVals,photoLabel);
            for(int i=0;i<3;i++){
                if(photoVals[i].isEmpty())continue;
                for(int e=0;e<2;e++){
                    float x=photoPts[i][e*2]*getWidth(),y=photoPts[i][e*2+1]*getHeight();
                    boolean on=i==dragDim&&e==dragEnd;
                    hp.setStyle(Paint.Style.FILL);hp.setColor(Color.argb(on?255:200,26,126,241));
                    c.drawCircle(x,y,dp(on?13:10),hp);
                    hp.setStyle(Paint.Style.STROKE);hp.setStrokeWidth(dp(2));hp.setColor(Color.WHITE);
                    c.drawCircle(x,y,dp(on?13:10),hp);
                }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent ev){
            float x=ev.getX(),y=ev.getY();
            switch(ev.getAction()){
                case MotionEvent.ACTION_DOWN:{
                    float best=dp(34);dragDim=-1;
                    for(int i=0;i<3;i++){
                        if(photoVals[i].isEmpty())continue;
                        for(int e=0;e<2;e++){
                            float d=(float)Math.hypot(x-photoPts[i][e*2]*getWidth(),y-photoPts[i][e*2+1]*getHeight());
                            if(d<best){best=d;dragDim=i;dragEnd=e;}
                        }
                    }
                    if(dragDim<0)return false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if(dragDim<0)return false;
                    photoPts[dragDim][dragEnd*2]=Math.max(0f,Math.min(1f,x/getWidth()));
                    photoPts[dragDim][dragEnd*2+1]=Math.max(0f,Math.min(1f,y/getHeight()));
                    invalidate();return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                    if(dragDim>=0){dragDim=-1;savePhotoPts();invalidate();}
                    return true;
            }
            return true;
        }
    }
    private void typePhotoValue(int i){
        EditText e=new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(photoVals[i]);e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(DIM_NAMES[i]+" in metres").setView(e)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("OK",(dialog,which)->{
                double v=positive(e);
                if(!Double.isFinite(v)){notice("Enter a number above zero");return;}
                photoVals[i]=fmt(v);photoSel=nextEmptyDim();render();
            }).show();
    }
    private int nextEmptyDim(){
        for(int i=0;i<3;i++)if(photoVals[i].isEmpty())return i;
        return -1;
    }
    private void dimTile(int i){
        boolean sel=i==photoSel,filled=!photoVals[i].isEmpty();
        LinearLayout tile=horizontal();tile.setBackground(tileBg(sel));pad(tile,16,12);
        LinearLayout left=vertical();
        left.addView(text(DIM_NAMES[i].toUpperCase(Locale.UK),12,MUTED,true));gap(left,2);
        left.addView(text(filled?photoVals[i]+" m":"—",28,WHITE,true));gap(left,2);
        left.addView(text(sel?"● Waiting… press measure on your IR40"
            :filled?"Tap to measure again":"Tap to select this box",13,sel?BLUE:MUTED,sel));
        tile.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        TextView type=text("Type",14,WHITE,true);type.setGravity(Gravity.CENTER);
        type.setBackground(bg(BORDER,10));type.setOnClickListener(v->typePhotoValue(i));
        tile.addView(type,lp(72,44));
        TextView clear=text("✕",16,MUTED,true);clear.setGravity(Gravity.CENTER);
        clear.setOnClickListener(v->{photoVals[i]="";photoSel=i;render();});
        if(filled)tile.addView(clear,lp(40,44));
        tile.setOnClickListener(v->{photoSel=i;render();});
        LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(10);content.addView(tile,p);
    }
    private void applyMeasurementToPhoto(Protocol.Measurement m){
        if(!"Distance".equals(m.kind)){notice("Set the IR40 to normal distance mode, then measure again");return;}
        if(photoSel<0||photoSel>=3){notice("Tap Width, Height or Depth first, then press measure");return;}
        int done=photoSel;
        photoVals[done]=fmt(m.value);photoProject=store.active();
        Toast.makeText(this,DIM_NAMES[done]+" = "+fmt(m.value)+" m",Toast.LENGTH_SHORT).show();
        photoSel=-1;
        for(int k=1;k<=3;k++){int j=(done+k)%3;if(photoVals[j].isEmpty()){photoSel=j;break;}}
        render();
    }
    private void recentPhotos(){
        File[] all=new File(getFilesDir(),"photos").listFiles();
        if(all==null||all.length==0)return;
        Arrays.sort(all,(x,y)->Long.compare(y.lastModified(),x.lastModified()));
        LinearLayout box=card(content);label(box,"Recent photos");
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row=horizontal();hs.addView(row);box.addView(hs);
        for(int k=0;k<Math.min(8,all.length);k++){
            File f=all[k];
            BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=16;
            Bitmap t=BitmapFactory.decodeFile(f.getPath(),o);
            if(t==null)continue;
            ImageView iv=new ImageView(this);iv.setImageBitmap(t);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            boolean cur=photoUri!=null&&Uri.fromFile(f).equals(photoUri);
            GradientDrawable ring=bg(Color.TRANSPARENT,8);ring.setStroke(dp(cur?3:1),cur?BLUE:BORDER);iv.setForeground(ring);
            iv.setClipToOutline(true);iv.setBackground(bg(BORDER,8));
            iv.setOnTouchListener(pressFx);
            iv.setOnClickListener(v->{photoUri=Uri.fromFile(f);photoBmp=null;photoProject=store.active();
                photoVals[0]="";photoVals[1]="";photoVals[2]="";photoSel=0;resetPhotoPts();
                getPreferences(MODE_PRIVATE).edit().putString("photo_uri",photoUri.toString()).apply();render();});
            LinearLayout.LayoutParams p=lp(84,84);p.rightMargin=dp(8);row.addView(iv,p);
        }
    }
    private void showPhoto(){
        section("Photo annotation");
        LinearLayout how=card(content);
        how.addView(text("1. Take or choose a photo. A new photo is added here automatically.\n2. Tap Width, Height or Depth, then press measure on your IR40.\n3. Drag the blue handles to place each line on the object.\n4. Export the annotated JPEG, or include it in your report.",15,WHITE,false));
        button(content,"Take a photo",this::takePhoto,false);
        button(content,"Choose from gallery",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i,PHOTO_PICK);
        },true);
        recentPhotos();
        if(photoUri!=null&&photoBmp==null){
            try{photoBmp=loadPhoto(1600);}catch(Exception e){notice("Cannot open photo: "+e.getMessage());}
        }
        if(photoBmp!=null){
            LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(12);
            content.addView(new AnnotView(this,photoBmp),p);
        }
        for(int i=0;i<3;i++)dimTile(i);
        EditText caption=input(content,"Optional title (shown at the top)",photoLabel);
        button(content,"Export annotated JPEG",()->{
            photoLabel=caption.getText().toString().trim();
            getPreferences(MODE_PRIVATE).edit().putString("photo_caption",photoLabel).apply();
            if(photoUri==null){notice("Choose a photo first");return;}
            if(photoVals[0].isEmpty()&&photoVals[1].isEmpty()&&photoVals[2].isEmpty()){
                notice("Add at least one measurement first");return;
            }
            try{
                pendingFile=annotatedPhoto();
                createDocument("image/jpeg","Blake-annotated-measurement.jpg",SAVE_PHOTO);
            }catch(Exception e){notice("Image export failed: "+e.getMessage());}
        },false);
        button(content,"Reset line positions",()->{resetPhotoPts();render();},true);
        button(content,"Clear all measurements",()->{
            photoVals[0]="";photoVals[1]="";photoVals[2]="";photoSel=0;render();
        },true);
        content.addView(text("Labels come from your IR40 readings or typed values. They are not measured from the photo.",13,MUTED,false));
    }
    private byte[] annotatedPhoto()throws IOException{
        Bitmap overlay=loadPhoto(3000).copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas=new Canvas(overlay);
        drawDims(canvas,overlay.getWidth(),overlay.getHeight(),photoVals,photoLabel);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!overlay.compress(Bitmap.CompressFormat.JPEG,92,out))throw new IOException("Image encoding failed");
        return out.toByteArray();
    }

    private void showSettings(){
        section("Settings and export");
        LinearLayout c=card(content);label(c,"Capture and Bluetooth");
        button(c,"Automatic saving: "+(store.autoSave()?"ON":"OFF"),()->{
            store.setAutosave(!store.autoSave());render();
        },false);
        button(c,"Automatic reconnect: "+(reconnect?"ON":"OFF"),()->{
            reconnect=!reconnect;ble.setReconnect(reconnect);
            getPreferences(MODE_PRIVATE).edit().putBoolean("reconnect",reconnect).apply();render();
        },true);
        line(c,"Device-reference codes are logged but unit and reference controls require hardware validation.");
        LinearLayout cal=card(content);label(cal,"Measuring offset");
        line(cal,offsetMm==0?"No offset. Readings are exactly what the IR40 reports.":"Offset "+offsetText()+" is added to every distance reading.");
        button(cal,"Set offset (mm)",this::askOffset,false);
        if(offsetMm!=0)button(cal,"Remove offset",()->{setOffset(0);render();},true);
        button(cal,"Check accuracy",()->go("Accuracy"),true);
        LinearLayout br=card(content);label(br,"Report branding");
        line(br,"Your details appear on printed and PDF reports. Add your own logo to present reports as yours.");
        EditText bizName=input(br,"Business name",prefs().getString("biz_name",""));
        EditText bizBy=input(br,"Prepared by",prefs().getString("biz_by",""));
        EditText bizRef=input(br,"Job or customer reference",prefs().getString("biz_ref",""));
        File logoFile=new File(getFilesDir(),"business_logo.png");
        if(logoFile.exists()){
            ImageView lv=new ImageView(this);lv.setImageBitmap(BitmapFactory.decodeFile(logoFile.getPath()));
            lv.setAdjustViewBounds(true);lv.setBackgroundColor(Color.WHITE);pad(lv,12,10);
            br.addView(lv,lp(-1,90));gap(br,8);
        }
        button(br,"Save details",()->{
            prefs().edit().putString("biz_name",bizName.getText().toString().trim())
                .putString("biz_by",bizBy.getText().toString().trim())
                .putString("biz_ref",bizRef.getText().toString().trim()).apply();
            notice("Report details saved");
        },false);
        button(br,logoFile.exists()?"Change business logo":"Add business logo",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i,BUSINESS_LOGO);
        },true);
        if(logoFile.exists())button(br,"Remove business logo",()->{logoFile.delete();render();},true);
        LinearLayout ex=card(content);label(ex,"Reports · selected project");
        button(ex,"Export CSV",()->{
            pendingFile=csv().getBytes(StandardCharsets.UTF_8);
            createDocument("text/csv","RangeFinder-measurements.csv",SAVE_CSV);
        },false);
        button(ex,"Export branded PDF",()->{
            try{pendingFile=pdf();createDocument("application/pdf","Blake-RangeFinder-report.pdf",SAVE_PDF);}
            catch(Exception e){notice("PDF generation failed: "+e.getMessage());}
        },false);
        button(ex,"Print report",()->{
            try{printReport(pdf());}catch(Exception e){notice("Print failed: "+e.getMessage());}
        },false);
        button(ex,"Include annotated photo: "+(prefs().getBoolean("rep_photo",true)?"ON":"OFF"),()->{
            prefs().edit().putBoolean("rep_photo",!prefs().getBoolean("rep_photo",true)).apply();render();
        },true);
        button(ex,"Backup all projects (JSON)",()->{
            pendingFile=store.exportJson().getBytes(StandardCharsets.UTF_8);
            createDocument("application/json","Blake-RangeFinder-backup.json",SAVE_BACKUP);
        },true);
        button(ex,"Restore JSON backup",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i,RESTORE_BACKUP);
        },true);
        LinearLayout diag=card(content);label(diag,"Bluetooth diagnostics");
        line(diag,"Connection: "+connection+"\nPackets: "+sessionPackets+"\nLast: "+lastRaw);
        button(diag,"Copy diagnostic log",()->{
            android.content.ClipboardManager cb=
                (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("IR40 diagnostics",log.toString()));
            notice("Diagnostic log copied");
        },true);
        line(diag,log.length()>1200?log.substring(log.length()-1200):log.toString());
        line(diag,"IR40 Bluetooth compatibility requires a live hardware test.");
    }
    private String csv(){
        StringBuilder b=new StringBuilder("Project,Date,Type,Value,Unit,Source,Notes\r\n");
        JSONArray records=store.records();
        for(int i=0;i<records.length();i++){
            JSONObject r=records.optJSONObject(i);
            if(r==null||!store.active().equals(r.optString("projectId")))continue;
            String date=DateFormat.getDateTimeInstance().format(new Date(r.optLong("time")));
            String[] fields={projectName(store.active()),date,r.optString("kind"),
                fmt(r.optDouble("metres")),unitSuffix(r),r.optString("source"),r.optString("notes")};
            for(int j=0;j<fields.length;j++){
                if(j>0)b.append(',');
                b.append('"').append(fields[j].replace("\"","\"\"")).append('"');
            }
            b.append("\r\n");
        }
        return b.toString();
    }
    private SharedPreferences prefs(){return getPreferences(MODE_PRIVATE);}
    private void saveBusinessLogo(Uri uri)throws IOException{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,o);}
        int sample=1;while(Math.max(o.outWidth,o.outHeight)/sample>900)sample*=2;
        BitmapFactory.Options d=new BitmapFactory.Options();d.inSampleSize=sample;
        Bitmap b;
        try(InputStream in=getContentResolver().openInputStream(uri)){b=BitmapFactory.decodeStream(in,null,d);}
        if(b==null)throw new IOException("Unreadable image");
        try(OutputStream out=new FileOutputStream(new File(getFilesDir(),"business_logo.png"))){
            b.compress(Bitmap.CompressFormat.PNG,100,out);
        }
    }
    private byte[] pdf()throws IOException{
        ReportPdf.Data d=new ReportPdf.Data();
        d.business=prefs().getString("biz_name","");d.preparedBy=prefs().getString("biz_by","");
        d.jobRef=prefs().getString("biz_ref","");d.project=projectName(store.active());
        d.dateText=DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.SHORT,Locale.UK).format(new Date());
        File logo=new File(getFilesDir(),"business_logo.png");
        if(logo.exists())d.businessLogo=BitmapFactory.decodeFile(logo.getPath());
        d.blakeLogo=BitmapFactory.decodeResource(getResources(),R.drawable.blake_logo_print);
        d.rows=new ArrayList<>();
        JSONArray arr=store.records();int n=0;
        for(int i=0;i<arr.length();i++){
            JSONObject v=arr.optJSONObject(i);
            if(v==null||!store.active().equals(v.optString("projectId")))continue;
            d.rows.add(new String[]{String.valueOf(++n),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,Locale.UK).format(new Date(v.optLong("time"))),
                v.optString("kind"),fmt(v.optDouble("metres"))+" "+unitSuffix(v),v.optString("notes")});
        }
        JSONObject plan=store.plans().optJSONObject(store.active());
        if(plan!=null){d.planLength=plan.optDouble("length");d.planWidth=plan.optDouble("width");}
        boolean measured=!photoVals[0].isEmpty()||!photoVals[1].isEmpty()||!photoVals[2].isEmpty();
        if(photoUri!=null&&measured&&store.active().equals(photoProject)&&prefs().getBoolean("rep_photo",true)){
            try{
                Bitmap ph=loadPhoto(1600).copy(Bitmap.Config.ARGB_8888,true);
                drawDims(new Canvas(ph),ph.getWidth(),ph.getHeight(),photoVals,photoLabel);
                d.photo=ph;d.photoCaption=photoLabel;
            }catch(Exception ignored){}
        }
        return ReportPdf.build(d);
    }
    private void printReport(final byte[] bytes){
        PrintManager pm=(PrintManager)getSystemService(PRINT_SERVICE);
        pm.print("Blake RangeFinder report",new PrintDocumentAdapter(){
            @Override public void onLayout(PrintAttributes o,PrintAttributes n,CancellationSignal cs,LayoutResultCallback cb,Bundle b){
                if(cs.isCanceled()){cb.onLayoutCancelled();return;}
                cb.onLayoutFinished(new PrintDocumentInfo.Builder("Blake-RangeFinder-report.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(),true);
            }
            @Override public void onWrite(PageRange[] pages,ParcelFileDescriptor dest,CancellationSignal cs,WriteResultCallback cb){
                try(OutputStream out=new FileOutputStream(dest.getFileDescriptor())){
                    out.write(bytes);out.flush();cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                }catch(IOException e){cb.onWriteFailed(e.getMessage());}
            }
        },null);
    }
    private void createDocument(String mime,String name,int req){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,req);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==PHOTO_CAPTURE){handleCapture(result);return;}
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(req==PHOTO_PICK){
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored){}
            useNewPhoto(uri);return;
        }
        if(req==BUSINESS_LOGO){
            try{saveBusinessLogo(uri);notice("Business logo saved");render();}
            catch(Exception e){notice("Cannot use that image: "+e.getMessage());}
            return;
        }
        if(req==RESTORE_BACKUP){
            try(InputStream in=getContentResolver().openInputStream(uri)){
                String json=new String(ProjectStore.readAll(in),StandardCharsets.UTF_8);
                new AlertDialog.Builder(this).setTitle("Replace all local project data?")
                    .setMessage("Restore replaces all current records. Export your existing backup first.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Restore",(dialog,which)->{
                        try{store.restore(json);render();notice("Backup restored");}
                        catch(Exception e){notice("Restore failed: "+e.getMessage());}
                    }).show();
            }catch(Exception e){notice("Cannot read backup: "+e.getMessage());}
            return;
        }
        if(req==SAVE_CSV||req==SAVE_PDF||req==SAVE_BACKUP||req==SAVE_PHOTO){
            if(pendingFile==null){notice("Export data missing");return;}
            try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                out.write(pendingFile);out.flush();notice("Export saved");
            }catch(Exception e){notice("Cannot save export: "+e.getMessage());}
            finally{pendingFile=null;}
        }
    }

    // ---------- BleClient callbacks ----------
    @Override public void state(String s){
        runOnUiThread(()->{
            connection=s;if("Disconnected".equals(s))last=null;
            if(s.startsWith(READY_PREFIX)){
                String addr=ble.connectedAddress();
                if(!addr.isEmpty())getPreferences(MODE_PRIVATE).edit().putString("last_device",addr).apply();
            }
            updateStatus();
            if("Measure".equals(page))render();
        });
    }
    @Override public void device(BluetoothDevice d,int rssi){
        runOnUiThread(()->{
            if(autoConnect&&!userDisconnected&&!ready()&&!connection.startsWith("Connecting")){
                String saved=getPreferences(MODE_PRIVATE).getString("last_device","");
                if(saved.isEmpty()||saved.equals(d.getAddress())){autoConnect=false;ble.connect(d);return;}
            }
            if("Measure".equals(page))render();
        });
    }
    @Override public void measurement(Protocol.Measurement incoming,String raw){
        final Protocol.Measurement m=adjust(incoming);
        runOnUiThread(()->{
            last=m;lastRaw=raw;sessionPackets++;
            if("Distance".equals(m.kind)){
                sessionCount++;sessionSum+=m.value;
                sessionMin=Double.isNaN(sessionMin)?m.value:Math.min(sessionMin,m.value);
                sessionMax=Double.isNaN(sessionMax)?m.value:Math.max(sessionMax,m.value);
            }
            boolean calculating="Calculate".equals(page),photoPage="Photo".equals(page),accPage="Accuracy".equals(page);
            if(store.autoSave()&&!calculating&&!photoPage&&!accPage)try{store.addRecord(m,"IR40");}
                catch(Exception e){log("Unable to save reading: "+e.getMessage());}
            if(calculating)applyMeasurementToCalc(m);
            else if(photoPage)applyMeasurementToPhoto(m);
            else if(accPage)addAccuracyReading(m);
            else if("Measure".equals(page))render();
        });
    }
    @Override public void log(String message){
        runOnUiThread(()->{
            log.append(DateFormat.getTimeInstance().format(new Date()))
                .append("   ").append(message).append('\n');
            if(log.length()>12000)log.delete(0,log.length()-9000);
        });
    }
}
