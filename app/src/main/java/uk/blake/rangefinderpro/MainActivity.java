package uk.blake.rangefinderpro;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.bluetooth.BluetoothDevice;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
        WHITE=Color.WHITE, MUTED=Color.rgb(170,185,205), GREEN=Color.rgb(60,210,153), BORDER=Color.rgb(60,78,99);
    private static final int SAVE_CSV=11,SAVE_PDF=12,SAVE_BACKUP=13,RESTORE_BACKUP=14,PHOTO_PICK=15,SAVE_PHOTO=16;
    private LinearLayout root,content;
    private ProjectStore store;
    private BleClient ble;
    private String page="Measure",connection="Disconnected",lastRaw="—";
    private Protocol.Measurement last;
    private int sessionCount=0,sessionPackets=0;
    private double sessionSum=0,sessionMin=Double.NaN,sessionMax=Double.NaN;
    private final StringBuilder log=new StringBuilder();
    private byte[] pendingFile;
    private Uri photoUri;
    private String photoLabel="1.234 m";
    private EditText lengthInput,widthInput;
    private boolean reconnect=true;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        store=new ProjectStore(this);ble=new BleClient(this,this);
        reconnect=getPreferences(MODE_PRIVATE).getBoolean("reconnect",true);ble.setReconnect(reconnect);
        String uri=getPreferences(MODE_PRIVATE).getString("photo_uri","");
        if(!uri.isEmpty())photoUri=Uri.parse(uri);
        photoLabel=getPreferences(MODE_PRIVATE).getString("photo_label",photoLabel);
        render();
    }
    @Override protected void onDestroy(){ble.close();super.onDestroy();}
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
        Button b=new Button(this);b.setText(name);b.setTextAllCaps(false);b.setTextColor(WHITE);b.setTextSize(14);
        b.setBackground(bg(secondary?BORDER:BLUE,10));b.setOnClickListener(v->run.run());
        LinearLayout.LayoutParams p=lp(-1,48);p.bottomMargin=dp(8);parent.addView(b,p);return b;
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
    private void section(String title){content.addView(text(title,21,WHITE,true));gap(content,15);}
    private String fmt(double v){return String.format(Locale.UK,"%.3f",v);}

    private void render(){
        root=vertical();root.setBackgroundColor(NAVY);setContentView(root);
        LinearLayout header=horizontal();pad(header,17,9);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.blake_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);header.addView(logo,lp(125,46));
        LinearLayout names=vertical();names.addView(text("RangeFinder Pro",16,WHITE,true));
        names.addView(text("IR40 · 2.1.0 beta",11,MUTED,false));
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,dp(48),1);
        np.leftMargin=dp(12);header.addView(names,np);root.addView(header,lp(-1,70));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        content=vertical();pad(content,16,16);scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        switch(page){
            case "Projects":showProjects();break;
            case "Plan":showPlan();break;
            case "Tools":showTools();break;
            case "Settings":showSettings();break;
            case "Photo":showPhoto();break;
            default:showMeasure();
        }
        HorizontalScrollView bottom=new HorizontalScrollView(this);bottom.setHorizontalScrollBarEnabled(false);
        bottom.setBackgroundColor(CARD);LinearLayout nav=horizontal();
        for(String p:new String[]{"Measure","Projects","Plan","Tools","Photo","Settings"}){
            Button b=new Button(this);b.setText(p);b.setTextSize(12);b.setAllCaps(false);
            b.setTextColor(p.equals(page)?GREEN:WHITE);b.setBackgroundColor(CARD);
            b.setOnClickListener(v->{page=p;render();});nav.addView(b,lp(100,54));
        }
        bottom.addView(nav);root.addView(bottom,lp(-1,56));
    }

    private void showMeasure(){
        section("Live measurement");
        LinearLayout device=card(content);label(device,"IR40 Bluetooth");line(device,connection);
        button(device,"Scan for IR40",this::startScan,false);
        button(device,"Choose discovered device ("+ble.devices().size()+")",this::chooseDevice,true);
        button(device,"Disconnect",()->{ble.disconnect();last=null;render();},true);
        LinearLayout display=card(content);label(display,"Measurement");
        display.addView(text(last==null?"—":last.display(),34,WHITE,true));gap(display,8);
        if(last!=null){
            line(display,"Type: "+last.kind);
            if("Distance".equals(last.kind))
                line(display,"Nominal tolerance: ±"+String.format(Locale.UK,"%.2f mm",Protocol.toleranceMm(last.value)));
            line(display,"Device unit code: "+(last.unitCode==null?"unknown":last.unitCode));
            line(display,"Reference code: "+(last.referenceCode==null?"unknown":last.referenceCode));
        }
        button(display,"Save last reading",()->{
            if(last==null){notice("Measure first");return;}
            try{store.addRecord(last,"IR40");render();}catch(Exception e){notice("Save failed: "+e.getMessage());}
        },false);
        line(display,"Auto-save "+(store.autoSave()?"ON":"OFF")+" · "+sessionPackets+" decoded readings");
        LinearLayout stats=card(content);label(stats,"Current session");
        line(stats,"Count: "+sessionCount+"   Min: "+(Double.isNaN(sessionMin)?"—":fmt(sessionMin)+" m"));
        line(stats,"Max: "+(Double.isNaN(sessionMax)?"—":fmt(sessionMax)+" m")
            +"   Average: "+(sessionCount==0?"—":fmt(sessionSum/sessionCount)+" m"));
        button(stats,"Reset session stats",()->{
            sessionCount=0;sessionPackets=0;sessionSum=0;sessionMin=Double.NaN;sessionMax=Double.NaN;render();
        },true);
        showHistory(12);
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
    private void startScan(){
        if(!ble.enabled()){
            new AlertDialog.Builder(this).setTitle("Bluetooth disabled")
                .setMessage("Switch on Bluetooth, then scan again.")
                .setPositiveButton("OK",null).show();return;
        }
        if(ble.permissionGranted()){ble.scan();render();return;}
        if(Build.VERSION.SDK_INT>=31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},7);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},7);
    }
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] g){
        super.onRequestPermissionsResult(req,p,g);
        if(req==7){if(ble.permissionGranted())ble.scan();else notice("Nearby devices permission required");render();}
    }
    private void chooseDevice(){
        Map<String,BluetoothDevice> devices=ble.devices();
        if(devices.isEmpty()){notice("No IR40 found; run a scan with the device switched on");return;}
        ArrayList<BluetoothDevice> values=new ArrayList<>(devices.values());
        String[] names=new String[values.size()];
        for(int i=0;i<names.length;i++){
            BluetoothDevice d=values.get(i);String n="IR40";
            try{if(d.getName()!=null)n=d.getName();}catch(Exception ignored){}
            names[i]=n+"    "+d.getAddress();
        }
        new AlertDialog.Builder(this).setTitle("Connect to IR40")
            .setItems(names,(dialog,which)->{ble.connect(values.get(which));render();}).show();
    }

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

    private void showTools(){
        section("Measurement tools");
        LinearLayout c=card(content);label(c,"Calculator · metres");
        EditText a=decimal(c,"Length / A",""),b=decimal(c,"Width / B",""),h=decimal(c,"Height / C","");
        button(c,"Calculate area",()->{
            double area=Protocol.area(positive(a),positive(b));
            notice(Double.isFinite(area)?"Area = "+fmt(area)+" m²":"Enter positive length and width");
        },false);
        button(c,"Calculate volume",()->{
            double v=Protocol.volume(positive(a),positive(b),positive(h));
            notice(Double.isFinite(v)?"Volume = "+fmt(v)+" m³":"Enter three positive dimensions");
        },false);
        button(c,"Pythagoras · hypotenuse",()->{
            double x=positive(a),y=positive(b);
            notice(Double.isFinite(x)&&Double.isFinite(y)?"Hypotenuse = "+fmt(Math.hypot(x,y))+" m":"Enter positive A and B");
        },true);
        LinearLayout manual=card(content);label(manual,"Manual measurement");
        EditText value=decimal(manual,"Distance in metres","");
        button(manual,"Save manual measurement",()->{
            double v=positive(value);
            if(!Double.isFinite(v)){notice("Enter a valid distance");return;}
            store.addManual(v);notice("Measurement saved");render();
        },false);
    }

    private void showPhoto(){
        section("Photo annotation");LinearLayout c=card(content);label(c,"Photo + measurement caption");
        line(c,"Select a photo, add a dimension and export an annotated JPEG.");
        if(photoUri!=null){
            try{
                ImageView iv=new ImageView(this);
                iv.setAdjustViewBounds(true);iv.setImageURI(photoUri);c.addView(iv,lp(-1,230));
            }catch(Exception ignored){}
        }
        button(c,"Choose photo",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i,PHOTO_PICK);
        },true);
        EditText caption=input(c,"Measurement caption",photoLabel);
        button(c,"Export annotated JPEG",()->{
            photoLabel=caption.getText().toString().trim();
            getPreferences(MODE_PRIVATE).edit().putString("photo_label",photoLabel).apply();
            if(photoUri==null){notice("Choose a photo first");return;}
            try{
                pendingFile=annotatedPhoto();
                createDocument("image/jpeg","Blake-annotated-measurement.jpg",SAVE_PHOTO);
            }catch(Exception e){notice("Image export failed: "+e.getMessage());}
        },false);
        line(c,"A caption is not a camera-derived dimension. Verify values using the IR40.");
    }
    private byte[] annotatedPhoto()throws IOException{
        Bitmap bitmap;
        try(InputStream in=getContentResolver().openInputStream(photoUri)){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;
            bitmap=BitmapFactory.decodeStream(in,null,options);
        }
        if(bitmap==null)throw new IOException("Unable to read photo");
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        Bitmap overlay=bitmap.copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas=new Canvas(overlay);Paint paint=new Paint(3);
        float margin=w*.08f,y=h*.13f,stroke=Math.max(5,w/175f);
        paint.setColor(Color.YELLOW);paint.setStrokeWidth(stroke);
        canvas.drawLine(margin,y,w-margin,y,paint);
        canvas.drawLine(margin,y-18,margin,y+18,paint);
        canvas.drawLine(w-margin,y-18,w-margin,y+18,paint);
        paint.setColor(Color.BLACK);paint.setTextSize(Math.max(28,w/24f));
        paint.setTypeface(Typeface.DEFAULT_BOLD);canvas.drawText(photoLabel,margin+10,y-22,paint);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!overlay.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("Image encoding failed");
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
        LinearLayout ex=card(content);label(ex,"Reports · selected project");
        button(ex,"Export CSV",()->{
            pendingFile=csv().getBytes(StandardCharsets.UTF_8);
            createDocument("text/csv","RangeFinder-measurements.csv",SAVE_CSV);
        },false);
        button(ex,"Export branded PDF",()->{
            try{pendingFile=pdf();createDocument("application/pdf","Blake-RangeFinder-report.pdf",SAVE_PDF);}
            catch(Exception e){notice("PDF generation failed: "+e.getMessage());}
        },false);
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
    private byte[] pdf()throws IOException{
        android.graphics.pdf.PdfDocument doc=new android.graphics.pdf.PdfDocument();
        Paint paint=new Paint(3);int pageNo=0,y=0;
        android.graphics.pdf.PdfDocument.Page page=null;
        Canvas canvas=null;
        ArrayList<String> lines=new ArrayList<>();
        lines.add("Blake UK · RangeFinder Pro");
        lines.add("Measurement report");
        lines.add("Project: "+projectName(store.active()));
        lines.add("Created: "+DateFormat.getDateTimeInstance().format(new Date()));
        lines.add("");
        JSONArray arr=store.records();int n=0;
        for(int i=0;i<arr.length();i++){
            JSONObject v=arr.optJSONObject(i);
            if(v==null||!store.active().equals(v.optString("projectId")))continue;
            lines.add((++n)+". "+v.optString("kind")+"   "+fmt(v.optDouble("metres"))+" "+unitSuffix(v));
        }
        lines.add("");lines.add("Verify critical measurements against the physical IR40 display.");
        for(String l:lines){
            if(page==null||y>790){
                if(page!=null)doc.finishPage(page);
                page=doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(595,842,++pageNo).create());
                canvas=page.getCanvas();canvas.drawColor(Color.WHITE);y=60;
                Bitmap logo=BitmapFactory.decodeResource(getResources(),R.drawable.blake_logo);
                if(logo!=null)canvas.drawBitmap(logo,null,new Rect(40,15,160,51),paint);
            }
            paint.setColor(Color.rgb(30,42,59));
            paint.setTextSize(y<140?17:11);
            paint.setTypeface(y<140?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
            canvas.drawText(l.length()>93?l.substring(0,93):l,40,y,paint);
            y+=22;
        }
        if(page!=null)doc.finishPage(page);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        doc.writeTo(out);doc.close();return out.toByteArray();
    }
    private void createDocument(String mime,String name,int req){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,req);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(req==PHOTO_PICK){
            photoUri=uri;
            getPreferences(MODE_PRIVATE).edit().putString("photo_uri",uri.toString()).apply();
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored){}
            render();return;
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

    @Override public void state(String s){
        runOnUiThread(()->{
            connection=s;if("Disconnected".equals(s))last=null;
            if("Measure".equals(page))render();
        });
    }
    @Override public void device(BluetoothDevice d,int rssi){
        runOnUiThread(()->{if("Measure".equals(page))render();});
    }
    @Override public void measurement(Protocol.Measurement m,String raw){
        runOnUiThread(()->{
            last=m;lastRaw=raw;sessionPackets++;
            if("Distance".equals(m.kind)){
                sessionCount++;sessionSum+=m.value;
                sessionMin=Double.isNaN(sessionMin)?m.value:Math.min(sessionMin,m.value);
                sessionMax=Double.isNaN(sessionMax)?m.value:Math.max(sessionMax,m.value);
            }
            if(store.autoSave())try{store.addRecord(m,"IR40");}
                catch(Exception e){log("Unable to save reading: "+e.getMessage());}
            if("Measure".equals(page))render();
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
