package uk.blake.rangefinderpro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Builds the A4 measurement report. Two passes so every footer can say "Page x of y". */
final class ReportPdf {
    static final class Data {
        String business="",preparedBy="",jobRef="",project="",dateText="",instrument="FNIRSI IR40 via Blake RangeFinder Pro";
        Bitmap businessLogo,blakeLogo,photo;
        String photoCaption="";
        List<String[]> rows;              // {no, date, type, value, notes}
        double planLength,planWidth;
    }
    private static final int NAVY=Color.rgb(13,19,29),BLUE=Color.rgb(26,126,241),INK=Color.rgb(30,42,59),
        GREY=Color.rgb(110,122,140),LIGHT=Color.rgb(243,246,250),RULE=Color.rgb(214,222,232);
    private static final int W=595,H=842,MX=40,TOP=118,BOTTOM=800;

    private final Data d;private final int total;
    private PdfDocument doc;private PdfDocument.Page page;private Canvas c;
    private int pageNo=0;private float y;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);

    private ReportPdf(Data d,int total){this.d=d;this.total=total;}

    static byte[] build(Data d)throws IOException{
        ReportPdf first=new ReportPdf(d,0);first.run();
        ReportPdf second=new ReportPdf(d,first.pageNo);
        return second.run();
    }

    private byte[] run()throws IOException{
        doc=new PdfDocument();
        newPage(true);
        metaCard();
        table();
        plan();
        closing();
        if(d.photo!=null)photoPage();
        doc.finishPage(page);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        doc.writeTo(out);doc.close();return out.toByteArray();
    }

    // ---- page furniture ----
    private void newPage(boolean first){
        if(page!=null)doc.finishPage(page);
        page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,++pageNo).create());
        c=page.getCanvas();c.drawColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);p.setColor(BLUE);c.drawRect(0,0,W,7,p);
        Bitmap logo=d.businessLogo!=null?d.businessLogo:d.blakeLogo;
        float lh=first?54:34;
        if(logo!=null)fit(logo,MX,22,first?170:110,lh);
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(GREY);p.setTextSize(8.5f);
        c.drawText(first?"MEASUREMENT REPORT":"MEASUREMENT REPORT  ·  "+d.project,W-MX,42,p);
        p.setTextAlign(Paint.Align.LEFT);
        // footer
        p.setColor(RULE);p.setStrokeWidth(0.8f);c.drawLine(MX,812,W-MX,812,p);
        p.setTypeface(Typeface.DEFAULT);p.setTextSize(8);p.setColor(GREY);
        c.drawText(d.businessLogo!=null||!d.business.isEmpty()?"Created with Blake RangeFinder Pro by Blake UK  ·  blake-uk.com"
            :"Blake UK  ·  blake-uk.com",MX,826,p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(total>0?"Page "+pageNo+" of "+total:"Page "+pageNo,W-MX,826,p);
        p.setTextAlign(Paint.Align.LEFT);
        y=first?TOP:70;
    }
    private void fit(Bitmap b,float x,float top,float maxW,float maxH){
        float s=Math.min(maxW/b.getWidth(),maxH/b.getHeight());
        float w=b.getWidth()*s,h=b.getHeight()*s;
        c.drawBitmap(b,null,new RectF(x,top+(maxH-h)/2,x+w,top+(maxH-h)/2+h),p);
    }
    private String clip(String s,float maxW){
        if(s==null)return "";
        if(p.measureText(s)<=maxW)return s;
        int n=p.breakText(s,true,maxW-p.measureText("…"),null);
        return s.substring(0,Math.max(0,n))+"…";
    }
    private void text(String s,float x,float yy,float size,int color,boolean bold){
        p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setTextSize(size);p.setColor(color);
        c.drawText(s,x,yy,p);
    }

    // ---- sections ----
    private void metaCard(){
        text("Measurement report",MX,y+14,26,NAVY,true);y+=24;
        text(d.project,MX,y+14,14,BLUE,true);y+=32;
        String[][] kv={
            {"Prepared for / job",d.jobRef.isEmpty()?"-":d.jobRef},
            {"Prepared by",d.preparedBy.isEmpty()?"-":d.preparedBy},
            {"Business",d.business.isEmpty()?"-":d.business},
            {"Date",d.dateText},
            {"Instrument",d.instrument},
            {"Readings",String.valueOf(d.rows.size())}};
        float rowH=20,h=rowH*3+22;
        p.setStyle(Paint.Style.FILL);p.setColor(LIGHT);c.drawRoundRect(new RectF(MX,y,W-MX,y+h),10,10,p);
        p.setColor(BLUE);c.drawRoundRect(new RectF(MX,y,MX+4,y+h),2,2,p);
        float colW=(W-2*MX)/2f;
        for(int i=0;i<kv.length;i++){
            float x=MX+16+(i%2)*colW,yy=y+22+(i/2)*rowH;
            text(kv[i][0].toUpperCase(Locale.UK),x,yy-8,7,GREY,true);
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(10.5f);p.setColor(INK);
            c.drawText(clip(kv[i][1],colW-24),x,yy+3,p);
        }
        y+=h+26;
    }
    private void tableHeader(){
        p.setStyle(Paint.Style.FILL);p.setColor(NAVY);c.drawRoundRect(new RectF(MX,y,W-MX,y+22),5,5,p);
        float[] x=cols();String[] h={"No.","Date and time","Type","Value","Notes"};
        for(int i=0;i<h.length;i++)text(h[i],x[i],y+15,9,Color.WHITE,true);
        y+=26;
    }
    private float[] cols(){return new float[]{MX+10,MX+42,MX+170,MX+258,MX+350};}
    private void table(){
        text("Readings",MX,y,13,NAVY,true);y+=12;
        if(d.rows.isEmpty()){
            text("No readings saved in this project yet.",MX,y+14,10.5f,GREY,false);y+=30;return;
        }
        tableHeader();
        float[] x=cols();int i=0;
        for(String[] r:d.rows){
            if(y>BOTTOM-24){newPage(false);tableHeader();}
            if(i++%2==0){p.setStyle(Paint.Style.FILL);p.setColor(LIGHT);c.drawRect(MX,y-2,W-MX,y+19,p);}
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(10);p.setColor(INK);
            c.drawText(r[0],x[0],y+12,p);
            c.drawText(clip(r[1],x[2]-x[1]-6),x[1],y+12,p);
            c.drawText(clip(r[2],x[3]-x[2]-6),x[2],y+12,p);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(clip(r[3],x[4]-x[3]-6),x[3],y+12,p);
            p.setTypeface(Typeface.DEFAULT);p.setColor(GREY);
            c.drawText(clip(r[4],W-MX-x[4]-8),x[4],y+12,p);
            y+=21;
        }
        y+=14;
    }
    private void plan(){
        if(d.planLength<=0||d.planWidth<=0)return;
        if(y>BOTTOM-210)newPage(false);
        text("Room plan",MX,y+4,13,NAVY,true);y+=18;
        float boxW=240,boxH=150,s=Math.min(boxW/(float)d.planLength,boxH/(float)d.planWidth);
        float rw=(float)d.planLength*s,rh=(float)d.planWidth*s;
        RectF r=new RectF(MX+20,y+10,MX+20+rw,y+10+rh);
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(228,239,254));c.drawRect(r,p);
        p.setStyle(Paint.Style.STROKE);p.setColor(BLUE);p.setStrokeWidth(2);c.drawRect(r,p);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        text(String.format(Locale.UK,"%.3f m",d.planLength),r.centerX(),r.top-4,9.5f,INK,true);
        c.save();c.rotate(-90,r.left-8,r.centerY());
        text(String.format(Locale.UK,"%.3f m",d.planWidth),r.left-8,r.centerY(),9.5f,INK,true);c.restore();
        text(String.format(Locale.UK,"%.3f m²",d.planLength*d.planWidth),r.centerX(),r.centerY()+5,14,NAVY,true);
        p.setTextAlign(Paint.Align.LEFT);
        float tx=MX+20+rw+36;
        text("Area",tx,y+30,8,GREY,true);
        text(String.format(Locale.UK,"%.3f m²",d.planLength*d.planWidth),tx,y+46,14,INK,true);
        text("Perimeter",tx,y+70,8,GREY,true);
        text(String.format(Locale.UK,"%.3f m",2*(d.planLength+d.planWidth)),tx,y+86,14,INK,true);
        y+=rh+40;
    }
    private void closing(){
        if(y>BOTTOM-60)newPage(false);
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(255,246,225));
        c.drawRoundRect(new RectF(MX,y,W-MX,y+40),8,8,p);
        text("Please note",MX+12,y+16,8.5f,Color.rgb(160,100,0),true);
        text("Verify critical measurements against the physical IR40 display. Laser measurement accuracy is ±(2 mm + 0.05 mm per metre).",MX+12,y+30,8.5f,INK,false);
        y+=52;
    }
    private void photoPage(){
        newPage(false);
        text("Annotated photograph",MX,y+10,16,NAVY,true);y+=24;
        if(!d.photoCaption.isEmpty()){text(d.photoCaption,MX,y+8,10.5f,GREY,false);y+=16;}
        float maxW=W-2*MX,maxH=BOTTOM-y-24;
        float s=Math.min(maxW/d.photo.getWidth(),maxH/d.photo.getHeight());
        float w=d.photo.getWidth()*s,h=d.photo.getHeight()*s;
        float x=MX+(maxW-w)/2;
        p.setStyle(Paint.Style.FILL);p.setColor(RULE);c.drawRect(x-1,y+9,x+w+1,y+h+11,p);
        c.drawBitmap(d.photo,null,new RectF(x,y+10,x+w,y+10+h),p);
        y+=h+24;
        text("Labels show IR40 readings or typed values; they are not measured from the picture.",MX,y,8,GREY,false);
    }
}
