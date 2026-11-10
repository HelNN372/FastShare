package app.fastshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.widget.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

public final class MainActivity extends Activity {
    private static final int PICK_FILES = 1;
    private final LinkedHashMap<String, TransferServer.Item> selected = new LinkedHashMap<>();
    private TransferServer server;
    private TextView status;
    private TextView link;
    private Button files, apps, clear, start, share, copy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(243, 247, 255));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(22), dp(22), dp(22));
        scroll.addView(layout);
        setContentView(scroll);

        TextView title = label(layout, "Быстрый перенос", 30);
        title.setTypeface(null, Typeface.BOLD);
        label(layout, "Подключи оба телефона к одному Wi‑Fi или включи точку доступа на этом телефоне. На втором телефоне открой ссылку в браузере.", 16);
        label(layout, "Ссылка работает в локальной сети. Используй сеть, которой доверяешь, и держи этот экран открытым во время передачи.", 14);
        files = button(layout, "Добавить фото, музыку и файлы", this::pickFiles);
        apps = button(layout, "Добавить установленные приложения", this::pickApps);
        clear = button(layout, "Очистить список", () -> { selected.clear(); refresh(); });
        status = label(layout, "", 16);
        start = button(layout, "Начать передачу", this::toggleTransfer);
        link = label(layout, "", 16);
        link.setTextIsSelectable(true);
        copy = button(layout, "Скопировать ссылку", this::copyLink);
        share = button(layout, "Поделиться ссылкой", this::shareLink);
        refresh();
    }

    private TextView label(LinearLayout layout, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(20, 33, 61));
        text.setPadding(0, dp(5), 0, dp(9));
        layout.addView(text);
        return text;
    }

    private Button button(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        boolean running = server != null;
        files.setEnabled(!running);
        apps.setEnabled(!running);
        clear.setEnabled(!running && !selected.isEmpty());
        start.setEnabled(!selected.isEmpty());
        start.setText(running ? "Остановить передачу" : "Начать передачу");
        copy.setVisibility(running ? Button.VISIBLE : Button.GONE);
        share.setVisibility(running ? Button.VISIBLE : Button.GONE);
        link.setVisibility(running ? TextView.VISIBLE : TextView.GONE);
        link.setText(running ? server.url() : "");
        StringBuilder list = new StringBuilder("Выбрано: ").append(selected.size());
        int shown = 0;
        for (TransferServer.Item item : selected.values()) {
            if (shown++ == 6) {
                list.append("\n…");
                break;
            }
            list.append("\n• ").append(item.name);
        }
        status.setText(list);
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_FILES);
        } catch (android.content.ActivityNotFoundException error) {
            notifyUser("На телефоне недоступен системный выбор файлов.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        ClipData clip = data.getClipData();
        if (clip == null) {
            if (data.getData() != null) addFile(data.getData());
        } else {
            for (int i = 0; i < clip.getItemCount(); i++) addFile(clip.getItemAt(i).getUri());
        }
        refresh();
    }

    private void addFile(Uri uri) {
        String name = uri.getLastPathSegment();
        long length = -1;
        String[] columns = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        try (Cursor cursor = getContentResolver().query(uri, columns, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(columns[0]);
                int sizeColumn = cursor.getColumnIndex(columns[1]);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) length = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) {
            // Some document providers expose a stream without metadata.
        }
        selected.put(uri.toString(), new TransferServer.Item(safeName(name), length, out -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("File unavailable");
                transfer(in, out);
            } catch (SecurityException error) {
                throw new IOException(error);
            }
        }));
    }

    private void pickApps() {
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, ApplicationInfo> found = new LinkedHashMap<>();
        for (ResolveInfo entry : manager.queryIntentActivities(launcher, 0)) {
            ApplicationInfo info = entry.activityInfo.applicationInfo;
            found.put(info.packageName, info);
        }
        List<ApplicationInfo> applications = new ArrayList<>(found.values());
        Collections.sort(applications, (a, b) -> a.loadLabel(manager).toString()
            .compareToIgnoreCase(b.loadLabel(manager).toString()));
        if (applications.isEmpty()) {
            notifyUser("Список приложений пуст.");
            return;
        }
        String[] labels = new String[applications.size()];
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            ApplicationInfo info = applications.get(i);
            labels[i] = info.loadLabel(manager).toString();
            checked[i] = selected.containsKey("apk:" + info.packageName);
        }
        new AlertDialog.Builder(this).setTitle("Установленные приложения")
            .setMultiChoiceItems(labels, checked, (dialog, index, enabled) -> checked[index] = enabled)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Готово", (dialog, which) -> {
                for (int i = 0; i < labels.length; i++) {
                    ApplicationInfo info = applications.get(i);
                    String key = "apk:" + info.packageName;
                    if (checked[i]) selected.put(key, appItem(info, safeName(labels[i])));
                    else selected.remove(key);
                }
                refresh();
            }).show();
    }

    private TransferServer.Item appItem(ApplicationInfo info, String label) {
        File base = new File(info.publicSourceDir);
        if (info.splitPublicSourceDirs == null || info.splitPublicSourceDirs.length == 0) {
            return diskItem(base, label + ".apk");
        }
        List<TransferServer.Item> parts = new ArrayList<>();
        parts.add(diskItem(base, "base.apk"));
        for (String path : info.splitPublicSourceDirs) {
            File file = new File(path);
            parts.add(diskItem(file, file.getName()));
        }
        return new TransferServer.Item(label + ".apks", -1, out -> zip(parts, out));
    }

    private static TransferServer.Item diskItem(File file, String name) {
        return new TransferServer.Item(safeName(name), file.length(), out -> {
            try (InputStream in = new FileInputStream(file)) { transfer(in, out); }
        });
    }

    private static String safeName(String input) {
        if (input == null) return "Файл";
        String name = input.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "Файл" : name;
    }

    private static void transfer(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        for (int count; (count = in.read(buffer)) != -1;) out.write(buffer, 0, count);
    }

    private static void zip(List<TransferServer.Item> items, OutputStream out) throws IOException {
        Set<String> names = new HashSet<>();
        OutputStream shield = new FilterOutputStream(out) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }
            @Override public void close() {}
        };
        try (ZipOutputStream archive = new ZipOutputStream(shield)) {
            archive.setLevel(Deflater.NO_COMPRESSION);
            for (TransferServer.Item item : items) {
                String name = item.name;
                int extension = name.lastIndexOf('.');
                int split = extension > 0 ? extension : name.length();
                int number = 2;
                while (!names.add(name)) {
                    name = item.name.substring(0, split) + " (" + number++ + ")" + item.name.substring(split);
                }
                archive.putNextEntry(new ZipEntry(name));
                item.writer.write(archive);
                archive.closeEntry();
            }
        }
    }

    private void toggleTransfer() {
        if (server != null) {
            stopTransfer();
            refresh();
            return;
        }
        try {
            List<InetAddress> addresses = new ArrayList<>();
            List<InetAddress> fallback = new ArrayList<>();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isPointToPoint()) continue;
                String name = network.getName();
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) continue;
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                        addresses.add(address);
                    } else {
                        fallback.add(address);
                    }
                }
            }
            if (addresses.isEmpty()) addresses.addAll(fallback);
            if (addresses.isEmpty()) {
                notifyUser("Подключись к Wi‑Fi или включи точку доступа.");
            } else if (addresses.size() == 1) {
                startTransfer(addresses.get(0));
            } else {
                String[] labels = new String[addresses.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = addresses.get(i).getHostAddress();
                new AlertDialog.Builder(this).setTitle("Выбери локальный адрес")
                    .setItems(labels, (dialog, index) -> startTransfer(addresses.get(index))).show();
            }
        } catch (IOException error) {
            notifyUser("Не удалось получить адрес Wi‑Fi.");
        }
    }

    private void startTransfer(InetAddress address) {
        List<TransferServer.Item> items = new ArrayList<>(selected.values());
        if (items.size() > 1) {
            List<TransferServer.Item> contents = new ArrayList<>(items);
            items.add(0, new TransferServer.Item("Скачать всё.zip", -1, out -> zip(contents, out)));
        }
        try {
            server = new TransferServer(address, items);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            refresh();
        } catch (IOException error) {
            notifyUser("Не удалось начать передачу. Проверь подключение и попробуй ещё раз.");
        }
    }

    private void copyLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Ссылка для переноса", server.url()));
        notifyUser("Ссылка скопирована");
    }

    private void shareLink() {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, server.url());
        startActivity(Intent.createChooser(send, "Передать ссылку"));
    }

    private void notifyUser(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void stopTransfer() {
        if (server == null) return;
        try { server.close(); } catch (IOException ignored) {}
        server = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onDestroy() {
        stopTransfer();
        super.onDestroy();
    }
}
