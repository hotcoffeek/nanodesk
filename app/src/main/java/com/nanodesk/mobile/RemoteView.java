package com.nanodesk.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.nanodesk.mobile.net.Msg;
import com.nanodesk.mobile.net.Session;

/**
 * Экран удалённого компьютера: показ картинки, масштабирование и жесты.
 * Два режима: «тачпад» (палец двигает курсор, как на ноутбуке) и «касание» (палец — сам курсор).
 */
public final class RemoteView extends View {
    public interface Events {
        void onUserTouch();
        void onZoomChanged();
    }

    private final Link link = Link.I;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ptr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler h = new Handler(Looper.getMainLooper());
    public Events events;

    public boolean touchpad = true;
    public boolean panMode;                 // двухпальцевое движение двигает картинку, а не крутит колесо
    public float speed = 1.4f;
    public float ptrScale = 0.7f;
    private long lastClickAt;
    public boolean invertScroll;
    public boolean haptics = true;
    public boolean pasteAll;

    // положение картинки: tx, ty — где на экране левый верхний угол; zoom — во сколько раз крупнее «вписанного» вида
    private float zoom = 1f, tx, ty;
    private float cx, cy;                   // курсор в координатах удалённого экрана
    private boolean cursorKnown;
    private long lastTouchAt;

    private final int slop;
    private final int longPressMs = 480;
    private final float scrollStep;

    // жесты
    private int mode;                       // 0 нет, 1 один палец, 2 два пальца
    private float downX, downY, lastX, lastY, startT;
    private boolean moved, longFired, dragging, dragArmed;
    private long lastTapAt;
    private float lastTapX, lastTapY;
    private float g2StartDist, g2LastDist, g2LastCx, g2LastCy, g2StartCx, g2StartCy;
    private int g2Kind;                     // 0 не определён, 1 зум, 2 прокрутка/сдвиг
    private boolean g2Moved;
    private long g2Down;
    private float wheelAccX, wheelAccY;

    private final Runnable longPress = new Runnable() {
        public void run() {
            if (mode != 1 || moved || longFired) return;
            longFired = true;
            buzz();
            Session s = link.session;
            if (s == null) return;
            float[] r = touchpad ? new float[] { cx, cy } : toRemote(downX, downY);
            if (!touchpad) { cx = r[0]; cy = r[1]; }
            click(s, 1, r[0], r[1]);
        }
    };

    public RemoteView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = Math.max(vc.getScaledTouchSlop(), Ui.dp(c, 6));
        scrollStep = Ui.dp(c, 26);
        ptr.setStyle(Paint.Style.FILL);
    }

    // ---------- геометрия ----------

    private int fw() { Bitmap b = link.frame; return b == null ? 0 : b.getWidth(); }
    private int fh() { Bitmap b = link.frame; return b == null ? 0 : b.getHeight(); }
    private int sc() { return Math.max(1, link.scale); }

    private float fit() {
        int w = fw(), hh = fh();
        if (w == 0 || hh == 0 || getWidth() == 0) return 1f;
        return Math.min(getWidth() / (float) w, getHeight() / (float) hh);
    }

    /** Сколько пикселей экрана приходится на один пиксель картинки. */
    private float total() { return fit() * zoom; }

    private float[] toRemote(float sx, float sy) {
        float t = total();
        return new float[] { (sx - tx) / t * sc(), (sy - ty) / t * sc() };
    }

    private float[] toScreen(float rx, float ry) {
        float t = total();
        return new float[] { rx / sc() * t + tx, ry / sc() * t + ty };
    }

    private void clampPan() {
        float t = total();
        float sw = fw() * t, shh = fh() * t;
        if (sw <= getWidth()) tx = (getWidth() - sw) / 2f; else tx = Math.max(getWidth() - sw, Math.min(0, tx));
        if (shh <= getHeight()) ty = (getHeight() - shh) / 2f; else ty = Math.max(getHeight() - shh, Math.min(0, ty));
    }

    public void fitToScreen() {
        zoom = 1f;
        tx = ty = 0;
        clampPan();
        notifyZoom();
        invalidate();
    }

    /** Масштаб 1:1 — один пиксель удалённого экрана на один пиксель телефона. */
    public void actualSize() {
        float f = fit();
        if (f <= 0) return;
        float want = 1f / sc();
        zoomAt(getWidth() / 2f, getHeight() / 2f, Math.max(1f, want / f) / zoom);
    }

    public boolean isZoomed() { return zoom > 1.03f; }

    private void notifyZoom() { Events e = events; if (e != null) e.onZoomChanged(); }

    private void zoomAt(float fx, float fy, float factor) {
        float nz = Math.max(1f, Math.min(8f, zoom * factor));
        factor = nz / zoom;
        tx = fx - (fx - tx) * factor;
        ty = fy - (fy - ty) * factor;
        zoom = nz;
        clampPan();
        notifyZoom();
    }

    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        clampPan();
        keepCursorVisible();
    }

    /** Новый кадр или размер экрана. */
    public void frameChanged() {
        clampPan();
        postInvalidateOnAnimation();
    }

    public void hostCursor(int x, int y) {
        if (SystemClock.uptimeMillis() - lastTouchAt < 700 && cursorKnown) return;
        cx = x; cy = y;
        cursorKnown = true;
        postInvalidateOnAnimation();
    }

    public void sync() {
        cx = Link.I.curX; cy = Link.I.curY;
        cursorKnown = Link.I.curVis || cursorKnown;
        clampPan();
        invalidate();
    }

    /** Курсор у края — картинка сама подъезжает, чтобы он оставался на экране. */
    private void keepCursorVisible() {
        if (!isZoomed() && tx >= 0) return;
        float[] s = toScreen(cx, cy);
        float m = Ui.dp(getContext(), 56);
        float dx = 0, dy = 0;
        if (s[0] < m) dx = m - s[0]; else if (s[0] > getWidth() - m) dx = getWidth() - m - s[0];
        if (s[1] < m) dy = m - s[1]; else if (s[1] > getHeight() - m) dy = getHeight() - m - s[1];
        if (dx != 0 || dy != 0) { tx += dx; ty += dy; clampPan(); }
    }

    // ---------- рисование ----------

    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        Bitmap b = link.frame;
        if (b == null) return;
        float t = total();
        c.save();
        c.translate(tx, ty);
        c.scale(t, t);
        bmpPaint.setFilterBitmap(t < 1.01f || t > 1.5f ? true : false);
        c.drawBitmap(b, 0, 0, bmpPaint);
        c.restore();
        if (touchpad && cursorKnown) drawPointer(c);
    }

    private void drawPointer(Canvas c) {
        float[] s = toScreen(cx, cy);
        float u = Ui.dp(getContext(), 1) * ptrScale;
        Path p = new Path();
        p.moveTo(s[0], s[1]);
        p.lineTo(s[0], s[1] + 17 * u);
        p.lineTo(s[0] + 4.5f * u, s[1] + 13 * u);
        p.lineTo(s[0] + 8 * u, s[1] + 20 * u);
        p.lineTo(s[0] + 11 * u, s[1] + 18.5f * u);
        p.lineTo(s[0] + 7.5f * u, s[1] + 11.5f * u);
        p.lineTo(s[0] + 13 * u, s[1] + 11.5f * u);
        p.close();
        ptr.setStyle(Paint.Style.FILL);
        ptr.setColor(Color.WHITE);
        c.drawPath(p, ptr);
        ptr.setStyle(Paint.Style.STROKE);
        ptr.setStrokeWidth(1.6f * u);
        ptr.setColor(Color.BLACK);
        c.drawPath(p, ptr);
    }

    // ---------- действия мыши ----------

    private void buzz() {
        if (!haptics) return;
        try { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Exception ignore) { }
    }

    private int rx(float v) { return Math.max(0, Math.min(Math.max(0, link.remoteW - 1), Math.round(v))); }
    private int ry(float v) { return Math.max(0, Math.min(Math.max(0, link.remoteH - 1), Math.round(v))); }

    private void click(Session s, int button, float x, float y) {
        lastClickAt = SystemClock.uptimeMillis();
        s.mouseMove(rx(x), ry(y));
        s.mouseButton(true, button, rx(x), ry(y));
        s.mouseButton(false, button, rx(x), ry(y));
    }

    /**
     * Нажатие левой кнопки для перетаскивания. Если прямо перед этим был клик (тап, а затем «тянем»),
     * ждём, пока пройдёт время двойного клика: иначе Windows примет это за двойной клик и развернёт окно на весь экран.
     */
    private void pressLeft(Session s) {
        long wait = 560 - (SystemClock.uptimeMillis() - lastClickAt);
        if (wait > 0) s.pause((int) wait);
        s.mouseButton(true, 0, rx(cx), ry(cy));
    }

    private void moveCursor(Session s, float x, float y) {
        cx = Math.max(0, Math.min(Math.max(0, link.remoteW - 1), x));
        cy = Math.max(0, Math.min(Math.max(0, link.remoteH - 1), y));
        cursorKnown = true;
        s.mouseMove(rx(cx), ry(cy));
    }

    // ---------- касания ----------

    public boolean onTouchEvent(MotionEvent e) {
        Session s = link.session;
        lastTouchAt = SystemClock.uptimeMillis();
        Events ev = events;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (ev != null) ev.onUserTouch();
                mode = 1;
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                startT = SystemClock.uptimeMillis();
                moved = false; longFired = false; dragging = false;
                dragArmed = SystemClock.uptimeMillis() - lastTapAt < 320 && Math.hypot(downX - lastTapX, downY - lastTapY) < Ui.dp(getContext(), 44);
                h.postDelayed(longPress, longPressMs);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) startTwo(e, s);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == 1 && e.getPointerCount() == 1) moveOne(e, s);
                else if (mode == 2 && e.getPointerCount() >= 2) moveTwo(e, s);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == 2 && e.getPointerCount() == 2) endTwo(s);
                return true;
            case MotionEvent.ACTION_UP:
                h.removeCallbacks(longPress);
                if (mode == 1) endOne(e, s);
                mode = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                if (dragging && s != null) s.mouseButton(false, 0, rx(cx), ry(cy));
                dragging = false;
                mode = 0;
                return true;
            default:
                return true;
        }
    }

    private void moveOne(MotionEvent e, Session s) {
        float x = e.getX(), y = e.getY();
        if (!moved) {
            if (Math.hypot(x - downX, y - downY) < slop) return;
            moved = true;
            h.removeCallbacks(longPress);
            if (longFired) return;
            if (s != null && dragArmed && touchpad) {
                // «тап и сразу тянем» — перетаскивание (как на тачпаде ноутбука)
                dragging = true;
                buzz();
                pressLeft(s);
            } else if (s != null && !touchpad) {
                float[] r = toRemote(downX, downY);
                moveCursor(s, r[0], r[1]);
                dragging = true;
                pressLeft(s);
            }
            lastX = x; lastY = y;
            return;
        }
        if (longFired) { lastX = x; lastY = y; return; }
        float dx = x - lastX, dy = y - lastY;
        lastX = x; lastY = y;
        if (s == null) return;
        if (touchpad) {
            float dist = (float) Math.hypot(dx, dy);
            float acc = 1f + Math.min(1.3f, dist / Ui.dp(getContext(), 22));       // быстрое движение — дальше, медленное — точнее
            float k = speed * acc / total() * sc();
            moveCursor(s, cx + dx * k, cy + dy * k);
            keepCursorVisible();
        } else {
            float[] r = toRemote(x, y);
            moveCursor(s, r[0], r[1]);
        }
        postInvalidateOnAnimation();
    }

    private void endOne(MotionEvent e, Session s) {
        h.removeCallbacks(longPress);
        if (s == null) return;
        if (dragging) {
            s.mouseButton(false, 0, rx(cx), ry(cy));
            dragging = false;
            return;
        }
        if (!moved && !longFired) {
            if (!touchpad) { float[] r = toRemote(downX, downY); moveCursor(s, r[0], r[1]); }
            click(s, 0, cx, cy);
            lastTapAt = SystemClock.uptimeMillis();
            lastTapX = downX; lastTapY = downY;
            if (haptics) { try { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Exception ignore) { } }
        }
    }

    private void startTwo(MotionEvent e, Session s) {
        h.removeCallbacks(longPress);
        if (dragging && s != null) { s.mouseButton(false, 0, rx(cx), ry(cy)); dragging = false; }
        mode = 2;
        g2Kind = 0; g2Moved = false;
        g2Down = SystemClock.uptimeMillis();
        float x0 = e.getX(0), y0 = e.getY(0), x1 = e.getX(1), y1 = e.getY(1);
        g2StartDist = g2LastDist = (float) Math.hypot(x1 - x0, y1 - y0);
        g2StartCx = g2LastCx = (x0 + x1) / 2f;
        g2StartCy = g2LastCy = (y0 + y1) / 2f;
        wheelAccX = wheelAccY = 0;
    }

    private void moveTwo(MotionEvent e, Session s) {
        float x0 = e.getX(0), y0 = e.getY(0), x1 = e.getX(1), y1 = e.getY(1);
        float d = (float) Math.hypot(x1 - x0, y1 - y0);
        float mx = (x0 + x1) / 2f, my = (y0 + y1) / 2f;
        if (g2Kind == 0) {
            float dd = Math.abs(d - g2StartDist);
            float mm = (float) Math.hypot(mx - g2StartCx, my - g2StartCy);
            if (dd < slop && mm < slop) return;
            g2Moved = true;
            g2Kind = dd > mm * 0.9f ? 1 : 2;
            g2LastDist = d; g2LastCx = mx; g2LastCy = my;
            return;
        }
        if (g2Kind == 1) {
            if (g2LastDist > 0) zoomAt(mx, my, d / g2LastDist);
            // заодно двигаем картинку вслед за пальцами
            tx += mx - g2LastCx; ty += my - g2LastCy;
            clampPan();
            postInvalidateOnAnimation();
        } else {
            float dx = mx - g2LastCx, dy = my - g2LastCy;
            if (panMode || (!touchpad && isZoomed())) {
                tx += dx; ty += dy;
                clampPan();
                postInvalidateOnAnimation();
            } else if (s != null) {
                wheelAccY += dy;
                wheelAccX += dx;
                float sign = invertScroll ? -1f : 1f;
                while (Math.abs(wheelAccY) >= scrollStep) {
                    int step = wheelAccY > 0 ? 1 : -1;
                    s.wheel((int) (120 * step * sign), false, rx(cx), ry(cy));
                    wheelAccY -= step * scrollStep;
                }
                if (Math.abs(wheelAccX) > scrollStep * 2.5f) {
                    int step = wheelAccX > 0 ? 1 : -1;
                    s.wheel((int) (-120 * step * sign), true, rx(cx), ry(cy));
                    wheelAccX = 0;
                }
            }
        }
        g2LastDist = d; g2LastCx = mx; g2LastCy = my;
    }

    private void endTwo(Session s) {
        // быстрое касание двумя пальцами без движения — правый клик
        if (!g2Moved && SystemClock.uptimeMillis() - g2Down < 280 && s != null) {
            if (!touchpad) {
                float[] r = toRemote(g2StartCx, g2StartCy);
                moveCursor(s, r[0], r[1]);
            }
            click(s, 1, cx, cy);
            if (haptics) { try { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); } catch (Exception ignore) { } }
        }
        mode = 0;
    }

    public boolean onGenericMotionEvent(MotionEvent e) {
        // колесо внешней мыши
        if ((e.getSource() & android.view.InputDevice.SOURCE_CLASS_POINTER) != 0 && e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            Session s = link.session;
            if (s != null) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0) s.wheel((int) (120 * v), false, rx(cx), ry(cy));
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    // ---------- клавиатура ----------

    public void showKeyboard() {
        requestFocus();
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) { m.restartInput(this); m.showSoftInput(this, 0); }
    }

    public void hideKeyboard() {
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    public boolean onCheckIsTextEditor() { return true; }

    public InputConnection onCreateInputConnection(EditorInfo out) {
        // без подсказок и автозамены: каждая буква сразу уходит на компьютер
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            private String composing = "";

            public boolean commitText(CharSequence t, int newPos) {
                replaceComposing(t.toString());
                composing = "";
                return true;
            }

            public boolean setComposingText(CharSequence t, int newPos) {
                replaceComposing(t.toString());
                composing = t.toString();
                return true;
            }

            public boolean finishComposingText() { composing = ""; return true; }

            private void replaceComposing(String now) {
                int common = 0;
                int n = Math.min(composing.length(), now.length());
                while (common < n && composing.charAt(common) == now.charAt(common)) common++;
                int del = composing.length() - common;
                for (int i = 0; i < del; i++) Keys.tap(link.session, Keys.VK_BACK);
                Keys.type(link.session, now.substring(common), pasteAll, RemoteView.this);
            }

            public boolean deleteSurroundingText(int before, int after) {
                composing = "";
                for (int i = 0; i < before; i++) Keys.tap(link.session, Keys.VK_BACK);
                for (int i = 0; i < after; i++) Keys.tap(link.session, Keys.VK_DELETE);
                return true;
            }

            public boolean sendKeyEvent(KeyEvent ke) {
                if (ke.getAction() == KeyEvent.ACTION_DOWN) return handleKey(ke.getKeyCode(), ke, true);
                if (ke.getAction() == KeyEvent.ACTION_UP) return handleKey(ke.getKeyCode(), ke, false);
                return true;
            }

            public boolean performEditorAction(int code) { Keys.tap(link.session, Keys.VK_RETURN); return true; }
        };
    }

    private boolean handleKey(int code, KeyEvent ke, boolean down) {
        Session s = link.session;
        if (s == null) return false;
        int vk = Keys.fromAndroid(code);
        if (vk != 0) { Keys.press(s, vk, !down); return true; }
        if (down) {
            int u = ke.getUnicodeChar();
            if (u > 0 && ke.getRepeatCount() == 0) Keys.type(s, new String(Character.toChars(u)), pasteAll, this);
            return true;
        }
        return false;
    }

    public boolean onKeyDown(int code, KeyEvent ke) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(code, ke);
        if (handleKey(code, ke, true)) return true;
        return super.onKeyDown(code, ke);
    }

    public boolean onKeyUp(int code, KeyEvent ke) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyUp(code, ke);
        if (handleKey(code, ke, false)) return true;
        return super.onKeyUp(code, ke);
    }
}
