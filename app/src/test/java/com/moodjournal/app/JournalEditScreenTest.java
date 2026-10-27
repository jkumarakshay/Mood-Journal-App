package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import java.time.LocalDate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowPopupMenu;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class JournalEditScreenTest {
    private Context context;
    private JournalHeap heap;
    private JournalStore.Entry original;
    @Before public void reset() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("journal.db");
        for(String prefs:new String[]{"draft","journal_lock","appearance","journal_entry_draft","heap_notices","backup_notices"})
            context.getSharedPreferences(prefs,0).edit().clear().commit();
        context.getSharedPreferences("heap_notices",0).edit().putBoolean("created",true).commit();
        try(JournalStore store=new JournalStore(context)) {
            heap=store.addHeap("Work","Career",JournalHeap.COLORS[1],"A place for work thoughts");
            long id=store.add(3,"Original event","Original thoughts","Original needs",heap.id(),2,
                    new WeatherSnapshot(LocalDate.now().toString(),24,12,"Sunny",WeatherClient.ATTRIBUTION,System.currentTimeMillis(),0));
            original=store.entry(id);
        }
    }

    @Test public void editPrecedesExportAndIsAvailableForBlankJournalsAndEmptyHeaps() {
        try(JournalStore store=new JournalStore(context);ActivityController<MainActivity> c=unlocked()) {
            long blank=store.add(3,"","","");JournalHeap empty=store.addHeap("Empty","",null);
            c.get().findViewById(R.id.checkin_tab).performClick();c.get().findViewById(R.id.journal_tab).performClick();JournalTest.unlockJournal(c.get());
            for(Object id:new Object[]{blank,empty.id()}) {
                PopupMenu menu=openMenu(c.get(),id);
                assertEquals(3,menu.getMenu().size());assertEquals("Edit",menu.getMenu().getItem(0).getTitle());
                assertEquals(R.id.edit_action,menu.getMenu().getItem(0).getItemId());assertTrue(menu.getMenu().getItem(0).isEnabled());
                assertEquals("Export",menu.getMenu().getItem(1).getTitle());assertFalse(menu.getMenu().getItem(1).isEnabled());menu.dismiss();
            }
            edit(c.get(),blank);assertNotNull(dialog().findViewById(R.id.journal_entry_event));
        }
    }

    @Test public void savingJournalChangesUpdatesTheSameEntryAndPreservesWeatherDateAndNewEntryDraft() {
        context.getSharedPreferences("journal_entry_draft",0).edit().putString("event","Unrelated unfinished entry").commit();
        try(ActivityController<MainActivity> c=unlocked();JournalStore store=new JournalStore(context)) {
            AlertDialog edit=edit(c.get(),original.id());
            assertEquals("Original event",field(edit,R.id.journal_entry_event).getText().toString());
            assertEquals(3,((Spinner)edit.findViewById(R.id.journal_entry_mood)).getSelectedItemPosition());
            assertEquals(1,((Spinner)edit.findViewById(R.id.entry_heap)).getSelectedItemPosition());
            assertEquals("Save Changes",edit.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            field(edit,R.id.journal_entry_event).setText("Edited event");field(edit,R.id.journal_entry_thoughts).setText("Edited thoughts");field(edit,R.id.journal_entry_needs).setText("Edited needs");
            ((Spinner)edit.findViewById(R.id.journal_entry_mood)).setSelection(5);((Spinner)edit.findViewById(R.id.entry_heap)).setSelection(0);
            Button oldSave=edit.getButton(AlertDialog.BUTTON_POSITIVE);oldSave.performClick();idle();
            JournalStore.Entry saved=store.entry(original.id());assertEquals(1,store.all().size());assertEquals(original.createdAt(),saved.createdAt());
            assertEquals(5,saved.mood());assertEquals("Edited event",saved.event());assertEquals("Edited thoughts",saved.thoughts());assertEquals("Edited needs",saved.needs());
            assertNull(saved.heapId());assertEquals(original.weather(),saved.weather());assertEquals(original.weatherAnswer(),saved.weatherAnswer());
            assertEquals("Unrelated unfinished entry",context.getSharedPreferences("journal_entry_draft",0).getString("event",""));
            assertFalse(edit.isShowing());oldSave.performClick();idle();assertEquals(saved,store.entry(original.id()));
            assertTrue(text(c.get().getWindow().getDecorView(),"Edited event"));
        }
    }

    @Test public void cancelAndBackgroundDiscardEditsAndRejectOldSaveAfterUnlock() {
        try(ActivityController<MainActivity> c=unlocked();JournalStore store=new JournalStore(context)) {
            AlertDialog edit=edit(c.get(),original.id());EditText input=field(edit,R.id.journal_entry_event);
            input.setText("Cancelled private edit");edit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();
            assertEquals(original,store.entry(original.id()));assertEquals("",input.getText().toString());
            edit=edit(c.get(),original.id());input=field(edit,R.id.journal_entry_event);input.setText("Unsaved private edit");Button stale=edit.getButton(AlertDialog.BUTTON_POSITIVE);
            assertFalse(input.isSaveEnabled());assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,input.getImportantForAutofill());
            assertTrue((edit.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            c.pause().stop();idle();assertFalse(edit.isShowing());assertEquals("",input.getText().toString());
            c.start().resume().visible();assertNotNull(c.get().findViewById(R.id.unlock_submit));JournalTest.unlockJournal(c.get());
            input.setText("Late stale edit");stale.performClick();idle();assertEquals(original,store.entry(original.id()));
            c.recreate();assertNotNull(c.get().findViewById(R.id.unlock_submit));JournalTest.unlockJournal(c.get());
            assertEquals("Original event",field(edit(c.get(),original.id()),R.id.journal_entry_event).getText().toString());
        }
    }

    @Test public void invalidMoodAndStorageFailureLeaveOriginalUntouchedAndPermitRetry() {
        try(ActivityController<MainActivity> c=unlocked();JournalStore store=new JournalStore(context)) {
            AlertDialog edit=edit(c.get(),original.id());((Spinner)edit.findViewById(R.id.journal_entry_mood)).setSelection(0);
            edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(edit.isShowing());assertEquals(original,store.entry(original.id()));
            ((Spinner)edit.findViewById(R.id.journal_entry_mood)).setSelection(4);field(edit,R.id.journal_entry_event).setText("Retry this edit");
            store.getWritableDatabase().execSQL("CREATE TRIGGER reject_edit BEFORE UPDATE ON entries BEGIN SELECT RAISE(ABORT,'test storage error'); END");
            edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(edit.isShowing());assertEquals(original,store.entry(original.id()));
            assertTrue(text(edit.getWindow().getDecorView(),"Couldn’t save your changes"));assertEquals("Retry this edit",field(edit,R.id.journal_entry_event).getText().toString());
            store.getWritableDatabase().execSQL("DROP TRIGGER reject_edit");edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            assertEquals("Retry this edit",store.entry(original.id()).event());assertEquals(1,store.all().size());
        }
    }

    @Test public void heapEditPrefillsAllFieldsAndUpdatesCardsFiltersAndMembership() {
        try(ActivityController<MainActivity> c=unlocked();JournalStore store=new JournalStore(context)) {
            AlertDialog edit=edit(c.get(),heap.id());assertEquals("Work",field(edit,R.id.heap_name).getText().toString());
            assertEquals("Career",field(edit,R.id.heap_category).getText().toString());assertEquals(heap.description(),field(edit,R.id.heap_description).getText().toString());
            assertEquals(heap.color(),((HeapColorPicker)edit.findViewById(R.id.heap_color)).getColor());
            field(edit,R.id.heap_name).setText("Learning");field(edit,R.id.heap_category).setText("Growth");field(edit,R.id.heap_description).setText("A new purpose\nA little room to grow");
            ((HeapColorPicker)edit.findViewById(R.id.heap_color)).setColor(0xFF123456);edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            JournalHeap changed=store.heap(heap.id());assertEquals("Learning",changed.name());assertEquals("Growth",changed.category());assertEquals("A new purpose\nA little room to grow",changed.description());
            assertEquals(Integer.valueOf(0xFF123456),changed.color());assertEquals(heap.id(),store.entry(original.id()).heapId());assertEquals(1,store.heaps().size());
            View card=findTag(c.get().getWindow().getDecorView(),original.id());
            assertEquals(0xFF123456,((android.graphics.drawable.GradientDrawable)card.getBackground()).getColor().getDefaultColor());
            Spinner filter=c.get().findViewById(R.id.journal_category);assertEquals("Category: Growth",filter.getItemAtPosition(2));
            assertTrue(text(c.get().getWindow().getDecorView(),"A new purpose"));
        }
    }

    @Test public void descriptionsAreOptionalOnCreateAndEditableInsideAHeap() {
        try(ActivityController<MainActivity> c=unlocked();JournalStore store=new JournalStore(context)) {
            c.get().findViewById(R.id.journal_add).performClick();Shadows.shadowOf(dialog()).clickOnItem(1);idle();
            AlertDialog create=dialog();assertEquals("",field(create,R.id.heap_description).getText().toString());
            field(create,R.id.heap_name).setText("Family");field(create,R.id.heap_description).setText("People and connection");create.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            JournalHeap added=store.heaps().stream().filter(h->h.name().equals("Family")).findFirst().get();assertEquals("People and connection",added.description());
            c.get().findViewById(R.id.export_more).performClick();ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(R.id.edit_action,0);
            AlertDialog edit=dialog();field(edit,R.id.heap_name).setText(" ");edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(edit.isShowing());assertEquals(added,store.heap(added.id()));
            field(edit,R.id.heap_name).setText("Family");field(edit,R.id.heap_description).setText("");
            ((HeapColorPicker)edit.findViewById(R.id.heap_color)).setColor(null);edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            assertEquals("",store.heap(added.id()).description());assertNull(store.heap(added.id()).color());
        }
    }

    @Test public void editingFromReadDialogClosesTheReadWindowAndLockClosesTheEditor() {
        try(ActivityController<MainActivity> c=unlocked()) {
            View card=findTag(c.get().getWindow().getDecorView(),original.id());findButton(card,"Read this entry  →").performClick();AlertDialog read=dialog();
            read.findViewById(R.id.export_more).performClick();ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(R.id.edit_action,0);AlertDialog edit=dialog();
            assertNotSame(read,edit);assertFalse(read.isShowing());assertTrue(edit.isShowing());c.pause().stop();idle();assertFalse(edit.isShowing());assertFalse(read.isShowing());
        }
    }

    @Test public void dismissedAndBackgroundMenusCannotOpenAnEditorInALaterSession() {
        try(ActivityController<MainActivity> c=unlocked()) {
            PopupMenu popup=openMenu(c.get(),original.id());android.widget.PopupMenu.OnMenuItemClickListener stale=Shadows.shadowOf(popup).getOnMenuItemClickListener();
            android.view.MenuItem edit=popup.getMenu().findItem(R.id.edit_action);popup.dismiss();idle();assertFalse(stale.onMenuItemClick(edit));
            popup=openMenu(c.get(),heap.id());stale=Shadows.shadowOf(popup).getOnMenuItemClickListener();edit=popup.getMenu().findItem(R.id.edit_action);
            c.pause().stop();idle();c.start().resume().visible();JournalTest.unlockJournal(c.get());assertFalse(stale.onMenuItemClick(edit));
            assertNotNull(c.get().findViewById(R.id.journal_add));
        }
    }

    private ActivityController<MainActivity> unlocked() { ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();c.get().findViewById(R.id.journal_tab).performClick();JournalTest.unlockJournal(c.get());return c; }
    private PopupMenu openMenu(MainActivity activity,Object id) {
        View view=findTag(activity.getWindow().getDecorView(),id);assertNotNull("Missing card "+id,view);
        while(view.findViewById(R.id.export_more)==null)view=(View)view.getParent();view.findViewById(R.id.export_more).performClick();return ShadowPopupMenu.getLatestPopupMenu();
    }
    private AlertDialog edit(MainActivity activity,Object id) { openMenu(activity,id).getMenu().performIdentifierAction(R.id.edit_action,0);return dialog(); }
    private AlertDialog dialog() { return ShadowAlertDialog.getLatestAlertDialog(); }
    private EditText field(AlertDialog dialog,int id) { return dialog.findViewById(id); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private View findTag(View root,Object tag) { if(tag.equals(root.getTag()))return root;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View found=findTag(group.getChildAt(i),tag);if(found!=null)return found;}return null; }
    private Button findButton(View root,String title) { if(root instanceof Button button&&title.contentEquals(button.getText()))return button;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){Button found=findButton(group.getChildAt(i),title);if(found!=null)return found;}return null; }
    private boolean text(View root,String value) { if(root instanceof TextView view&&view.getText().toString().contains(value))return true;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)if(text(group.getChildAt(i),value))return true;return false; }
}
