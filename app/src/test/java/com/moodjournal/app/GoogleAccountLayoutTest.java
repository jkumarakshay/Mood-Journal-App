package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class,qualifiers="w411dp-h900dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GoogleAccountLayoutTest {
    @Test public void accountAndResetScreensFitBothThemes(){check(411,900,1,true);}
    @Test public void accountAndResetScreensSupportSmallScreensWithLargeText(){check(320,568,1.6f,false);}
    private void check(int width,int height,float scale,boolean preview){
        RuntimeEnvironment.setQualifiers("w"+width+"dp-h"+height+"dp-port-mdpi");RuntimeEnvironment.setFontScale(scale);
        Context context=RuntimeEnvironment.getApplication();Bitmap sheet=preview?Bitmap.createBitmap(width*3,(height+32)*2,Bitmap.Config.ARGB_8888):null;
        Canvas canvas=sheet==null?null:new Canvas(sheet);Paint caption=new Paint();caption.setColor(Color.BLACK);caption.setTextSize(15);
        for(int theme=0;theme<2;theme++)for(int mode=0;mode<3;mode++){
            GoogleAccountScreenTest fixture=new GoogleAccountScreenTest();fixture.reset();Appearance.setDark(context,theme==1);
            try{
                if(mode>0)fixture.setupOwner();
                try(GoogleAccountScreenTest.Session s=fixture.new Session()){
                    if(mode>0)s.recover();if(mode==2)s.signIn();
                    TourLayoutTest.layout(s.a,width,height);
                    if(mode!=2){Button google=s.a.findViewById(R.id.google_sign_in);assertTrue(google.getHeight()>=60);noEllipsis(google);
                        View ancestor=google;while(!(ancestor instanceof ScrollView))ancestor=(View)ancestor.getParent();((ScrollView)ancestor).scrollTo(0,google.getTop());
                        Rect visible=new Rect();assertTrue(google.getGlobalVisibleRect(visible));assertEquals(google.getHeight(),visible.height());((ScrollView)ancestor).scrollTo(0,0);
                    }
                    AlertDialog popup=GoogleAccountScreenTest.showing();View decor=null;
                    if(mode==2){assertNotNull(popup);decor=popup.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(width-32,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height-32,View.MeasureSpec.AT_MOST));decor.layout(0,0,width-32,decor.getMeasuredHeight());assertTrue(decor.getHeight()<=height-32);
                        for(int id:new int[]{AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE}){Button button=popup.getButton(id);assertTrue(button.getHeight()>=48);assertTrue(button.getBottom()<=((View)button.getParent()).getHeight());noEllipsis(button);}
                    }
                    if(preview){canvas.save();canvas.translate(mode*width,theme*(height+32));canvas.clipRect(0,0,width,height+32);canvas.drawColor(Color.rgb(237,232,239));
                        canvas.drawText(new String[]{"Google setup","PIN recovery","New PIN"}[mode]+(theme==0?" · Light":" · Dark"),12,22,caption);canvas.translate(0,32);s.a.findViewById(android.R.id.content).draw(canvas);
                        if(decor!=null){Paint dim=new Paint();dim.setColor(0x66000000);canvas.drawRect(0,0,width,height,dim);canvas.translate(16,(height-decor.getHeight())/2f);decor.draw(canvas);}canvas.restore();
                    }
                    s.c.pause().stop();if(popup!=null)assertFalse(popup.isShowing());
                }
            }catch(Exception e){throw new AssertionError(e);}
        }
        if(preview)try{File file=new File("build/google-account-preview.png");file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){assertTrue(sheet.compress(Bitmap.CompressFormat.PNG,100,out));}}catch(Exception e){throw new AssertionError(e);}
    }
    private void noEllipsis(Button button){assertNotNull(button.getLayout());for(int i=0;i<button.getLayout().getLineCount();i++)assertEquals(button.getText().toString(),0,button.getLayout().getEllipsisCount(i));}
}
