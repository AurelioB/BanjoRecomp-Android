package io.github.banjorecomp;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

public class BanjoSDLActivity extends SDLActivity {
    private static final String TAG = "BanjoSDLActivity";
    private static final int REQUEST_INSTALL_MODS = 1001;
    private static final int REQUEST_SELECT_ROM = 1002;
    private static final int REQUEST_SELECT_GPU_DRIVER = 1003;
    private static final int REQUEST_IMPORT_SAVE = 1004;
    private static final int REQUEST_EXPORT_SAVE = 1005;
    private static final int REQUEST_SAVE_FOLDER = 1006;
    private static final String SAVE_FOLDER_PREFS = "save-folder";
    private static final String SAVE_FOLDER_URI = "tree-uri";
    private static final String SAVE_DOCUMENT_NAME = "banjo-kazooie.bin";
    private static final String RUNTIME_SAVE_NAME = "bk.n64.us.1.0.bin";
    private static final long BANJO_SAVE_SIZE = 0x800L;
    private static final String PROGRAM_ASSET_STAMP_FILE = ".program-assets-stamp";
    private static final String GPU_DRIVER_ROOT_DIR = "gpu-drivers";
    private static final String GPU_DRIVER_TMP_DIR = "tmp";
    private static final String GPU_DRIVER_IMPORTS_DIR = "imports";
    private static final String[] GPU_DRIVER_MIME_TYPES = new String[] {
        "application/zip",
        "application/octet-stream",
        "application/x-zip-compressed"
    };
    private static final String[] GPU_DRIVER_SONAME_ORDER = new String[] {
        "libvulkan_freedreno.so",
        "vulkan.freedreno.so",
        "libvulkan.so"
    };
    private static final String GPU_DRIVER_REQUIRED_ABI = "arm64-v8a";
    private static final int GPU_DRIVER_ZIP_MAX_ENTRIES = 512;
    private static final long GPU_DRIVER_ZIP_MAX_ENTRY_BYTES = 256L * 1024L * 1024L;
    private static final long GPU_DRIVER_ZIP_MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    private static final String EXTRA_FORCE_SYSTEM_DRIVER = "banjo_force_system_driver";
    private static final String EXTRA_DUAL_SCREEN_PREVIEW = "dualscreen_preview";
    private static final String EXTRA_DUAL_SCREEN_PREVIEW_CLEAR = "dualscreen_preview_clear";
    private static final String EXTRA_DUAL_SCREEN_PREVIEW_MAP = "dualscreen_preview_map";
    private static final String EXTRA_VULKAN_SMOKE_PROBE = "vulkan_smoke_probe";
    private static final String EXTRA_VULKAN_SMOKE_PROBE_MODE = "vulkan_smoke_probe_mode";
    private static BanjoSDLActivity currentActivity;
    private static int lastLoggedDualScreenMapId = Integer.MIN_VALUE;
    private static Boolean lastPostedDualScreenGameplayActive;
    private static DualScreenStats lastPostedDualScreenStats;

    public static native void nativeSetAndroidSurfaceReady(boolean ready);
    public static native void nativeSetAppAudioActive(boolean active);

    private boolean activityResumed;
    private boolean windowFocused;
    private boolean appAudioActive;
    private DualScreenStatsManager dualScreenStatsManager;
    private final ExecutorService saveIoExecutor = Executors.newSingleThreadExecutor();
    private String startupSaveStatus;
    private String startupSaveLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        currentActivity = this;
        lastPostedDualScreenGameplayActive = null;
        lastPostedDualScreenStats = null;
        File programDir = new File(getFilesDir(), "program");
        File appDataDir = new File(getFilesDir(), "data");
        File gpuDriverRoot = new File(getFilesDir(), GPU_DRIVER_ROOT_DIR);
        File gpuDriverTmp = new File(gpuDriverRoot, GPU_DRIVER_TMP_DIR);

        hydrateInternalSaveFromSelectedFolder(appDataDir);
        hydrateInternalGpuDriverFromExternalFolder(gpuDriverRoot);

        try {
            extractProgramAssetsIfNeeded(programDir);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy program assets", e);
        }

        super.onCreate(savedInstanceState);
        applyImmersiveFullscreen();
        dualScreenStatsManager = new DualScreenStatsManager(this);
        dualScreenStatsManager.start();

        nativeSetenv("APP_PROGRAM_PATH", programDir.getAbsolutePath());
        nativeSetenv("APP_FOLDER_PATH", appDataDir.getAbsolutePath());
        setupGpuDriverEnvironment(gpuDriverRoot, gpuDriverTmp, getIntent());
        File bundledDevRom = new File(programDir, "dev-roms/baserom.us.v10.z64");
        File cachedRom = findLatestCachedRom();
        if (cachedRom != null && dualScreenStatsManager != null) {
            dualScreenStatsManager.loadThemeFromRom(cachedRom);
        }
        if (BuildConfig.BANJO_BUNDLE_DEV_ROMS && bundledDevRom.isFile()) {
            if (dualScreenStatsManager != null) {
                dualScreenStatsManager.loadThemeFromRom(bundledDevRom);
            }
            nativeSetenv("RECOMP_AUTO_ROM_PATH", bundledDevRom.getAbsolutePath());
            Log.i(TAG, "RECOMP_AUTO_ROM_PATH=" + bundledDevRom.getAbsolutePath());
        }
        Log.i(TAG, "APP_PROGRAM_PATH=" + programDir.getAbsolutePath());
        Log.i(TAG, "APP_FOLDER_PATH=" + appDataDir.getAbsolutePath());
        if (startupSaveStatus != null) nativeOnSaveOperation(startupSaveStatus, startupSaveLocation);
        handleVulkanSmokeProbeIntent(getIntent());
        handleDualScreenPreviewIntent(getIntent());
    }

    private void handleVulkanSmokeProbeIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        if (intent.getBooleanExtra(EXTRA_VULKAN_SMOKE_PROBE, false)) {
            nativeSetenv("BANJO_ANDROID_VULKAN_SMOKE_PROBE", "1");
            Log.i(TAG, "BanjoVkSmoke enabled by intent extra");
        }

        String mode = intent.getStringExtra(EXTRA_VULKAN_SMOKE_PROBE_MODE);
        if (mode != null && !mode.isEmpty()) {
            nativeSetenv("BANJO_ANDROID_VULKAN_SMOKE_PROBE_MODE", mode);
            nativeSetenv("BANJO_ANDROID_VULKAN_SMOKE_PROBE", "1");
            Log.i(TAG, "BanjoVkSmoke mode from intent extra=" + mode);
        }
    }

    private void setupGpuDriverEnvironment(File gpuDriverRoot, File gpuDriverTmp, Intent intent) {
        ensureDirectory(gpuDriverRoot, "GPU driver root");
        ensureDirectory(gpuDriverTmp, "GPU driver tmp");

        String nativeLibraryDir = getApplicationInfo().nativeLibraryDir;
        boolean forceSystemDriver = intent != null && intent.getBooleanExtra(EXTRA_FORCE_SYSTEM_DRIVER, false);
        nativeSetenv("BANJO_GPU_DRIVER_ROOT", gpuDriverRoot.getAbsolutePath());
        nativeSetenv("BANJO_GPU_DRIVER_TMP", gpuDriverTmp.getAbsolutePath());
        nativeSetenv("BANJO_NATIVE_LIBRARY_DIR", nativeLibraryDir);
        nativeSetenv("BANJO_FORCE_SYSTEM_DRIVER", forceSystemDriver ? "1" : "0");

        Log.i(TAG, "BanjoGpuDriver BANJO_GPU_DRIVER_ROOT=" + gpuDriverRoot.getAbsolutePath());
        Log.i(TAG, "BanjoGpuDriver BANJO_GPU_DRIVER_TMP=" + gpuDriverTmp.getAbsolutePath());
        Log.i(TAG, "BanjoGpuDriver BANJO_NATIVE_LIBRARY_DIR=" + nativeLibraryDir);
        Log.i(TAG, "BanjoGpuDriver BANJO_FORCE_SYSTEM_DRIVER=" + (forceSystemDriver ? "1" : "0"));
    }

    private void ensureDirectory(File directory, String label) {
        if (directory.isDirectory()) {
            return;
        }

        if (!directory.mkdirs() && !directory.isDirectory()) {
            Log.e(TAG, "Failed to create " + label + " directory " + directory.getAbsolutePath());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_FORCE_SYSTEM_DRIVER)) {
            boolean forceSystemDriver = intent.getBooleanExtra(EXTRA_FORCE_SYSTEM_DRIVER, false);
            nativeSetenv("BANJO_FORCE_SYSTEM_DRIVER", forceSystemDriver ? "1" : "0");
            Log.i(TAG, "BanjoGpuDriver BANJO_FORCE_SYSTEM_DRIVER updated from intent="
                    + (forceSystemDriver ? "1" : "0") + "; restart required if Vulkan is already initialized");
        }
        handleVulkanSmokeProbeIntent(intent);
        handleDualScreenPreviewIntent(intent);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveFullscreen();
        activityResumed = true;
        updateAppAudioActive();
        updateDualScreenForeground();
    }

    @Override
    protected void onPause() {
        synchronizeSaveFolder();
        activityResumed = false;
        updateAppAudioActive();
        updateDualScreenForeground();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        windowFocused = hasFocus;
        if (hasFocus) {
            applyImmersiveFullscreen();
        }
        updateAppAudioActive();
        updateDualScreenForeground();
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleDualScreenDebugKeyEvent(event.getKeyCode(), event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleDualScreenDebugKeyEvent(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public static boolean handleDualScreenDebugKeyEvent(int keyCode, KeyEvent event) {
        BanjoSDLActivity activity = currentActivity;
        if (!BuildConfig.BANJO_DUAL_SCREEN_DEBUG || activity == null || activity.dualScreenStatsManager == null
                || event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return activity.dualScreenStatsManager.debugStepPreviewArea(1);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return activity.dualScreenStatsManager.debugStepPreviewArea(-1);
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (dualScreenStatsManager != null) {
            dualScreenStatsManager.stop();
            dualScreenStatsManager = null;
        }
        if (currentActivity == this) {
            currentActivity = null;
        }
        saveIoExecutor.shutdown();
        super.onDestroy();
    }

    private void applyImmersiveFullscreen() {
        Window window = getWindow();
        if (window == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        View decorView = window.getDecorView();
        if (decorView != null) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void updateAppAudioActive() {
        boolean active = activityResumed && windowFocused;
        if (active != appAudioActive) {
            appAudioActive = active;
            nativeSetAppAudioActive(active);
        }
    }

    private void updateDualScreenForeground() {
        if (dualScreenStatsManager != null) {
            dualScreenStatsManager.setAppForeground(activityResumed && windowFocused);
        }
    }

    private void handleDualScreenPreviewIntent(Intent intent) {
        if (!BuildConfig.BANJO_DUAL_SCREEN_DEBUG || intent == null || dualScreenStatsManager == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_DUAL_SCREEN_PREVIEW_CLEAR, false)) {
            dualScreenStatsManager.clearPreviewMode();
            return;
        }
        if (!intent.getBooleanExtra(EXTRA_DUAL_SCREEN_PREVIEW, false)
                && !intent.hasExtra(EXTRA_DUAL_SCREEN_PREVIEW_MAP)) {
            return;
        }
        int mapId = parsePreviewMapId(intent.getExtras() == null
                        ? null
                        : intent.getExtras().get(EXTRA_DUAL_SCREEN_PREVIEW_MAP),
                0x01);
        dualScreenStatsManager.previewStatsBackground(mapId);
    }

    private int parsePreviewMapId(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            return fallback;
        }
        String trimmed = ((String) value).trim();
        try {
            return Integer.decode(trimmed);
        } catch (NumberFormatException e) {
            String lower = trimmed.toLowerCase(java.util.Locale.US);
            switch (lower) {
                case "spiral":
                case "spiral_mountain":
                case "sm":
                    return 0x01;
                case "mumbo":
                case "mumbos_mountain":
                case "mm":
                    return 0x02;
                case "treasure_trove":
                case "treasure_trove_cove":
                case "ttc":
                    return 0x07;
                case "gruntys_lair":
                case "grunty_lair":
                case "lair":
                case "gl":
                    return 0x69;
                default:
                    Log.w(TAG, "Unknown dual-screen preview map '" + trimmed + "', using 0x"
                            + Integer.toHexString(fallback));
                    return fallback;
            }
        }
    }

    public void openModFilePicker() {
        runOnUiThread(() -> {
            if (dualScreenStatsManager != null) {
                dualScreenStatsManager.hideForExternalActivity();
            }

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
            });

            try {
                startActivityForResult(intent, REQUEST_INSTALL_MODS);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "No Android document picker is available for installing mods", e);
            }
        });
    }

    public void openRomFilePicker() {
        runOnUiThread(() -> {
            if (dualScreenStatsManager != null) {
                dualScreenStatsManager.hideForExternalActivity();
            }

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/octet-stream",
                "application/x-n64-rom",
                "application/vnd.nintendo.snes.rom"
            });

            try {
                startActivityForResult(intent, REQUEST_SELECT_ROM);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "No Android document picker is available for loading ROMs", e);
                nativeOnRomSelected(null);
            }
        });
    }

    public void openGpuDriverFilePicker() {
        runOnUiThread(() -> {
            if (dualScreenStatsManager != null) {
                dualScreenStatsManager.hideForExternalActivity();
            }

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, GPU_DRIVER_MIME_TYPES);

            try {
                startActivityForResult(intent, REQUEST_SELECT_GPU_DRIVER);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "No Android document picker is available for GPU driver import", e);
                nativeOnGpuDriverImported(null, null, null, null,
                        "No Android document picker is available");
            }
        });
    }

    public void openSaveImportPicker() {
        launchSavePicker(new Intent(Intent.ACTION_OPEN_DOCUMENT), REQUEST_IMPORT_SAVE, "Import picker unavailable");
    }

    public void openSaveExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.putExtra(Intent.EXTRA_TITLE, SAVE_DOCUMENT_NAME);
        launchSavePicker(intent, REQUEST_EXPORT_SAVE, "Export picker unavailable");
    }

    public void openSaveFolderPicker() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            try {
                startActivityForResult(intent, REQUEST_SAVE_FOLDER);
            } catch (ActivityNotFoundException e) {
                nativeOnSaveOperation("No Android folder picker is available", null);
            }
        });
    }

    public void resetSaveFolder() {
        runOnUiThread(() -> {
            String saved = getSharedPreferences(SAVE_FOLDER_PREFS, MODE_PRIVATE).getString(SAVE_FOLDER_URI, null);
            if (saved != null) {
                try { getContentResolver().releasePersistableUriPermission(Uri.parse(saved),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                catch (SecurityException ignored) { }
            }
            getSharedPreferences(SAVE_FOLDER_PREFS, MODE_PRIVATE).edit().remove(SAVE_FOLDER_URI).apply();
            nativeOnSaveOperation("External synchronization disabled; existing files were kept", "App storage");
        });
    }

    private void launchSavePicker(Intent intent, int request, String unavailableMessage) {
        runOnUiThread(() -> {
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            try { startActivityForResult(intent, request); }
            catch (ActivityNotFoundException e) { nativeOnSaveOperation(unavailableMessage, null); }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_INSTALL_MODS) {
            try {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    ArrayList<String> importedPaths = new ArrayList<>();

                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            copySelectedMod(data.getClipData().getItemAt(i).getUri(), importedPaths);
                        }
                    } else if (data.getData() != null) {
                        copySelectedMod(data.getData(), importedPaths);
                    }

                    if (!importedPaths.isEmpty()) {
                        nativeOnModsSelected(importedPaths.toArray(new String[0]));
                    }
                }
            } finally {
                // SDLActivity's normal onPause/onResume lifecycle handles native rendering state.
            }
            return;
        }

        if (requestCode == REQUEST_SELECT_ROM) {
            String importedPath = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                File importedRom = copySelectedRom(data.getData());
                if (importedRom != null) {
                    importedPath = importedRom.getAbsolutePath();
                    if (dualScreenStatsManager != null) {
                        dualScreenStatsManager.loadThemeFromRom(importedRom);
                    }
                }
            }
            nativeOnRomSelected(importedPath);
            return;
        }

        if (requestCode == REQUEST_SELECT_GPU_DRIVER) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importSelectedGpuDriver(data.getData());
            } else {
                nativeOnGpuDriverImported(null, null, null, null, "GPU driver import cancelled");
            }
            return;
        }

        if (requestCode == REQUEST_IMPORT_SAVE || requestCode == REQUEST_EXPORT_SAVE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                saveIoExecutor.execute(() -> processSaveDocument(requestCode, uri));
            } else {
                nativeOnSaveOperation("Save operation cancelled", null);
            }
            return;
        }

        if (requestCode == REQUEST_SAVE_FOLDER) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(uri, flags);
                    saveIoExecutor.execute(() -> initializeSaveFolder(uri));
                } catch (SecurityException e) {
                    nativeOnSaveOperation("Could not retain access to the selected folder", null);
                }
            } else nativeOnSaveOperation("Folder selection cancelled", null);
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void processSaveDocument(int requestCode, Uri uri) {
        File temporary = new File(getCacheDir(), requestCode == REQUEST_IMPORT_SAVE ? "save-import.bin" : "save-export.bin");
        try {
            if (requestCode == REQUEST_IMPORT_SAVE) {
                copyUriToFile(uri, temporary);
                boolean ok = nativeImportSave(temporary.getAbsolutePath());
                nativeOnSaveOperation(ok ? "Save imported successfully" : "Import rejected: wrong size or write failed", null);
                if (ok) synchronizeSaveFolder();
            } else {
                if (!nativePrepareSaveExport(temporary.getAbsolutePath())) throw new IOException("Could not snapshot active save");
                copyFileToUri(temporary, uri);
                nativeOnSaveOperation("Save exported successfully", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Save document operation failed", e);
            nativeOnSaveOperation("Save operation failed: " + e.getMessage(), null);
        } finally {
            if (!temporary.delete() && temporary.exists()) Log.w(TAG, "Could not remove " + temporary);
        }
    }

    private void initializeSaveFolder(Uri treeUri) {
        try {
            String collision = findRecognizedSaveArtifact(treeUri);
            if (collision != null) {
                nativeOnSaveOperation("Folder contains recognized save artifact " + collision
                        + "; selection was not changed. Import it explicitly to replace the active save.", null);
                return;
            }
            File snapshot = new File(getCacheDir(), "save-folder-snapshot.bin");
            if (!nativePrepareSaveExport(snapshot.getAbsolutePath())) throw new IOException("Could not snapshot active save");
            Uri document = DocumentsContract.createDocument(getContentResolver(),
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
                    "application/octet-stream", SAVE_DOCUMENT_NAME);
            if (document == null) throw new IOException("Provider refused to create save file");
            copyFileToUri(snapshot, document);
            snapshot.delete();
            getSharedPreferences(SAVE_FOLDER_PREFS, MODE_PRIVATE).edit().putString(SAVE_FOLDER_URI, treeUri.toString()).apply();
            nativeOnSaveOperation("Existing save copied to the empty destination", "App storage + synchronized folder: " + treeUri);
        } catch (Exception e) {
            Log.e(TAG, "Save folder initialization failed", e);
            nativeOnSaveOperation("Folder setup failed: " + e.getMessage(), null);
        }
    }

    private void synchronizeSaveFolder() {
        String saved = getSharedPreferences(SAVE_FOLDER_PREFS, MODE_PRIVATE).getString(SAVE_FOLDER_URI, null);
        if (saved == null || saveIoExecutor.isShutdown()) return;
        saveIoExecutor.execute(() -> {
            File snapshot = new File(getCacheDir(), "save-folder-sync.bin");
            try {
                Uri tree = Uri.parse(saved);
                Uri document = findSaveDocument(tree);
                if (document == null) throw new IOException("Destination save was removed");
                if (!nativePrepareSaveExport(snapshot.getAbsolutePath())) throw new IOException("Could not snapshot active save");
                copyFileToUri(snapshot, document);
                nativeOnSaveOperation("External save synchronized", "App storage + synchronized folder: " + tree);
            } catch (Exception e) {
                Log.e(TAG, "External save synchronization failed", e);
                nativeOnSaveOperation("External sync failed; app copy remains safe: " + e.getMessage(), null);
            } finally { snapshot.delete(); }
        });
    }

    private Uri findSaveDocument(Uri tree) throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        try (Cursor cursor = getContentResolver().query(children,
                new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (SAVE_DOCUMENT_NAME.equals(cursor.getString(1)))
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
        } catch (SecurityException e) { throw new IOException("Folder permission was revoked", e); }
        return null;
    }

    private String findRecognizedSaveArtifact(Uri tree) throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        try (Cursor cursor = getContentResolver().query(children,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (SAVE_DOCUMENT_NAME.equals(name) || RUNTIME_SAVE_NAME.equals(name)
                        || (name != null && (name.equals(SAVE_DOCUMENT_NAME + ".bak")
                        || name.equals(RUNTIME_SAVE_NAME + ".bak")))) return name;
            }
        } catch (SecurityException e) { throw new IOException("Folder permission was revoked", e); }
        return null;
    }

    /**
     * The native runtime requires a filesystem path. The SAF document is authoritative
     * across launches, while this atomically refreshed file is its runtime mirror.
     */
    private void hydrateInternalSaveFromSelectedFolder(File appDataDir) {
        String saved = getSharedPreferences(SAVE_FOLDER_PREFS, MODE_PRIVATE).getString(SAVE_FOLDER_URI, null);
        if (saved == null) return;
        Uri tree = Uri.parse(saved);
        startupSaveLocation = "External folder (runtime mirror): " + tree;
        File savesDir = new File(appDataDir, "saves");
        File destination = new File(savesDir, RUNTIME_SAVE_NAME);
        File temporary = new File(savesDir, RUNTIME_SAVE_NAME + ".external.tmp");
        File backup = new File(savesDir, RUNTIME_SAVE_NAME + ".pre-external.bak");
        if (backup.exists()) backup = new File(savesDir,
                RUNTIME_SAVE_NAME + ".pre-external-" + System.currentTimeMillis() + ".bak");
        try {
            Uri document = findSaveDocument(tree);
            if (document == null) throw new IOException("External save is missing");
            if (!savesDir.isDirectory() && !savesDir.mkdirs() && !savesDir.isDirectory())
                throw new IOException("Could not create internal save directory");
            copyUriToFile(document, temporary);
            if (temporary.length() != BANJO_SAVE_SIZE)
                throw new IOException("External save has invalid size " + temporary.length() + " (expected " + BANJO_SAVE_SIZE + ")");

            if (destination.isFile()) {
                if (!destination.renameTo(backup)) throw new IOException("Could not preserve previous internal save");
            }
            if (!temporary.renameTo(destination)) {
                if (backup.isFile()) backup.renameTo(destination);
                throw new IOException("Could not atomically activate external save");
            }
            startupSaveStatus = "Loaded authoritative external save; changes will be written back on pause";
        } catch (Exception e) {
            Log.e(TAG, "External save hydration failed; keeping app copy", e);
            if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Could not remove " + temporary);
            startupSaveStatus = "External save unavailable or invalid; previous app copy retained: " + e.getMessage();
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copyStream(in, out);
        }
    }

    private void writeActiveSelection(File activeJson, String driverId, String displayName, File driverDir, String driverSoname) throws Exception {
        JSONObject json = new JSONObject();
        json.put("mode", "custom");
        json.put("driver_id", driverId);
        json.put("display_name", displayName);
        json.put("driver_dir", driverDir.getAbsolutePath());
        json.put("driver_soname", driverSoname);
        writeTextFile(activeJson, json.toString(2));
    }

    private void importDriverPackageFile(File zipFile, File gpuDriverRoot, boolean activate) throws Exception {
        String displayName = zipFile.getName();
        String sourceFilename = sanitizeFilename(displayName);
        String driverId = makeDriverId(sourceFilename);
        File importDir = new File(new File(gpuDriverRoot, GPU_DRIVER_IMPORTS_DIR), driverId);
        if (importDir.exists() && new File(importDir, "driver.json").exists()) {
            if (activate) {
                ValidatedDriver vd = findSupportedDriver(importDir);
                if (vd != null) {
                    writeActiveSelection(new File(gpuDriverRoot, "active.json"), driverId, displayName, vd.directory, vd.soname);
                }
            }
            return;
        }

        ensureCleanDirectory(importDir);
        extractZipSafely(zipFile, importDir);
        ValidatedDriver validatedDriver = findSupportedDriver(importDir);
        if (validatedDriver == null) {
            deleteRecursively(importDir);
            throw new IOException("Staged driver does not contain supported Vulkan driver soname for ABI " + GPU_DRIVER_REQUIRED_ABI);
        }

        File driverJson = new File(importDir, "driver.json");
        writeDriverMetadata(driverJson, displayName, driverId, validatedDriver.directory, validatedDriver.soname, sourceFilename);
        Log.i(TAG, "Successfully auto-imported GPU driver " + driverId + " soname=" + validatedDriver.soname);

        if (activate) {
            writeActiveSelection(new File(gpuDriverRoot, "active.json"), driverId, displayName, validatedDriver.directory, validatedDriver.soname);
            Log.i(TAG, "Activated auto-imported GPU driver " + driverId + " as default active.json");
        }
    }

    private void hydrateInternalGpuDriverFromExternalFolder(File gpuDriverRoot) {
        try {
            File internalActiveJson = new File(gpuDriverRoot, "active.json");

            File externalFiles = getExternalFilesDir(null);
            if (externalFiles != null) {
                File externalGpuDir = new File(externalFiles, GPU_DRIVER_ROOT_DIR);
                if (!externalGpuDir.exists()) {
                    externalGpuDir.mkdirs();
                }

                File externalActiveJson = new File(externalGpuDir, "active.json");
                if (externalActiveJson.isFile()) {
                    copyFile(externalActiveJson, internalActiveJson);
                    Log.i(TAG, "Synced active.json from external storage: " + externalActiveJson.getAbsolutePath());
                }

                File[] stagedZips = externalGpuDir.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.US).endsWith(".zip"));
                if (stagedZips != null) {
                    for (File zipFile : stagedZips) {
                        try {
                            importDriverPackageFile(zipFile, gpuDriverRoot, !internalActiveJson.exists());
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to auto-import staged driver: " + zipFile.getName(), e);
                        }
                    }
                }
            }

            if (!internalActiveJson.exists()) {
                File downloadDriversDir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "drivers");
                if (downloadDriversDir.isDirectory()) {
                    File[] downloadZips = downloadDriversDir.listFiles((dir, name) ->
                        name.toLowerCase(java.util.Locale.US).contains("turnip") && name.toLowerCase(java.util.Locale.US).endsWith(".zip"));
                    if (downloadZips != null && downloadZips.length > 0) {
                        for (File zipFile : downloadZips) {
                            try {
                                importDriverPackageFile(zipFile, gpuDriverRoot, true);
                                break;
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to auto-import Turnip driver from Download: " + zipFile.getName(), e);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "hydrateInternalGpuDriverFromExternalFolder error", e);
        }
    }

    private void copyUriToFile(Uri uri, File file) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("Provider returned no input stream");
            copyStream(in, out);
        }
    }

    private void copyFileToUri(File file, Uri uri) throws IOException {
        try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri, "rwt")) {
            if (out == null) throw new IOException("Provider returned no output stream");
            copyStream(in, out);
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        out.flush();
    }

    private void copySelectedMod(Uri uri, ArrayList<String> importedPaths) {
        String displayName = getDisplayName(uri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "selected-mod.zip";
        }

        File importDir = new File(getCacheDir(), "mod-imports");
        File destination = copyDocumentToCache(uri, importDir, sanitizeFilename(displayName), "selected mod");
        if (destination != null) {
            importedPaths.add(destination.getAbsolutePath());
        }
    }

    private File copySelectedRom(Uri uri) {
        String displayName = getDisplayName(uri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "selected-rom.z64";
        }

        File importDir = new File(getCacheDir(), "rom-imports");
        return copyDocumentToCache(uri, importDir, sanitizeFilename(displayName), "selected ROM");
    }

    private void importSelectedGpuDriver(Uri uri) {
        String displayName = getDisplayName(uri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "selected-gpu-driver.zip";
        }

        String sourceFilename = sanitizeFilename(displayName);
        File cacheDir = new File(getCacheDir(), "driver-imports");
        File cachedPackage = copyDocumentToCache(uri, cacheDir, sourceFilename, "GPU driver package");
        if (cachedPackage == null) {
            nativeOnGpuDriverImported(null, null, null, null, "Failed to copy selected GPU driver package");
            return;
        }

        File importDir = null;
        try {
            String driverId = makeDriverId(sourceFilename);
            importDir = new File(new File(new File(getFilesDir(), GPU_DRIVER_ROOT_DIR), GPU_DRIVER_IMPORTS_DIR), driverId);
            ensureCleanDirectory(importDir);
            extractZipSafely(cachedPackage, importDir);

            ValidatedDriver validatedDriver = findSupportedDriver(importDir);
            if (validatedDriver == null) {
                throw new IOException("GPU driver package does not contain a supported Vulkan driver soname");
            }

            File driverJson = new File(importDir, "driver.json");
            writeDriverMetadata(driverJson, displayName, driverId, validatedDriver.directory,
                    validatedDriver.soname, sourceFilename);

            Log.i(TAG, "Imported GPU driver " + driverId + " soname=" + validatedDriver.soname
                    + " dir=" + validatedDriver.directory.getAbsolutePath());
            nativeOnGpuDriverImported(driverId, displayName, validatedDriver.directory.getAbsolutePath(),
                    validatedDriver.soname, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to import GPU driver package", e);
            if (importDir != null) {
                try {
                    deleteRecursively(importDir);
                } catch (IOException cleanupError) {
                    Log.w(TAG, "Failed to clean rejected GPU driver import " + importDir, cleanupError);
                }
            }
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
            nativeOnGpuDriverImported(null, null, null, null, errorMessage);
        } finally {
            if (!cachedPackage.delete() && cachedPackage.exists()) {
                Log.w(TAG, "Failed to remove cached GPU driver package " + cachedPackage);
            }
        }
    }

    private String makeDriverId(String sourceFilename) {
        String stem = sourceFilename;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }

        String sanitized = stem.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "gpu-driver";
        }
        return sanitized + "-" + Long.toString(System.currentTimeMillis());
    }

    private void ensureCleanDirectory(File directory) throws IOException {
        if (directory.exists()) {
            deleteRecursively(directory);
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Failed to create " + directory.getAbsolutePath());
        }
    }

    private void extractZipSafely(File zipFile, File destinationDir) throws IOException {
        String destinationRoot = destinationDir.getCanonicalPath() + File.separator;
        boolean sawEntry = false;
        int entryCount = 0;
        long totalExtractedBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                sawEntry = true;
                entryCount++;
                if (entryCount > GPU_DRIVER_ZIP_MAX_ENTRIES) {
                    throw new IOException("GPU driver package has too many ZIP entries");
                }

                String safeName = validateZipEntryName(entry.getName());
                File destination = new File(destinationDir, safeName);
                String destinationPath = destination.getCanonicalPath();
                if (!destinationPath.startsWith(destinationRoot)) {
                    throw new IOException("Rejected ZIP entry outside import directory: " + entry.getName());
                }

                long entrySize = entry.getSize();
                if (!entry.isDirectory() && entrySize > GPU_DRIVER_ZIP_MAX_ENTRY_BYTES) {
                    throw new IOException("GPU driver ZIP entry is too large: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!destination.mkdirs() && !destination.isDirectory()) {
                        throw new IOException("Failed to create directory " + destinationPath);
                    }
                } else {
                    File parent = destination.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent.getAbsolutePath());
                    }
                    try (OutputStream out = new FileOutputStream(destination)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        long entryExtractedBytes = 0;
                        while ((read = zip.read(buffer)) != -1) {
                            entryExtractedBytes += read;
                            totalExtractedBytes += read;
                            if (entryExtractedBytes > GPU_DRIVER_ZIP_MAX_ENTRY_BYTES) {
                                throw new IOException("GPU driver ZIP entry is too large: " + entry.getName());
                            }
                            if (totalExtractedBytes > GPU_DRIVER_ZIP_MAX_TOTAL_BYTES) {
                                throw new IOException("GPU driver package is too large after extraction");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }

                zip.closeEntry();
            }
        }

        if (!sawEntry) {
            throw new IOException("GPU driver package ZIP is empty or invalid");
        }
    }

    private String validateZipEntryName(String name) throws IOException {
        if (name == null) {
            throw new IOException("Rejected ZIP entry with empty name");
        }

        String normalized = name.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            throw new IOException("Rejected ZIP entry with empty name");
        }
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("Rejected absolute ZIP entry: " + name);
        }

        String componentsName = normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        if (componentsName.isEmpty()) {
            throw new IOException("Rejected ZIP entry with empty name");
        }

        for (String component : componentsName.split("/")) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Rejected unsafe ZIP entry: " + name);
            }
        }

        return componentsName;
    }

    private ValidatedDriver findSupportedDriver(File importDir) throws IOException {
        for (String soname : GPU_DRIVER_SONAME_ORDER) {
            File driverFile = findAbiDriverFile(importDir, soname, GPU_DRIVER_REQUIRED_ABI);
            if (driverFile != null && driverFile.isFile()) {
                File parent = driverFile.getParentFile();
                if (parent != null) {
                    return new ValidatedDriver(parent, soname);
                }
            }
        }

        for (String soname : GPU_DRIVER_SONAME_ORDER) {
            File driverFile = findFileNamed(importDir, soname);
            if (driverFile != null && driverFile.isFile()) {
                throw new IOException("GPU driver package contains a supported Vulkan driver soname, but not for required ABI "
                        + GPU_DRIVER_REQUIRED_ABI);
            }
        }
        return null;
    }

    private File findAbiDriverFile(File directory, String filename, String abi) {
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (child.isFile() && child.getName().equals(filename)) {
                File parent = child.getParentFile();
                if (parent != null && parent.getName().equals(abi)) {
                    return child;
                }
            }
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findAbiDriverFile(child, filename, abi);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private File findFileNamed(File directory, String filename) {
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (child.isFile() && child.getName().equals(filename)) {
                return child;
            }
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findFileNamed(child, filename);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void writeDriverMetadata(File driverJson, String displayName, String driverId, File driverDir,
                                     String driverSoname, String sourceFilename) throws Exception {
        JSONObject json = new JSONObject();
        json.put("display_name", displayName);
        json.put("id", driverId);
        json.put("dir", driverDir.getAbsolutePath());
        json.put("soname", driverSoname);
        json.put("import_time_ms", System.currentTimeMillis());
        json.put("source_filename", sourceFilename);
        writeTextFile(driverJson, json.toString(2));
    }

    private File findLatestCachedRom() {
        File importDir = new File(getCacheDir(), "rom-imports");
        File[] files = importDir.listFiles();
        if (files == null) {
            return null;
        }

        File latest = null;
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            if (latest == null || file.lastModified() > latest.lastModified()) {
                latest = file;
            }
        }
        return latest;
    }

    private File copyDocumentToCache(Uri uri, File importDir, String filename, String label) {
        if (!importDir.exists() && !importDir.mkdirs()) {
            Log.e(TAG, "Failed to create import directory " + importDir.getAbsolutePath());
            return null;
        }

        File destination = uniqueDestination(importDir, filename);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(destination)) {
            if (in == null) {
                Log.e(TAG, "Unable to open " + label + " URI " + uri);
                return null;
            }

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            Log.i(TAG, "Imported " + label + " to " + destination.getAbsolutePath());
            return destination;
        } catch (IOException e) {
            Log.e(TAG, "Failed to import " + label + " " + uri, e);
            return null;
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read display name for " + uri, e);
        }
        return uri.getLastPathSegment();
    }

    private String sanitizeFilename(String filename) {
        String sanitized = filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isEmpty() ? "selected-mod.zip" : sanitized;
    }

    private File uniqueDestination(File directory, String filename) {
        File candidate = new File(directory, filename);
        if (!candidate.exists()) {
            return candidate;
        }

        String stem = filename;
        String extension = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            stem = filename.substring(0, dot);
            extension = filename.substring(dot);
        }

        for (int i = 1; ; i++) {
            candidate = new File(directory, stem + "-" + i + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
    }

    private static final class ValidatedDriver {
        final File directory;
        final String soname;

        ValidatedDriver(File directory, String soname) {
            this.directory = directory;
            this.soname = soname;
        }
    }

    private static native void nativeOnModsSelected(String[] paths);
    private static native void nativeOnRomSelected(String path);
    private static native void nativeOnGpuDriverImported(
            String driverId,
            String displayName,
            String driverDir,
            String driverSoname,
            String error);
    private static native boolean nativePrepareSaveExport(String path);
    private static native boolean nativeImportSave(String path);
    private static native void nativeOnSaveOperation(String status, String location);

    public static void setDualScreenGameplayActiveFromNative(boolean active) {
        BanjoSDLActivity activity = currentActivity;
        if (activity == null) {
            return;
        }
        synchronized (BanjoSDLActivity.class) {
            if (lastPostedDualScreenGameplayActive != null
                    && lastPostedDualScreenGameplayActive.booleanValue() == active) {
                return;
            }
            lastPostedDualScreenGameplayActive = active;
            if (!active) {
                lastPostedDualScreenStats = null;
            }
        }

        activity.runOnUiThread(() -> {
            if (activity.dualScreenStatsManager != null) {
                activity.dualScreenStatsManager.setGameplayActive(active);
            }
        });
    }

    public static void updateDualScreenStatsFromNative(
            int displayMode,
            int health,
            int maxHealth,
            int lives,
            int notes,
            int eggs,
            int redFeathers,
            int goldFeathers,
            int jiggies,
            int mumboTokens,
            int levelId,
            int jinjosMask,
            int totalJiggies,
            int totalNotes,
            int totalHoneycombs,
            int reachedGruntysLair,
            int selectedGameNumber,
            int gameTransitionPhase) {
        BanjoSDLActivity activity = currentActivity;
        if (activity == null) {
            return;
        }

        if (levelId != lastLoggedDualScreenMapId) {
            lastLoggedDualScreenMapId = levelId;
            Log.i(TAG, "Dual-screen current map id=0x" + Integer.toHexString(levelId));
        }
        DualScreenStats stats = new DualScreenStats(
                displayMode,
                health,
                maxHealth,
                lives,
                notes,
                eggs,
                redFeathers,
                goldFeathers,
                jiggies,
                mumboTokens,
                levelId,
                jinjosMask,
                totalJiggies,
                totalNotes,
                totalHoneycombs,
                reachedGruntysLair != 0,
                selectedGameNumber,
                gameTransitionPhase);
        synchronized (BanjoSDLActivity.class) {
            if (lastPostedDualScreenStats != null && lastPostedDualScreenStats.sameValues(stats)) {
                return;
            }
            lastPostedDualScreenStats = stats;
        }
        activity.runOnUiThread(() -> {
            if (activity.dualScreenStatsManager != null) {
                activity.dualScreenStatsManager.updateStats(stats);
            }
        });
    }

    private void extractProgramAssetsIfNeeded(File programDir) throws IOException {
        if (!assetTreeExists("program")) {
            Log.i(TAG, "No packaged program assets found; skipping asset extraction");
            return;
        }

        File stampFile = new File(programDir, PROGRAM_ASSET_STAMP_FILE);
        String expectedStamp = Long.toString(new File(getPackageCodePath()).lastModified());
        String currentStamp = readTextFile(stampFile);
        if (programDir.isDirectory() && expectedStamp.equals(currentStamp)) {
            Log.i(TAG, "Program assets are already current at " + programDir.getAbsolutePath());
            return;
        }

        deleteRecursively(programDir);
        copyAssetTree("program", programDir);
        writeTextFile(stampFile, expectedStamp);
        Log.i(TAG, "Copied program assets to " + programDir.getAbsolutePath());
    }

    private boolean assetTreeExists(String assetPath) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children != null && children.length > 0) {
            return true;
        }

        try (InputStream ignored = getAssets().open(assetPath)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String readTextFile(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }

        byte[] data = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private void writeTextFile(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory " + parent);
        }

        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        if (!file.delete()) {
            throw new IOException("Failed to delete " + file);
        }
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assetPath, destination);
            return;
        }

        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Failed to create directory " + destination);
        }

        for (String child : children) {
            copyAssetTree(assetPath + "/" + child, new File(destination, child));
        }
    }

    private void copyAssetFile(String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory " + parent);
        }

        try (InputStream in = getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
