package com.nanodesk.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Настройки и список компьютеров. Пароли хранятся только зашифрованными ключом из Android Keystore. */
public final class Store {
    private Store() {}

    public static final class Device {
        public String id = "";      // ID компьютера или IP‑адрес
        public String name = "";
        public boolean ip;
        public int port = 21740;
        public long last;
        public String pw = "";      // зашифрованный пароль
    }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("nd", Context.MODE_PRIVATE); }

    public static String theme(Context c) { return sp(c).getString("theme", Theme.DARK); }
    public static void setTheme(Context c, String t) { sp(c).edit().putString("theme", t).apply(); }
    public static boolean noAnim(Context c) { return sp(c).getBoolean("noanim", false); }
    public static void setNoAnim(Context c, boolean v) { sp(c).edit().putBoolean("noanim", v).apply(); }
    public static String prevTheme(Context c) { return sp(c).getString("prevtheme", Theme.DARK); }
    public static void setPrevTheme(Context c, String t) { sp(c).edit().putString("prevtheme", t).apply(); }

    public static boolean touchpad(Context c) { return sp(c).getBoolean("pad", true); }
    public static void setTouchpad(Context c, boolean v) { sp(c).edit().putBoolean("pad", v).apply(); }
    public static boolean pasteAll(Context c) { return sp(c).getBoolean("pasteall", false); }
    public static void setPasteAll(Context c, boolean v) { sp(c).edit().putBoolean("pasteall", v).apply(); }
    public static boolean invertScroll(Context c) { return sp(c).getBoolean("invscroll", false); }
    public static void setInvertScroll(Context c, boolean v) { sp(c).edit().putBoolean("invscroll", v).apply(); }
    public static boolean keepOn(Context c) { return sp(c).getBoolean("keepon", true); }
    public static void setKeepOn(Context c, boolean v) { sp(c).edit().putBoolean("keepon", v).apply(); }
    public static boolean haptics(Context c) { return sp(c).getBoolean("haptics", true); }
    public static void setHaptics(Context c, boolean v) { sp(c).edit().putBoolean("haptics", v).apply(); }
    public static boolean volumeScroll(Context c) { return sp(c).getBoolean("volscroll", false); }
    public static void setVolumeScroll(Context c, boolean v) { sp(c).edit().putBoolean("volscroll", v).apply(); }
    /** Размер курсора: 0 — маленький, 1 — средний (по умолчанию), 2 — большой. */
    public static int pointer(Context c) { return sp(c).getInt("pointer", 1); }
    public static void setPointer(Context c, int v) { sp(c).edit().putInt("pointer", v).apply(); }
    public static float pointerScale(Context c) { int p = pointer(c); return p == 0 ? 0.5f : (p == 2 ? 1.0f : 0.7f); }
    public static int speed(Context c) { return sp(c).getInt("speed", 14); }          // в десятых долях: 14 = ×1.4
    public static void setSpeed(Context c, int v) { sp(c).edit().putInt("speed", v).apply(); }
    public static int quality(Context c) { return sp(c).getInt("quality", 60); }
    public static void setQuality(Context c, int v) { sp(c).edit().putInt("quality", v).apply(); }
    public static int fps(Context c) { return sp(c).getInt("fps", 30); }
    public static void setFps(Context c, int v) { sp(c).edit().putInt("fps", v).apply(); }
    public static boolean autoQuality(Context c) { return sp(c).getBoolean("autoq", true); }
    public static void setAutoQuality(Context c, boolean v) { sp(c).edit().putBoolean("autoq", v).apply(); }
    public static boolean halfRes(Context c) { return sp(c).getBoolean("half", false); }
    public static void setHalfRes(Context c, boolean v) { sp(c).edit().putBoolean("half", v).apply(); }
    public static String phoneName(Context c) {
        String n = sp(c).getString("phonename", "");
        if (n.length() == 0) n = (Build.MODEL == null ? "Android" : Build.MODEL);
        return n;
    }

    // ---------- компьютеры ----------

    public static List<Device> devices(Context c) {
        List<Device> l = new ArrayList<Device>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("devices", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Device d = new Device();
                d.id = o.optString("id"); d.name = o.optString("name"); d.ip = o.optBoolean("ip");
                d.port = o.optInt("port", 21740); d.last = o.optLong("last"); d.pw = o.optString("pw");
                if (d.id.length() > 0) l.add(d);
            }
        } catch (Exception ignore) { }
        Collections.sort(l, new Comparator<Device>() {
            public int compare(Device a, Device b) { return a.last == b.last ? 0 : (a.last < b.last ? 1 : -1); }
        });
        return l;
    }

    private static void write(Context c, List<Device> l) {
        try {
            JSONArray a = new JSONArray();
            for (Device d : l) {
                JSONObject o = new JSONObject();
                o.put("id", d.id); o.put("name", d.name); o.put("ip", d.ip); o.put("port", d.port); o.put("last", d.last); o.put("pw", d.pw);
                a.put(o);
            }
            sp(c).edit().putString("devices", a.toString()).apply();
        } catch (Exception ignore) { }
    }

    public static Device find(Context c, String id) {
        for (Device d : devices(c)) if (d.id.equals(id)) return d;
        return null;
    }

    public static void save(Context c, Device nd) {
        List<Device> l = devices(c);
        boolean found = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(nd.id)) { l.set(i, nd); found = true; }
        if (!found) l.add(nd);
        write(c, l);
    }

    public static void remove(Context c, String id) {
        List<Device> l = devices(c);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        write(c, l);
    }

    // ---------- пароли ----------

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("nd_pw")) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder("nd_pw", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            return kg.generateKey();
        }
        return (SecretKey) ks.getKey("nd_pw", null);
    }

    /** На Android 5 хранилища ключей с нужным режимом нет — пароль там не запоминается. */
    public static boolean canRemember() { return Build.VERSION.SDK_INT >= 23; }

    public static String encrypt(String pw) {
        if (!canRemember() || pw == null || pw.length() == 0) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(pw.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public static String decrypt(String s) {
        if (!canRemember() || s == null || s.length() == 0) return "";
        try {
            byte[] all = Base64.decode(s, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
