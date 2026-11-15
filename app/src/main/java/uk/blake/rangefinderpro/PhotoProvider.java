package uk.blake.rangefinderpro;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Hands the camera app one private cache file to write a photo into. No other file is ever exposed. */
public final class PhotoProvider extends ContentProvider {
    public static final String AUTHORITY="uk.blake.rangefinderpro.photos";
    public static Uri captureUri(){return Uri.parse("content://"+AUTHORITY+"/capture.jpg");}
    public static File captureFile(Context c){
        File dir=new File(c.getCacheDir(),"photos");dir.mkdirs();
        return new File(dir,"capture.jpg");
    }
    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        File f=captureFile(getContext());
        boolean write=mode.contains("w");
        if(write&&!f.exists()){
            try{f.createNewFile();}catch(IOException e){throw new FileNotFoundException(e.getMessage());}
        }
        int flags=ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(f,flags);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
        c.addRow(new Object[]{"capture.jpg",captureFile(getContext()).length()});
        return c;
    }
    @Override public String getType(Uri uri){return "image/jpeg";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
}
