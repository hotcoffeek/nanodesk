package com.nanodesk.mobile;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.nanodesk.mobile.net.Msg;
import com.nanodesk.mobile.net.Session;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Файлы удалённого компьютера: просмотр папок, скачивание на телефон и отправка с телефона. */
public final class FilesActivity extends Activity implements Session.Listener, Link.StateListener {
    private static final int REQ_PICK = 11, REQ_SAVE = 12;
    private final Handler h = new Handler();
    private final List<Session.Item> items = new ArrayList<Session.Item>();
    private TextView pathView, status, empty;
    private ListView lv;
    private String curPath = "";
    private BaseAdapter adapter;

    // скачивание
    private OutputStream dlOut;
    private Uri dlUri;
    private String dlName = "";
    private long dlSize, dlGot;
    private Session.Item pendingItem;

    private final List<Uri> upQueue = new ArrayList<Uri>();
    private int upDest, upTotal, upDone;
    private boolean uploading;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        if (Link.I.state != Link.ACTIVE) { finish(); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Theme.bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        MainActivity.IconBtn back = new MainActivity.IconBtn(this, Icons.BACK);
        back.setContentDescription("Назад");
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onBackPressed(); } });
        head.addView(back);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(this, "Файлы", 18, Theme.text, true);
        pathView = Ui.text(this, "", 12.5f, Theme.sub, false);
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tt.addView(t1);
        tt.addView(pathView);
        head.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MainActivity.IconBtn up = new MainActivity.IconBtn(this, Icons.UP);
        up.setContentDescription("Отправить файл на компьютер");
        up.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickUpload(); } });
        head.addView(up);
        col.addView(head);

        lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        lv.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        lv.setClipToPadding(false);
        adapter = new BaseAdapter() {
            public int getCount() { return items.size() + (curPath.length() > 0 ? 1 : 0); }
            public Object getItem(int i) { return null; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) { return row(i); }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> a, View v, int pos, long id) { clicked(pos); }
        });
        col.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = Ui.text(this, "", 13.5f, Theme.text, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        status.setBackgroundColor(Theme.surface2);
        status.setVisibility(View.GONE);
        col.addView(status);
        setContentView(col);
        listDir("");
    }

    private View row(int i) {
        boolean hasUp = curPath.length() > 0;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        r.setMinimumHeight(Ui.dp(this, 60));
        r.setBackground(Ui.pressable(this, Theme.bg, Ui.mix(Theme.bg, Theme.text, 0.1f), 14, 0, 0));
        final boolean up = hasUp && i == 0;
        final Session.Item it = up ? null : items.get(i - (hasUp ? 1 : 0));
        View ic = new View(this) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            protected void onDraw(android.graphics.Canvas c) {
                p.setColor(up || (it != null && it.dir) ? Theme.accent : Theme.sub);
                Icons.draw(c, up ? Icons.BACK : (it.dir ? Icons.FOLDER : Icons.DOWN), getWidth() / 2f, getHeight() / 2f, getWidth() * 0.9f, p);
            }
        };
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView n = Ui.text(this, up ? "Наверх" : it.name, 16, Theme.text, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tx.addView(n);
        if (!up && !it.dir) tx.addView(Ui.text(this, pretty(it.size), 12.5f, Theme.sub, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    static String pretty(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format("%.1f КБ", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f МБ", b / 1048576.0);
        return String.format("%.2f ГБ", b / 1073741824.0);
    }

    private void listDir(String p) {
        Session s = Link.I.session;
        if (s == null) return;
        showStatus("Загрузка…");
        s.listDir(p);
    }

    private String parentOf(String p) {
        int k = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        if (k <= 2 && p.length() <= 3) return "";
        if (k < 0) return "";
        String r = p.substring(0, k);
        if (r.length() == 2 && r.charAt(1) == ':') r += "\\";
        return r;
    }

    private void clicked(int pos) {
        boolean hasUp = curPath.length() > 0;
        if (hasUp && pos == 0) { listDir(parentOf(curPath)); return; }
        final Session.Item it = items.get(pos - (hasUp ? 1 : 0));
        if (it.dir) { listDir(it.path); return; }
        if (dlOut != null) { toast("Подождите — идёт другая загрузка"); return; }
        Dlg.confirm(this, "Скачать на телефон?", it.name + " · " + pretty(it.size), "Скачать", new Runnable() { public void run() { startDownload(it); } });
    }

    // ---------- скачивание ----------

    private void startDownload(Session.Item it) {
        pendingItem = it;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/NanoDesk");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new Exception("нет доступа");
                begin(u, getContentResolver().openOutputStream(u));
            } catch (Exception e) {
                toast("Не удалось создать файл: " + e.getMessage());
            }
        } else {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_TITLE, it.name);
            startActivityForResult(i, REQ_SAVE);
        }
    }

    private void begin(Uri u, OutputStream o) {
        dlUri = u; dlOut = o; dlName = pendingItem.name; dlSize = pendingItem.size; dlGot = 0;
        showStatus("Скачиваю: " + dlName + " — 0%");
        Session s = Link.I.session;
        if (s != null) s.getFile(pendingItem.path);
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_SAVE && data.getData() != null) {
            try { begin(data.getData(), getContentResolver().openOutputStream(data.getData())); }
            catch (Exception e) { toast("Не удалось открыть файл для записи"); }
        } else if (req == REQ_PICK) {
            List<Uri> l = new ArrayList<Uri>();
            if (data.getClipData() != null) { for (int i = 0; i < data.getClipData().getItemCount(); i++) l.add(data.getClipData().getItemAt(i).getUri()); }
            else if (data.getData() != null) l.add(data.getData());
            if (!l.isEmpty()) chooseDest(l);
        }
    }

    public void onFileBegin(String name, long size) {
        if (dlOut != null) dlSize = size;
    }

    public void onFileData(byte[] d) {
        OutputStream o = dlOut;
        if (o == null) return;
        try {
            o.write(d);
            dlGot += d.length;
            final int pct = dlSize > 0 ? (int) (dlGot * 100 / dlSize) : 0;
            h.post(new Runnable() { public void run() { showStatus("Скачиваю: " + dlName + " — " + pct + "%"); } });
        } catch (Exception e) {
            finishDownload(false);
        }
    }

    public void onFileEnd() { finishDownload(true); }

    private void finishDownload(final boolean ok) {
        OutputStream o = dlOut;
        dlOut = null;
        if (o == null) return;
        try { o.flush(); o.close(); } catch (Exception ignore) { }
        if (Build.VERSION.SDK_INT >= 29 && dlUri != null && ok) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(dlUri, cv, null, null);
            } catch (Exception ignore) { }
        }
        final String n = dlName;
        h.post(new Runnable() {
            public void run() {
                showStatus(ok ? "Сохранено: " + (Build.VERSION.SDK_INT >= 29 ? "Загрузки/NanoDesk/" : "") + n : "Не удалось сохранить файл");
                h.postDelayed(new Runnable() { public void run() { status.setVisibility(View.GONE); } }, 4000);
            }
        });
    }

    // ---------- отправка на ПК ----------

    private void pickUpload() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    private void chooseDest(final List<Uri> l) {
        Dlg.menu(this, "Куда положить на компьютере?", new String[] { "Загрузки", "Рабочий стол", "Документы" }, new Dlg.Pick() {
            public void pick(int i) {
                upDest = i == 0 ? Msg.DEST_DOWNLOADS : (i == 1 ? Msg.DEST_DESKTOP : Msg.DEST_DOCUMENTS);
                upQueue.addAll(l);
                upTotal += l.size();
                if (!uploading) nextUpload();
            }
        });
    }

    private void nextUpload() {
        Session s = Link.I.session;
        if (s == null || upQueue.isEmpty()) {
            uploading = false;
            if (upTotal > 0) {
                showStatus("Отправлено файлов: " + upDone + " из " + upTotal);
                h.postDelayed(new Runnable() { public void run() { status.setVisibility(View.GONE); } }, 3500);
            }
            upTotal = upDone = 0;
            return;
        }
        uploading = true;
        Uri u = upQueue.remove(0);
        String name = "file";
        long size = 0;
        ContentResolver cr = getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignore) {
        } finally { if (c != null) c.close(); }
        final String fn = name;
        showStatus("Отправляю " + (upDone + 1) + " из " + upTotal + ": " + fn);
        try {
            InputStream in = cr.openInputStream(u);
            if (in == null) throw new Exception("нет доступа");
            s.upload(in, name, size, upDest, new Runnable() {
                public void run() { upDone++; h.post(new Runnable() { public void run() { nextUpload(); } }); }
            }, null);
        } catch (Exception e) {
            toast("Не удалось прочитать " + fn);
            nextUpload();
        }
    }

    // ---------- события ----------

    public void onList(final String shown, final List<Session.Item> l) {
        h.post(new Runnable() {
            public void run() {
                curPath = shown == null ? "" : shown;
                items.clear();
                items.addAll(l);
                pathView.setText(curPath.length() == 0 ? "Этот компьютер" : curPath);
                adapter.notifyDataSetChanged();
                lv.setSelection(0);
                if (dlOut == null && !uploading) status.setVisibility(View.GONE);
            }
        });
    }

    private void showStatus(String t) { status.setText(t); status.setVisibility(View.VISIBLE); }

    private void toast(String t) {
        showStatus(t);
        h.postDelayed(new Runnable() { public void run() { if (dlOut == null && !uploading) status.setVisibility(View.GONE); } }, 3500);
    }

    protected void onStart() {
        super.onStart();
        Link.I.attach(this);
        Link.I.addStateListener(this);
    }

    protected void onStop() {
        super.onStop();
        Link.I.removeStateListener(this);
        Link.I.detach(this);
    }

    public void onState(int st, String m, int e) { if (st == Link.IDLE) finish(); }

    public void onBackPressed() {
        if (curPath.length() > 0) { listDir(parentOf(curPath)); return; }
        super.onBackPressed();
        Ui.noTransition(this);
    }

    public void onInfo(int w, int hh, int mi, int mc, String name, int sc) { }
    public void onFrame(List<Session.Rect> rects) { }
    public void onCursor(boolean v, int x, int y) { }
    public void onClip(String t) { }
    public void onPerms(int p) { }
    public void onNote(String t) { }
    public void onWallpaper(byte[] j) { }
    public void onClosed(String r, boolean b, boolean d) { }
}
