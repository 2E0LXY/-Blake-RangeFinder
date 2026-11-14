package uk.blake.rangefinderpro;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Offline application-private JSON store. Writes through a temporary file. */
public final class ProjectStore {
    private static final String FILE="rangefinder-pro-v1.json";
    private final Context context;
    private JSONObject data;
    public ProjectStore(Context ctx){context=ctx.getApplicationContext();load();}
    private void load(){
        try(FileInputStream in=context.openFileInput(FILE)){
            data=new JSONObject(new String(readAll(in),StandardCharsets.UTF_8));validate(data);
        }catch(Exception e){data=empty();}
    }
    private static JSONObject empty(){
        try{
            return new JSONObject().put("schema",1).put("active","general")
                .put("autosave",true).put("unit","m")
                .put("projects",new JSONArray().put(new JSONObject().put("id","general").put("name","General measurements")))
                .put("records",new JSONArray()).put("plans",new JSONObject());
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    private static void validate(JSONObject d)throws JSONException{
        if(d.optInt("schema")!=1||d.optJSONArray("projects")==null||
            d.optJSONArray("records")==null||d.optJSONObject("plans")==null)
            throw new JSONException("Unsupported or incomplete backup");
        if(d.optJSONArray("records").length()>100000)throw new JSONException("Too many records");
    }
    public synchronized JSONArray projects(){return data.optJSONArray("projects");}
    public synchronized JSONArray records(){return data.optJSONArray("records");}
    public synchronized JSONObject plans(){return data.optJSONObject("plans");}
    public synchronized String active(){return data.optString("active","general");}
    public synchronized String unit(){return data.optString("unit","m");}
    public synchronized boolean autoSave(){return data.optBoolean("autosave",true);}
    public synchronized void setUnit(String unit){
        try{data.put("unit",unit);save();}catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized void setAutosave(boolean on){
        try{data.put("autosave",on);save();}catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized void setActive(String id){
        try{data.put("active",id);save();}catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized void createProject(String name){
        try{
            String id=UUID.randomUUID().toString();
            projects().put(new JSONObject().put("id",id).put("name",name));
            data.put("active",id);save();
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized void addRecord(Protocol.Measurement m,String source){
        try{
            JSONObject record=new JSONObject().put("id",UUID.randomUUID().toString())
                .put("projectId",active()).put("time",System.currentTimeMillis())
                .put("kind",m.kind).put("mode",m.mode).put("metres",m.value)
                .put("a",m.a).put("b",m.b).put("c",m.c)
                .put("source",source).put("notes","").put("photo","");
            JSONArray old=records(),next=new JSONArray().put(record);
            for(int i=0;i<old.length()&&i<9999;i++)next.put(old.getJSONObject(i));
            data.put("records",next);save();
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized void addManual(double value){
        addRecord(new Protocol.Measurement(0,value,0,0,0,null,null),"manual");
    }
    public synchronized void setPlan(double length,double width){
        try{
            plans().put(active(),new JSONObject().put("length",length)
                .put("width",width).put("saved",System.currentTimeMillis()));save();
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized String exportJson(){
        try{return data.toString(2);}catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized void restore(String text)throws JSONException{
        JSONObject incoming=new JSONObject(text);validate(incoming);
        JSONObject old=data;data=incoming;
        try{save();}catch(RuntimeException e){data=old;throw e;}
    }
    private void save(){
        File dest=new File(context.getFilesDir(),FILE),temp=new File(context.getFilesDir(),FILE+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){
            out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }catch(IOException e){throw new IllegalStateException("Saving failed",e);}
        if(!temp.renameTo(dest))throw new IllegalStateException("Could not save project data");
    }
    public static byte[] readAll(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n,total=0;
        while((n=input.read(buf))!=-1){
            total+=n;if(total>10_000_000)throw new IOException("File exceeds 10 MB limit");
            out.write(buf,0,n);
        }
        return out.toByteArray();
    }
}
