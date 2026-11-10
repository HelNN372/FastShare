package app.fastshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

public final class MainActivity extends Activity implements PeerLink.Listener, LanDiscovery.Listener {
    private static final int PICK_FILES = 1, PICK_FOLDER = 2;
    private static final int BG = 0xff101623, PANEL = 0xff1b2436, FIELD = 0xff252f43;
    private static final int TEXT = 0xfff2f6ff, MUTED = 0xffb8c6df, BLUE = 0xffadd2ff;
    private static final int GREEN = 0xffa7e8cb, INK = 0xff10263e;

    private final List<PeerLink.FileSource> selected = new ArrayList<>();
    private List<LanDiscovery.Device> devices = Collections.emptyList();
    private List<PeerLink.FileInfo> incoming;
    private LanDiscovery discovery;
    private PeerLink link;
    private Socket pending;
    private AlertDialog verification;
    private volatile Uri folder;
    private boolean online, ready, sending, receiving, scanning, destroyed;
    private int scanGeneration;
    private String serverAddress = "", roomCode = "", peerName = "";
    private String connection = "Нет подключения", sent = "Добавь файлы для отправки.";
    private String received = "Здесь появятся входящие файлы.";
    private long sentBytes, sentSize, receivedBytes, receivedSize;

    private LinearLayout root, modePanel, devicePanel, incomingPanel;
    private TextView stateText, selectionText, sentText, receivedText, folderText;
    private ProgressBar sendProgress, receiveProgress;
    private Button sendButton, fileButton, appButton, clearButton, endButton;
    private Button localButton, internetButton, modeAction, joinAction;
    private EditText endpoint, code;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        folder = restoredFolder();
        serverAddress = prefs().getString("relay", "");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root = column();
        root.setPadding(dp(20), dp(18), dp(20), dp(20));
        scroll.addView(root);
        setContentView(scroll);

        label(root, "FASTSHARE  /  0.2", 13, BLUE, true);
        label(root, "Быстрый перенос", 32, TEXT, true);
        label(root, "Отправляй и принимай файлы с одного экрана.", 15, MUTED, false);

        LinearLayout tabs = row(root);
        localButton = button(tabs, "Локальная сеть", true, () -> switchMode(false));
        internetButton = button(tabs, "Через интернет", false, () -> switchMode(true));

        modePanel = card(root);
        LinearLayout session = card(root);
        label(session, "СОЕДИНЕНИЕ", 13, BLUE, true);
        stateText = label(session, "", 17, TEXT, true);
        endButton = button(session, "Отключить", false, this::disconnectUser);

        LinearLayout access = card(root);
        label(access, "ДОСТУП К ФАЙЛАМ", 13, BLUE, true);
        folderText = label(access, "", 15, MUTED, false);
        button(access, "Выбрать папку для входящих", false, this::pickFolder);
        label(access, "Android даст доступ к выбранным файлам и папке через системное окно.", 13, MUTED, false);

        LinearLayout outgoingPanel = card(root);
        label(outgoingPanel, "ОТПРАВИТЬ", 13, BLUE, true);
        selectionText = label(outgoingPanel, "", 15, TEXT, false);
        LinearLayout pick = row(outgoingPanel);
        fileButton = button(pick, "＋ Файлы", false, this::pickFiles);
        appButton = button(pick, "＋ Приложения", false, this::pickApps);
        clearButton = button(outgoingPanel, "Очистить выбор", false, () -> {
            selected.clear();
            render();
        });
        sendButton = button(outgoingPanel, "Отправить", true, this::sendFiles);
        sentText = label(outgoingPanel, "", 14, MUTED, false);
        sendProgress = progress(outgoingPanel);

        LinearLayout receive = card(root);
        label(receive, "ПРИНЯТЬ", 13, GREEN, true);
        incomingPanel = column();
        receive.addView(incomingPanel);
        receivedText = label(receive, "", 15, MUTED, false);
        receiveProgress = progress(receive);

        renderMode();
        render();
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("fastshare", MODE_PRIVATE);
    }

    private Uri restoredFolder() {
        String value = prefs().getString("folder", null);
        if (value == null) return null;
        Uri uri = Uri.parse(value);
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isWritePermission()) return uri;
        }
        return null;
    }

    private void switchMode(boolean requested) {
        if (online == requested) return;
        if (sending || receiving) {
            new AlertDialog.Builder(this).setTitle("Переключить режим?")
                .setMessage("Текущая передача будет прервана.")
                .setNegativeButton("Остаться", null)
                .setPositiveButton("Переключить", (dialog, which) -> changeMode(requested)).show();
        } else {
            changeMode(requested);
        }
    }

    private void changeMode(boolean value) {
        rememberInputs();
        stopDiscovery();
        closeConnection();
        online = value;
        connection = "Нет подключения";
        renderMode();
        renderIncoming();
        render();
    }

    private void renderMode() {
        styleButton(localButton, !online);
        styleButton(internetButton, online);
        modePanel.removeAllViews();
        modeAction = null;
        joinAction = null;
        endpoint = null;
        code = null;
        devicePanel = null;
        if (online) {
            label(modePanel, "ИНТЕРНЕТ-КОМНАТА", 13, BLUE, true);
            label(modePanel, "Укажи один TLS-сервер на обоих телефонах. Создай комнату на одном, передай её код второму и нажми «Войти».", 15, MUTED, false);
            endpoint = field(modePanel, "Домен сервера:порт", serverAddress);
            code = field(modePanel, "Код комнаты", roomCode);
            LinearLayout actions = row(modePanel);
            modeAction = button(actions, "Создать", true, () -> startRoom(true));
            joinAction = button(actions, "Войти", false, () -> startRoom(false));
            button(modePanel, "Скопировать код", false, () -> {
                if (code.getText().length() == 0) return;
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Код FastShare", code.getText().toString()));
                notifyUser("Код скопирован.");
            });
        } else {
            label(modePanel, "УСТРОЙСТВА РЯДОМ", 13, BLUE, true);
            label(modePanel, "Открой FastShare на двух телефонах. Подключи их к одному Wi‑Fi или к точке доступа одного из них. Затем включи поиск на обоих.", 15, MUTED, false);
            LinearLayout actions = row(modePanel);
            modeAction = button(actions, "Включить поиск", true, this::toggleDiscovery);
            button(actions, "Настройки Wi‑Fi", false, () ->
                launchSettings(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)));
            label(modePanel, "Поиск работает через Wi‑Fi и не запрашивает геолокацию.", 13, MUTED, false);
            devicePanel = column();
            modePanel.addView(devicePanel);
            renderDevices();
        }
    }

    private void renderDevices() {
        if (devicePanel == null) return;
        devicePanel.removeAllViews();
        if (devices.isEmpty()) {
            label(devicePanel, scanning ? "Ищу телефоны в этой сети…" : "Поиск выключен.", 14, MUTED, false);
            return;
        }
        for (LanDiscovery.Device device : devices) {
            Button item = button(devicePanel, device.name + "  ·  " + device.address.getHostAddress(),
                false, () -> connectLan(device));
            item.setEnabled(link == null && pending == null);
        }
    }

    private void render() {
        stateText.setText(connection);
        endButton.setVisibility(link != null || pending != null ? View.VISIBLE : View.GONE);
        boolean busy = link != null || pending != null;
        if (online) {
            endpoint.setEnabled(!busy);
            code.setEnabled(!busy);
            modeAction.setEnabled(!busy);
            joinAction.setEnabled(!busy);
        } else {
            modeAction.setText(scanning ? "Остановить поиск" : "Включить поиск");
        }
        folderText.setText(folder == null ? "Папка для входящих ещё не выбрана."
            : "Папка: " + DocumentsContract.getTreeDocumentId(folder));
        StringBuilder summary = new StringBuilder(selected.isEmpty() ? "Файлы не выбраны" : "Выбрано: " + selected.size());
        for (int i = 0; i < Math.min(selected.size(), 4); i++) summary.append("\n• ").append(selected.get(i).name);
        if (selected.size() > 4) summary.append("\nи ещё ").append(selected.size() - 4);
        selectionText.setText(summary);
        fileButton.setEnabled(!sending);
        appButton.setEnabled(!sending);
        clearButton.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        clearButton.setEnabled(!sending);
        sendButton.setEnabled(ready && !sending && !selected.isEmpty());
        sentText.setText(sent);
        receivedText.setText(received);
        updateProgress(sendProgress, sending, sentBytes, sentSize);
        updateProgress(receiveProgress, receiving, receivedBytes, receivedSize);
        if (busy || scanning) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void renderIncoming() {
        incomingPanel.removeAllViews();
        if (incoming == null) return;
        label(incomingPanel, peerName + " предлагает файлов: " + incoming.size(), 17, TEXT, true);
        for (int i = 0; i < Math.min(4, incoming.size()); i++) {
            PeerLink.FileInfo file = incoming.get(i);
            label(incomingPanel, file.name + (file.size >= 0 ? "  ·  " + size(file.size) : ""), 14, MUTED, false);
        }
        if (incoming.size() > 4) label(incomingPanel, "и ещё " + (incoming.size() - 4), 14, MUTED, false);
        LinearLayout choices = row(incomingPanel);
        button(choices, folder == null ? "Выбрать папку" : "Принять", true, this::accept);
        button(choices, "Отклонить", false, this::reject);
    }

    private void toggleDiscovery() {
        if (scanning) {
            stopDiscovery();
            if (link == null && pending == null) connection = "Нет подключения";
            render();
            return;
        }
        scanning = true;
        int generation = ++scanGeneration;
        if (link == null && pending == null) connection = "Поиск телефонов поблизости";
        render();
        new Thread(() -> {
            try {
                LanDiscovery result = new LanDiscovery(deviceName(), this);
                runOnUiThread(() -> {
                    if (destroyed || generation != scanGeneration) close(result);
                    else {
                        discovery = result;
                        result.start();
                    }
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (generation != scanGeneration) return;
                    stopDiscovery();
                    connection = "Не удалось начать поиск. Проверь Wi‑Fi и доступ к локальной сети.";
                    render();
                });
            }
        }, "fastshare-start-discovery").start();
    }

    private void stopDiscovery() {
        scanGeneration++;
        scanning = false;
        close(discovery);
        discovery = null;
        devices = Collections.emptyList();
        renderDevices();
    }

    @Override public void devices(LanDiscovery source, List<LanDiscovery.Device> found) {
        runOnUiThread(() -> {
            if (discovery != source || destroyed) return;
            devices = found;
            renderDevices();
        });
    }

    @Override public void incoming(LanDiscovery source, Socket socket) {
        runOnUiThread(() -> {
            if (discovery != source || destroyed || pending != null || link != null) {
                close(socket);
                return;
            }
            begin(socket, null, "Входящее подключение…", () -> socket);
        });
    }

    @Override public void stopped(LanDiscovery source, String error) {
        runOnUiThread(() -> {
            if (discovery != source || destroyed) return;
            stopDiscovery();
            if (link == null && pending == null) connection = error;
            render();
        });
    }

    private void connectLan(LanDiscovery.Device device) {
        if (pending != null || link != null) return;
        Socket raw = new Socket();
        begin(raw, null, "Подключаюсь к " + device.name + "…", () -> {
            raw.connect(new InetSocketAddress(device.address, device.port), 8000);
            return raw;
        });
    }

    private void rememberInputs() {
        if (endpoint != null) serverAddress = endpoint.getText().toString().trim();
        if (code != null) roomCode = code.getText().toString().trim();
    }

    private void startRoom(boolean create) {
        if (pending != null || link != null) return;
        rememberInputs();
        try {
            InternetRoom.Endpoint address = new InternetRoom.Endpoint(serverAddress);
            if (create) {
                roomCode = InternetRoom.newCode();
                code.setText(roomCode);
            }
            byte[] secret = InternetRoom.codeBytes(roomCode);
            prefs().edit().putString("relay", serverAddress).apply();
            Socket raw = new Socket();
            begin(raw, secret, "Подключаюсь к комнате. Ожидание второго телефона до 5 минут.",
                () -> InternetRoom.connect(raw, address, secret));
        } catch (IOException error) {
            notifyUser(error.getMessage());
        }
    }

    private interface Connector { Socket connect() throws Exception; }

    private void begin(Socket raw, byte[] secret, String message, Connector connector) {
        pending = raw;
        connection = message;
        renderDevices();
        render();
        new Thread(() -> {
            try {
                Socket transport = connector.connect();
                transport.setTcpNoDelay(true);
                transport.setSoTimeout(18000);
                PeerLink session = new PeerLink(transport, deviceName(), secret, this);
                transport.setSoTimeout(0);
                runOnUiThread(() -> {
                    if (destroyed || pending != raw) {
                        close(session);
                        return;
                    }
                    pending = null;
                    link = session;
                    connection = "Проверяю соединение…";
                    render();
                    session.start();
                });
            } catch (Exception error) {
                close(raw);
                runOnUiThread(() -> {
                    if (destroyed || pending != raw) return;
                    pending = null;
                    String reason = error instanceof javax.net.ssl.SSLException
                        ? "Проверь сертификат и домен TLS-сервера."
                        : error instanceof java.net.SocketTimeoutException
                            ? "Время ожидания вышло. Повтори подключение." : error.getMessage();
                    connection = "Не удалось подключиться." + (reason == null ? "" : " " + reason);
                    renderDevices();
                    render();
                });
            }
        }, "fastshare-connect").start();
    }

    @Override public void verify(PeerLink session, String peer, String check) {
        onLink(session, () -> {
            connection = "Сверь код с телефоном «" + peer + "».";
            render();
            verification = new AlertDialog.Builder(this).setTitle("Код подключения  " + check)
                .setMessage("На втором телефоне должен быть тот же код. Если цифры совпадают, подтверди на обоих устройствах.")
                .setNegativeButton("Отклонить", (dialog, which) -> disconnectUser())
                .setPositiveButton("Коды совпадают", (dialog, which) -> {
                    connection = "Ожидаю подтверждение второго телефона…";
                    render();
                    session.trust();
                }).setOnCancelListener(dialog -> disconnectUser()).show();
        });
    }

    @Override public void ready(PeerLink session, String peer) {
        onLink(session, () -> {
            ready = true;
            peerName = peer;
            connection = "●  На связи: " + peer;
            render();
        });
    }

    @Override public void offer(PeerLink session, List<PeerLink.FileInfo> files) {
        onLink(session, () -> {
            incoming = files;
            received = "Подтверди, если хочешь сохранить файлы.";
            renderIncoming();
            receivedText.setText(received);
            incomingPanel.post(() -> {
                if (incoming == files) {
                    incomingPanel.requestRectangleOnScreen(new android.graphics.Rect(
                        0, 0, incomingPanel.getWidth(), incomingPanel.getHeight()), true);
                }
            });
        });
    }

    private void accept() {
        if (incoming == null || link == null) return;
        if (folder == null) {
            pickFolder();
            return;
        }
        incoming = null;
        receiving = true;
        receivedBytes = 0;
        receivedSize = -1;
        received = "Сохраняю файлы…";
        link.answer(true);
        renderIncoming();
        render();
    }

    private void reject() {
        if (incoming == null || link == null) return;
        incoming = null;
        received = "Передача отклонена.";
        link.answer(false);
        renderIncoming();
        render();
    }

    @Override public PeerLink.Target open(PeerLink.FileInfo file) throws IOException {
        Uri tree = folder;
        if (tree == null) throw new IOException("Папка для сохранения недоступна.");
        ContentResolver resolver = getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri document = DocumentsContract.createDocument(resolver, parent, file.mime, file.name);
        if (document == null) throw new IOException("Не удалось создать файл.");
        OutputStream stream;
        try {
            stream = resolver.openOutputStream(document, "w");
            if (stream == null) throw new IOException("Не удалось открыть файл для записи.");
        } catch (IOException | RuntimeException error) {
            delete(resolver, document);
            throw error;
        }
        OutputStream output = new BufferedOutputStream(stream, 128 * 1024);
        return new PeerLink.Target() {
            @Override public void write(byte[] bytes) throws IOException { output.write(bytes); }
            @Override public void commit() throws IOException { output.close(); }
            @Override public void abort() {
                close(output);
                delete(resolver, document);
            }
        };
    }

    private static void delete(ContentResolver resolver, Uri document) {
        try { DocumentsContract.deleteDocument(resolver, document); }
        catch (Exception ignored) {}
    }

    @Override public void progress(PeerLink session, boolean upload, String name, long bytes, long total) {
        onLink(session, () -> {
            String caption = name + "  ·  " + size(bytes) + (total >= 0 ? " / " + size(total) : "");
            if (upload && sending) {
                sent = caption;
                sentBytes = bytes;
                sentSize = total;
                sentText.setText(caption);
                updateProgress(sendProgress, true, bytes, total);
            } else if (!upload && receiving) {
                received = caption;
                receivedBytes = bytes;
                receivedSize = total;
                receivedText.setText(caption);
                updateProgress(receiveProgress, true, bytes, total);
            }
        });
    }

    @Override public void finished(PeerLink session, boolean upload, boolean success, String detail) {
        onLink(session, () -> {
            if (upload) {
                sending = false;
                sent = detail;
                if (success) selected.clear();
            } else {
                receiving = false;
                received = detail;
            }
            render();
        });
    }

    @Override public void closed(PeerLink session, String detail) {
        onLink(session, () -> {
            closeConnection();
            connection = detail;
            renderDevices();
            renderIncoming();
            render();
        });
    }

    private void onLink(PeerLink source, Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed && link == source) action.run();
        });
    }

    private void sendFiles() {
        if (!ready || link == null || sending || selected.isEmpty()) return;
        sending = true;
        sent = "Жду подтверждения получателя…";
        sentBytes = 0;
        sentSize = -1;
        link.send(new ArrayList<>(selected));
        render();
    }

    private void disconnectUser() {
        closeConnection();
        connection = scanning ? "Поиск телефонов поблизости" : "Нет подключения";
        renderDevices();
        renderIncoming();
        render();
    }

    private void closeConnection() {
        if (verification != null) {
            verification.dismiss();
            verification = null;
        }
        close(link);
        close(pending);
        link = null;
        pending = null;
        ready = false;
        if (sending) sent = "Отправка прервана.";
        if (receiving || incoming != null) received = "Получение прервано.";
        sending = false;
        receiving = false;
        incoming = null;
        peerName = "";
    }

    private void pickFiles() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        launchPicker(picker, PICK_FILES);
    }

    private void pickFolder() {
        if (receiving) {
            notifyUser("Дождись окончания приёма файлов.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        launchPicker(picker, PICK_FOLDER);
    }

    private void launchSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            notifyUser("Открой настройки Wi‑Fi вручную.");
        }
    }

    private void launchPicker(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException error) {
            notifyUser("Системный выбор файлов недоступен.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_FOLDER && data.getData() != null) {
            Uri chosen = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                notifyUser("Выбранная папка доступна только для чтения.");
                return;
            }
            Uri previous = folder;
            try {
                getContentResolver().takePersistableUriPermission(chosen, flags);
                prefs().edit().putString("folder", chosen.toString()).apply();
                if (previous != null && !previous.equals(chosen)) {
                    for (UriPermission grant : getContentResolver().getPersistedUriPermissions()) {
                        if (!grant.getUri().equals(previous)) continue;
                        int oldFlags = (grant.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                            | (grant.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                        getContentResolver().releasePersistableUriPermission(previous, oldFlags);
                    }
                }
            } catch (SecurityException ignored) {
                // The current temporary grant still permits receiving while the activity stays open.
            }
            folder = chosen;
            renderIncoming();
        } else if (request == PICK_FILES && !sending) {
            ClipData clip = data.getClipData();
            if (clip == null && data.getData() != null) addFile(data.getData());
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) addFile(clip.getItemAt(i).getUri());
            }
            if (selected.size() == PeerLink.MAX_FILES) notifyUser("За одну передачу можно выбрать до 128 файлов.");
        }
        render();
    }

    private void addFile(Uri uri) {
        if (uri == null || selected.size() >= PeerLink.MAX_FILES) return;
        String name = uri.getLastPathSegment(), mime = null;
        long length = -1;
        try {
            mime = getContentResolver().getType(uri);
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = cursor.getString(0);
                    if (!cursor.isNull(1)) length = cursor.getLong(1);
                }
            }
        } catch (RuntimeException ignored) {
            // Some providers expose a stream without metadata.
        }
        selected.add(new PeerLink.FileSource(name, mime, length, () -> {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Файл больше недоступен.");
            return in;
        }));
    }

    private void pickApps() {
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, ApplicationInfo> found = new LinkedHashMap<>();
        for (ResolveInfo entry : manager.queryIntentActivities(launcher, 0)) {
            ApplicationInfo app = entry.activityInfo.applicationInfo;
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) found.put(app.packageName, app);
        }
        List<ApplicationInfo> apps = new ArrayList<>(found.values());
        Collections.sort(apps, (a, b) -> a.loadLabel(manager).toString().compareToIgnoreCase(b.loadLabel(manager).toString()));
        if (apps.isEmpty()) {
            notifyUser("Приложения для экспорта не найдены.");
            return;
        }
        String[] names = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i).loadLabel(manager).toString();
        new AlertDialog.Builder(this).setTitle("Выбрать приложения")
            .setMultiChoiceItems(names, checked, (dialog, index, checkedNow) -> checked[index] = checkedNow)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", (dialog, which) -> {
                for (int i = 0; i < names.length && selected.size() < PeerLink.MAX_FILES; i++) {
                    if (checked[i]) selected.add(appSource(apps.get(i), names[i]));
                }
                render();
            }).show();
    }

    private PeerLink.FileSource appSource(ApplicationInfo app, String label) {
        String name = PeerLink.safeName(label);
        File base = new File(app.publicSourceDir);
        String[] splits = app.splitPublicSourceDirs;
        if (splits == null || splits.length == 0) {
            return new PeerLink.FileSource(name + ".apk", "application/vnd.android.package-archive",
                base.length(), () -> new FileInputStream(base));
        }
        return new PeerLink.FileSource(name + ".apks", "application/octet-stream", -1, () -> {
            File archive = File.createTempFile("splits-", ".apks", getCacheDir());
            try {
                try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
                    out.setLevel(Deflater.NO_COMPRESSION);
                    zipPart(out, base, "base.apk");
                    for (String path : splits) {
                        File file = new File(path);
                        zipPart(out, file, file.getName());
                    }
                }
                return new FilterInputStream(new FileInputStream(archive)) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { archive.delete(); }
                    }
                };
            } catch (IOException | RuntimeException error) {
                archive.delete();
                throw error;
            }
        });
    }

    private static void zipPart(ZipOutputStream out, File file, String name) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        byte[] buffer = new byte[128 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            for (int count; (count = in.read(buffer)) != -1;) out.write(buffer, 0, count);
        }
        out.closeEntry();
    }

    private String deviceName() {
        String model = android.os.Build.MODEL;
        return model == null || model.trim().isEmpty() ? "Android" : model;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024L * 1024) return String.format(Locale.getDefault(), "%.1f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f МБ", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.1f ГБ", bytes / 1073741824.0);
    }

    private ProgressBar progress(LinearLayout parent) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(GREEN));
        bar.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        bar.setMax(100);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(12)));
        return bar;
    }

    private static void updateProgress(ProgressBar bar, boolean active, long bytes, long total) {
        bar.setVisibility(active ? View.VISIBLE : View.GONE);
        bar.setIndeterminate(active && total < 0);
        if (active && total >= 0) bar.setProgress(total == 0 ? 100 : (int) Math.min(100, 100.0 * bytes / total));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(layout, new LinearLayout.LayoutParams(-1, -2));
        return layout;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout layout = column();
        layout.setBackground(shape(PANEL, 20));
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(13);
        parent.addView(layout, params);
        return layout;
    }

    private TextView label(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, Typeface.BOLD);
        text.setPadding(0, dp(4), 0, dp(6));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setSingleLine(true);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextSize(15);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setBackground(shape(FIELD, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        parent.addView(edit, params);
        return edit;
    }

    private Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(null, Typeface.BOLD);
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
            ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (parent.getChildCount() > 0 && parent.getOrientation() == LinearLayout.HORIZONTAL) params.leftMargin = dp(7);
        params.topMargin = dp(7);
        parent.addView(button, params);
        styleButton(button, primary);
        return button;
    }

    private void styleButton(Button button, boolean primary) {
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{primary ? 0xff47627d : 0xff8795af, primary ? INK : TEXT}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44799dd4),
            shape(primary ? BLUE : FIELD, 12), null));
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void notifyUser(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static void close(Closeable resource) {
        if (resource != null) try { resource.close(); } catch (IOException ignored) {}
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopDiscovery();
        closeConnection();
        super.onDestroy();
    }
}
