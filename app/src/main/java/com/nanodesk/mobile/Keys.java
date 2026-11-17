package com.nanodesk.mobile;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import com.nanodesk.mobile.net.Msg;
import com.nanodesk.mobile.net.Session;

/** Клавиши Windows (VK-коды) и преобразование ввода с телефона. */
public final class Keys {
    private Keys() {}

    public static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D, VK_SHIFT = 0x10, VK_CTRL = 0x11, VK_ALT = 0x12, VK_ESC = 0x1B,
            VK_SPACE = 0x20, VK_PGUP = 0x21, VK_PGDN = 0x22, VK_END = 0x23, VK_HOME = 0x24, VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27,
            VK_DOWN = 0x28, VK_DELETE = 0x2E, VK_WIN = 0x5B, VK_F1 = 0x70, VK_V = 0x56, VK_C = 0x43, VK_A = 0x41, VK_Z = 0x5A, VK_X = 0x58;

    private static final Handler H = new Handler(Looper.getMainLooper());

    // «Залипающие» клавиши с панели: Ctrl, Alt, Shift, Win удерживаются до следующей нажатой клавиши.
    private static final int[] STICKY = { VK_CTRL, VK_ALT, VK_SHIFT, VK_WIN };
    private static final boolean[] held = new boolean[4];
    public static volatile Runnable stickyChanged;

    public static boolean isHeld(int vk) {
        for (int i = 0; i < 4; i++) if (STICKY[i] == vk) return held[i];
        return false;
    }

    public static void toggleSticky(Session s, int vk) {
        for (int i = 0; i < 4; i++) {
            if (STICKY[i] != vk) continue;
            held[i] = !held[i];
            if (s != null) s.key(vk, !held[i]);
        }
        Runnable r = stickyChanged;
        if (r != null) r.run();
    }

    public static void releaseSticky(Session s) {
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (held[i]) { held[i] = false; any = true; if (s != null) s.key(STICKY[i], true); }
        }
        if (any) { Runnable r = stickyChanged; if (r != null) r.run(); }
    }

    public static void press(Session s, int vk, boolean up) { if (s != null) s.key(vk, up); }

    public static void tap(Session s, int vk) {
        if (s == null) return;
        s.key(vk, false);
        s.key(vk, true);
    }

    public static void combo(Session s, int mod, int vk) {
        if (s == null) return;
        s.key(mod, false);
        s.key(vk, false);
        s.key(vk, true);
        s.key(mod, true);
    }

    public static int fromAndroid(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DEL: return VK_BACK;
            case KeyEvent.KEYCODE_FORWARD_DEL: return VK_DELETE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return VK_RETURN;
            case KeyEvent.KEYCODE_TAB: return VK_TAB;
            case KeyEvent.KEYCODE_ESCAPE: return VK_ESC;
            case KeyEvent.KEYCODE_DPAD_LEFT: return VK_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return VK_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return VK_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return VK_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return VK_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return VK_END;
            case KeyEvent.KEYCODE_PAGE_UP: return VK_PGUP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return VK_PGDN;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return VK_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return VK_CTRL;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return VK_ALT;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT: return VK_WIN;
            default:
                if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return VK_F1 + (code - KeyEvent.KEYCODE_F1);
                return 0;
        }
    }

    /** Символ → {VK, shift}. Раскладка US; null — набрать нельзя клавишей. */
    private static int[] ascii(char c) {
        if (c >= 'a' && c <= 'z') return new int[] { c - 32, 0 };
        if (c >= 'A' && c <= 'Z') return new int[] { c, 1 };
        if (c >= '0' && c <= '9') return new int[] { c, 0 };
        switch (c) {
            case ' ': return new int[] { VK_SPACE, 0 };
            case '\n': return new int[] { VK_RETURN, 0 };
            case '\t': return new int[] { VK_TAB, 0 };
            case '!': return new int[] { '1', 1 };
            case '@': return new int[] { '2', 1 };
            case '#': return new int[] { '3', 1 };
            case '$': return new int[] { '4', 1 };
            case '%': return new int[] { '5', 1 };
            case '^': return new int[] { '6', 1 };
            case '&': return new int[] { '7', 1 };
            case '*': return new int[] { '8', 1 };
            case '(': return new int[] { '9', 1 };
            case ')': return new int[] { '0', 1 };
            case ';': return new int[] { 0xBA, 0 };
            case ':': return new int[] { 0xBA, 1 };
            case '=': return new int[] { 0xBB, 0 };
            case '+': return new int[] { 0xBB, 1 };
            case ',': return new int[] { 0xBC, 0 };
            case '<': return new int[] { 0xBC, 1 };
            case '-': return new int[] { 0xBD, 0 };
            case '_': return new int[] { 0xBD, 1 };
            case '.': return new int[] { 0xBE, 0 };
            case '>': return new int[] { 0xBE, 1 };
            case '/': return new int[] { 0xBF, 0 };
            case '?': return new int[] { 0xBF, 1 };
            case '`': return new int[] { 0xC0, 0 };
            case '~': return new int[] { 0xC0, 1 };
            case '[': return new int[] { 0xDB, 0 };
            case '{': return new int[] { 0xDB, 1 };
            case '\\': return new int[] { 0xDC, 0 };
            case '|': return new int[] { 0xDC, 1 };
            case ']': return new int[] { 0xDD, 0 };
            case '}': return new int[] { 0xDD, 1 };
            case '\'': return new int[] { 0xDE, 0 };
            case '"': return new int[] { 0xDE, 1 };
            default: return null;
        }
    }

    /**
     * Ввод текста. Латиницу набираем клавишами, всё остальное (русские буквы, эмодзи) — вставкой через буфер обмена,
     * чтобы результат не зависел от раскладки на компьютере.
     */
    public static void type(Session s, String text, boolean pasteAll, android.view.View v) {
        if (s == null || text.length() == 0) return;
        boolean canPaste = (s.perms & Msg.PERM_CLIP) != 0;
        StringBuilder rest = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int[] k = pasteAll && canPaste && c != '\n' ? null : ascii(c);
            if (k != null) {
                flushPaste(s, rest);
                if (k[1] != 0) s.key(VK_SHIFT, false);
                s.key(k[0], false);
                s.key(k[0], true);
                if (k[1] != 0) s.key(VK_SHIFT, true);
                releaseSticky(s);
            } else if (canPaste) {
                rest.append(c);
            }
        }
        flushPaste(s, rest);
    }

    private static void flushPaste(final Session s, StringBuilder sb) {
        if (sb.length() == 0) return;
        paste(s, sb.toString());
        sb.setLength(0);
    }

    /** Кладёт текст в буфер ПК и жмёт Ctrl+V. */
    public static void paste(final Session s, String text) {
        s.clip(text);
        s.pause(140);
        s.key(VK_CTRL, false);
        s.key(VK_V, false);
        s.key(VK_V, true);
        s.key(VK_CTRL, true);
        s.pause(80);
    }
}
