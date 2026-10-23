package com.example.liefantidia2;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.gms.tasks.Tasks;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import android.content.ContentValues;
import android.provider.MediaStore;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class CookingUiTest {
    @BeforeClass public static void useOnlyLocalTestServices() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        for (FirebaseApp app : FirebaseApp.getApps(context)) app.delete();
        FirebaseApp.initializeApp(context, new FirebaseOptions.Builder()
            .setApplicationId("1:123456789:android:test")
            .setApiKey("AIzaSyTestOnlyNeverUseInProduction1234567")
            .setProjectId("demo-cookai").build());
        FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099);
        FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080);
        FirebaseApp restore = FirebaseApp.initializeApp(context, FirebaseApp.getInstance().getOptions(), "account-restore");
        FirebaseAuth.getInstance(restore).useEmulator("10.0.2.2", 9099);
        FirebaseFirestore.getInstance(restore).useEmulator("10.0.2.2", 8080);
        Tasks.await(FirebaseAuth.getInstance().signInAnonymously(), 30, TimeUnit.SECONDS);
    }
    @Test public void saveDefaultsAndReloadAfterActivityRecreation() throws Exception {
        try (ActivityScenario<SettingsActivity> screen = ActivityScenario.launch(SettingsActivity.class)) {
            onView(withId(R.id.edit_preset_allergies)).perform(scrollTo(), replaceText("卵、乳"), closeSoftKeyboard());
            onView(withId(R.id.edit_preset_seasonings)).perform(scrollTo(), replaceText("塩、しょうゆ"), closeSoftKeyboard());
            onView(withId(R.id.edit_preset_methods)).perform(scrollTo(), replaceText("電子レンジのみ"), closeSoftKeyboard());
            onView(withId(R.id.button_save_cooking)).perform(scrollTo(), click());
            onView(withId(R.id.text_preset_status)).check(matches(withText("保存しました。次のレシピ生成から反映します。")));
            screen.recreate();
            onView(withId(R.id.edit_preset_allergies)).check(matches(withText("卵、乳")));
            onView(withId(R.id.edit_preset_methods)).check(matches(withText("電子レンジのみ")));
            onView(withId(R.id.edit_preset_allergies)).perform(scrollTo());
            screenshot("settings-presets");
        }
        CookingPreferences saved = new CookingPreferencesStore(ApplicationProvider.getApplicationContext())
                .load(FirebaseAuth.getInstance().getCurrentUser().getUid());
        assertEquals("卵、乳", saved.allergies);
        assertTrue(saved.prompt().contains("電子レンジのみ"));
    }
    @Test public void cookingDefaultsAreIsolatedAndCopiedOnlyWhenTargetHasNone() {
        CookingPreferencesStore store = new CookingPreferencesStore(ApplicationProvider.getApplicationContext());
        store.delete("test-source"); store.delete("test-target");
        assertTrue(store.save("test-source", new CookingPreferences("卵", "塩", "蒸す")));
        assertEquals("", store.load("test-target").allergies);
        assertTrue(store.copyIfMissing("test-source", "test-target"));
        assertEquals("卵", store.load("test-target").allergies);
        assertTrue(store.save("test-target", new CookingPreferences("乳", "", "")));
        assertTrue(store.copyIfMissing("test-source", "test-target"));
        assertEquals("乳", store.load("test-target").allergies);
        store.delete("test-source"); store.delete("test-target");
    }
    @Test public void registeredAccountCanLogInAgainAfterLogout() throws Exception {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) auth.signOut();
        Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS);
        String email = "relogin-" + System.nanoTime() + "@example.com";
        AccountController controller = AccountController.get(ApplicationProvider.getApplicationContext());
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> controller.register(email, "TestPassword123!"));
            waitForAccount(controller);
            assertFalse(auth.getCurrentUser().isAnonymous());
            String uid = auth.getCurrentUser().getUid();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::logout);
            assertNull(auth.getCurrentUser());
            // No guest data exists, so the destination can be signed in directly.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> controller.login(email, "TestPassword123!"));
            waitForAccount(controller);
            assertNotNull(auth.getCurrentUser());
            assertEquals(uid, auth.getCurrentUser().getUid());
        } finally {
            auth.signOut();
            Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS);
        }
    }
    @Test public void guestRecoveryCodeRestoresSameHistoryUid() throws Exception {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.signOut();
        Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS);
        String uid = auth.getCurrentUser().getUid();
        AccountController controller = AccountController.get(ApplicationProvider.getApplicationContext());
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::enableGuestRecovery);
            waitForAccount(controller);
            assertNotNull(auth.getCurrentUser());
            assertEquals(uid, auth.getCurrentUser().getUid());
            assertFalse(auth.getCurrentUser().isAnonymous());
            String code = GuestRecovery.load(ApplicationProvider.getApplicationContext(), uid);
            assertTrue(GuestRecovery.valid(code));
            try (ActivityScenario<SettingsActivity> first = ActivityScenario.launch(SettingsActivity.class)) {
                onView(withText("ゲストの復元コード")).check(matches(isDisplayed()));
                onView(withText("閉じる")).perform(click());
            }
            try (ActivityScenario<SettingsActivity> again = ActivityScenario.launch(SettingsActivity.class)) {
                onView(withText("ゲストの復元コード")).check(doesNotExist());
                onView(withId(R.id.button_show_recovery)).check(matches(isDisplayed()));
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::logout);
            assertNull(auth.getCurrentUser());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> controller.restoreGuest(GuestRecovery.display(code)));
            waitForAccount(controller);
            assertNotNull(auth.getCurrentUser());
            assertEquals(uid, auth.getCurrentUser().getUid());
        } finally {
            auth.signOut();
            Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS);
        }
    }
    @Test public void historySearchFiltersAndClearsAfterLoading() throws Exception {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        com.google.firebase.firestore.DocumentReference document = FirebaseFirestore.getInstance()
            .document("artifacts/liefantidia2/users/" + uid + "/history/search-demo");
        Map<String, Object> recipe = new HashMap<>();
        recipe.put("recipeTitle", "検索確認カレー");
        recipe.put("ingredientsWithUsage", "鶏肉、玉ねぎ");
        recipe.put("allConstraints", "簡単");
        recipe.put("recipeContent", "鶏肉を煮る");
        recipe.put("timestamp", System.currentTimeMillis());
        Tasks.await(document.set(recipe), 30, TimeUnit.SECONDS);
        try (ActivityScenario<HistoryActivity> screen = ActivityScenario.launch(HistoryActivity.class)) {
            long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                try {
                    onView(withText("検索確認カレー")).check(matches(isDisplayed()));
                    break;
                } catch (androidx.test.espresso.NoMatchingViewException | AssertionError error) {
                    if (System.currentTimeMillis() >= deadline) throw error;
                    Thread.sleep(100);
                }
            }
            onView(withId(R.id.edit_history_search)).perform(replaceText("鶏肉"), closeSoftKeyboard());
            onView(withText("検索確認カレー")).check(matches(isDisplayed()));
            onView(withId(R.id.edit_history_search)).perform(replaceText("存在しない"), closeSoftKeyboard());
            onView(withText("検索に一致する履歴がありません。")).check(matches(isDisplayed()));
            onView(withId(R.id.button_clear_search)).perform(click());
            onView(withText("検索確認カレー")).check(matches(isDisplayed()));
        } finally {
            Tasks.await(document.delete(), 30, TimeUnit.SECONDS);
        }
    }
    private void waitForAccount(AccountController controller) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (!controller.busy()) return;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        fail("Account operation timed out");
    }
    @Test public void mainHasCompactSelectorsAndLongRecipeRemainsReadable() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.edit_text_ingredients)).check(matches(isDisplayed()));
            onView(withText("予算")).check(doesNotExist());
            onView(withText("自由指定")).check(doesNotExist());
            onView(withText("いつもの調理設定")).check(doesNotExist());
            screenshot("main-top");
            onView(withId(R.id.spinner_difficulty)).perform(scrollTo(), click());
            onView(withText("初心者 (簡単)")).check(matches(isDisplayed()));
            androidx.test.espresso.Espresso.pressBack();
            screen.onActivity(activity -> ((android.widget.TextView) activity.findViewById(R.id.text_view_recipe_output))
                .setText("材料と手順\n" + "食材を切り、しっかり加熱します。\n".repeat(100) + "レシピの末尾"));
            onView(withId(R.id.button_copy_recipe)).perform(scrollTo()).check(matches(isDisplayed()));
            screenshot("main-long-recipe");
        }
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        // Public emulator media survives the app uninstall performed by Gradle test cleanup.
        Context context = ApplicationProvider.getApplicationContext();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CookAiTest/");
        android.net.Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull(output);
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
}
