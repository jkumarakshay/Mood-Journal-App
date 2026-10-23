package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Color;
import androidx.core.graphics.ColorUtils;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class HeapCustomColorTest {
    private Context context;
    @Before public void reset(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("journal.db");context.getSharedPreferences("appearance",0).edit().clear().commit();}
    @Test public void customBlackWhiteAndRgbSurviveDatabaseReopen() {
        try(JournalStore store=new JournalStore(context)) {
            for(int color:new int[]{Color.BLACK,Color.WHITE,0xFF123456,0xFF705180})store.addHeap(Integer.toHexString(color),"Colors",color);
            assertThrows(IllegalArgumentException.class,()->store.addHeap("Transparent","",0x80123456));
        }
        try(JournalStore store=new JournalStore(context)) {
            assertEquals(4,store.heaps().size());for(JournalHeap heap:store.heaps())assertEquals(heap.name(),Integer.toHexString(heap.color()));
        }
    }
    @Test public void encryptedBackupsKeepExactRgbWithAndWithoutWeather() throws Exception {
        JournalHeap heap=new JournalHeap(java.util.UUID.randomUUID().toString(),"Custom","Life",0xFF123456);
        char[] password="a long passphrase".toCharArray();
        BackupArchive.Entry plain=new BackupArchive.Entry(1234,4,"Thoughts","","",heap.id());
        WeatherSnapshot weather=new WeatherSnapshot("2026-10-23",25,15,"Sunny","Test",1792771200000L,0);
        BackupArchive.Entry withWeather=new BackupArchive.Entry(1234,4,"Thoughts","","",heap.id(),0,weather);
        for(BackupArchive.Entry entry:List.of(plain,withWeather)) {
            BackupArchive.Snapshot expected=new BackupArchive.Snapshot(List.of(heap),List.of(entry));
            BackupArchive.Snapshot decoded=BackupArchive.decryptSnapshot(BackupArchive.encrypt(expected,password),password);assertEquals(expected,decoded);
            try(JournalStore store=new JournalStore(context)){assertEquals(1,store.restoreSnapshot(decoded));assertEquals(Integer.valueOf(0xFF123456),store.heaps().get(0).color());}
            context.deleteDatabase("journal.db");
        }
    }
    @Test public void actualCardsKeepCustomRgbAndReadableTextWhileDefaultTracksTheme() {
        long blueId,whiteId,looseId;
        try(JournalStore store=new JournalStore(context)) {
            JournalHeap blue=store.addHeap("Blue","Life",0xFF705180),white=store.addHeap("White","Life",Color.WHITE);
            blueId=store.add(4,"Blue thoughts","","",blue.id());whiteId=store.add(3,"White thoughts","","",white.id());looseId=store.add(3,"Loose thoughts","","");
        }
        context.getSharedPreferences("journal_lock",0).edit().clear().commit();
        for(boolean dark:new boolean[]{false,true}) {
            Appearance.setDark(context,dark);
            try(org.robolectric.android.controller.ActivityController<MainActivity> c=org.robolectric.Robolectric.buildActivity(MainActivity.class).setup()) {
                c.get().findViewById(R.id.journal_tab).performClick();JournalTest.unlockJournal(c.get());
                assertCard(c.get(),blueId,0xFF705180,"Blue thoughts");assertCard(c.get(),whiteId,Color.WHITE,"White thoughts");
                assertCard(c.get(),looseId,Appearance.color(context,Color.WHITE),"Loose thoughts");
            }
        }
    }
    private void assertCard(MainActivity a,long id,int rgb,String words) {
        android.view.View card=tag(a.getWindow().getDecorView(),id);assertNotNull(card);
        assertEquals(rgb,((android.graphics.drawable.GradientDrawable)card.getBackground()).getColor().getDefaultColor());
        android.widget.TextView text=text(card,words);assertNotNull(text);assertTrue(ColorUtils.calculateContrast(text.getCurrentTextColor(),rgb)>=4.5);
    }
    private android.view.View tag(android.view.View view,Object expected) {
        if(expected.equals(view.getTag()))return view;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.view.View result=tag(group.getChildAt(i),expected);if(result!=null)return result;}return null;
    }
    private android.widget.TextView text(android.view.View view,String words) {
        if(view instanceof android.widget.TextView t&&t.getText().toString().equals(words))return t;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.widget.TextView result=text(group.getChildAt(i),words);if(result!=null)return result;}return null;
    }
    @Test public void customSurfacesStayExactAndCardTextHasReadableContrastInBothThemes() {
        for(boolean dark:new boolean[]{false,true}) {
            Appearance.setDark(context,dark);
            for(int red=0;red<=255;red+=51)for(int green=0;green<=255;green+=51)for(int blue=0;blue<=255;blue+=51) {
                int rgb=Color.rgb(red,green,blue);int surface=Appearance.heapSurface(context,rgb);
                if(!Appearance.isPresetHeapColor(rgb))assertEquals(rgb,surface);
                assertTrue(Integer.toHexString(rgb),ColorUtils.calculateContrast(Appearance.heapInk(context,rgb),surface)>=4.5);
            }
            assertEquals(0xFF705180,Appearance.heapSurface(context,0xFF705180));
        }
    }
}
