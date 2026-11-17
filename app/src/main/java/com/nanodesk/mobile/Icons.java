package com.nanodesk.mobile;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Простые значки, нарисованные кодом: не нужны ни картинки, ни шрифты с символами. */
public final class Icons {
    private Icons() {}

    public static final int KEYBOARD = 1, KEYS = 2, PAD = 3, TOUCH = 4, FOLDER = 5, MONITOR = 6, CLOSE = 7, SLIDERS = 8, FIT = 9,
            PAN = 10, BACK = 11, MORE = 12, SCAN = 13, PLUS = 14, TRASH = 15, MOON = 16, PC = 17, CLIP = 18, DOWN = 19, UP = 20, PLAY = 21;

    /** Рисует значок в квадрате (cx, cy, size) цветом из paint. */
    public static void draw(Canvas c, int kind, float cx, float cy, float s, Paint p) {
        float u = s / 24f;
        float x0 = cx - s / 2, y0 = cy - s / 2;
        Paint.Style old = p.getStyle();
        float oldW = p.getStrokeWidth();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.6f, 2f * u));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        RectF r = new RectF();
        switch (kind) {
            case KEYBOARD:
                r.set(x0 + 2 * u, y0 + 5 * u, x0 + 22 * u, y0 + 19 * u);
                c.drawRoundRect(r, 3 * u, 3 * u, p);
                for (int i = 0; i < 4; i++) c.drawPoint(x0 + (6 + i * 4) * u, y0 + 9.5f * u, p);
                for (int i = 0; i < 3; i++) c.drawPoint(x0 + (8 + i * 4) * u, y0 + 13 * u, p);
                c.drawLine(x0 + 8 * u, y0 + 16 * u, x0 + 16 * u, y0 + 16 * u, p);
                break;
            case KEYS:
                r.set(x0 + 3 * u, y0 + 4 * u, x0 + 21 * u, y0 + 20 * u);
                c.drawRoundRect(r, 3 * u, 3 * u, p);
                c.drawLine(x0 + 7 * u, y0 + 9 * u, x0 + 10 * u, y0 + 9 * u, p);
                c.drawLine(x0 + 7 * u, y0 + 9 * u, x0 + 7 * u, y0 + 15 * u, p);
                c.drawLine(x0 + 7 * u, y0 + 12 * u, x0 + 9.5f * u, y0 + 12 * u, p);
                c.drawLine(x0 + 13 * u, y0 + 15 * u, x0 + 17 * u, y0 + 9 * u, p);
                break;
            case PAD:
                r.set(x0 + 4 * u, y0 + 3 * u, x0 + 20 * u, y0 + 21 * u);
                c.drawRoundRect(r, 4 * u, 4 * u, p);
                c.drawLine(x0 + 12 * u, y0 + 3 * u, x0 + 12 * u, y0 + 10 * u, p);
                c.drawLine(x0 + 4 * u, y0 + 10 * u, x0 + 20 * u, y0 + 10 * u, p);
                break;
            case TOUCH:
                c.drawCircle(cx, cy, 3.2f * u, p);
                c.drawCircle(cx, cy, 8 * u, p);
                break;
            case FOLDER: {
                Path q = new Path();
                q.moveTo(x0 + 3 * u, y0 + 7 * u);
                q.lineTo(x0 + 3 * u, y0 + 19 * u);
                q.lineTo(x0 + 21 * u, y0 + 19 * u);
                q.lineTo(x0 + 21 * u, y0 + 8 * u);
                q.lineTo(x0 + 12 * u, y0 + 8 * u);
                q.lineTo(x0 + 10 * u, y0 + 5 * u);
                q.lineTo(x0 + 3 * u, y0 + 5 * u);
                q.close();
                c.drawPath(q, p);
                break;
            }
            case MONITOR:
                r.set(x0 + 2 * u, y0 + 4 * u, x0 + 22 * u, y0 + 16 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                c.drawLine(x0 + 8 * u, y0 + 20 * u, x0 + 16 * u, y0 + 20 * u, p);
                c.drawLine(x0 + 12 * u, y0 + 16 * u, x0 + 12 * u, y0 + 20 * u, p);
                break;
            case PC:
                r.set(x0 + 3 * u, y0 + 4 * u, x0 + 21 * u, y0 + 15 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                c.drawLine(x0 + 9 * u, y0 + 19 * u, x0 + 15 * u, y0 + 19 * u, p);
                c.drawLine(x0 + 12 * u, y0 + 15 * u, x0 + 12 * u, y0 + 19 * u, p);
                break;
            case CLOSE:
                c.drawLine(x0 + 6 * u, y0 + 6 * u, x0 + 18 * u, y0 + 18 * u, p);
                c.drawLine(x0 + 18 * u, y0 + 6 * u, x0 + 6 * u, y0 + 18 * u, p);
                break;
            case SLIDERS:
                for (int i = 0; i < 3; i++) {
                    float y = y0 + (6 + i * 6) * u;
                    c.drawLine(x0 + 3 * u, y, x0 + 21 * u, y, p);
                    float kx = x0 + (i == 0 ? 8 : i == 1 ? 16 : 11) * u;
                    Paint.Style st = p.getStyle();
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(kx, y, 2.6f * u, p);
                    p.setStyle(st);
                }
                break;
            case FIT:
                c.drawLine(x0 + 3 * u, y0 + 9 * u, x0 + 3 * u, y0 + 3 * u, p); c.drawLine(x0 + 3 * u, y0 + 3 * u, x0 + 9 * u, y0 + 3 * u, p);
                c.drawLine(x0 + 15 * u, y0 + 3 * u, x0 + 21 * u, y0 + 3 * u, p); c.drawLine(x0 + 21 * u, y0 + 3 * u, x0 + 21 * u, y0 + 9 * u, p);
                c.drawLine(x0 + 21 * u, y0 + 15 * u, x0 + 21 * u, y0 + 21 * u, p); c.drawLine(x0 + 21 * u, y0 + 21 * u, x0 + 15 * u, y0 + 21 * u, p);
                c.drawLine(x0 + 9 * u, y0 + 21 * u, x0 + 3 * u, y0 + 21 * u, p); c.drawLine(x0 + 3 * u, y0 + 21 * u, x0 + 3 * u, y0 + 15 * u, p);
                break;
            case PAN:
                c.drawLine(cx, y0 + 3 * u, cx, y0 + 21 * u, p);
                c.drawLine(x0 + 3 * u, cy, x0 + 21 * u, cy, p);
                c.drawLine(cx - 3 * u, y0 + 6 * u, cx, y0 + 3 * u, p); c.drawLine(cx + 3 * u, y0 + 6 * u, cx, y0 + 3 * u, p);
                c.drawLine(cx - 3 * u, y0 + 18 * u, cx, y0 + 21 * u, p); c.drawLine(cx + 3 * u, y0 + 18 * u, cx, y0 + 21 * u, p);
                c.drawLine(x0 + 6 * u, cy - 3 * u, x0 + 3 * u, cy, p); c.drawLine(x0 + 6 * u, cy + 3 * u, x0 + 3 * u, cy, p);
                c.drawLine(x0 + 18 * u, cy - 3 * u, x0 + 21 * u, cy, p); c.drawLine(x0 + 18 * u, cy + 3 * u, x0 + 21 * u, cy, p);
                break;
            case BACK:
                c.drawLine(x0 + 15 * u, y0 + 5 * u, x0 + 8 * u, y0 + 12 * u, p);
                c.drawLine(x0 + 8 * u, y0 + 12 * u, x0 + 15 * u, y0 + 19 * u, p);
                break;
            case MORE: {
                Paint.Style st = p.getStyle();
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) c.drawCircle(cx, y0 + (5 + i * 7) * u, 1.9f * u, p);
                p.setStyle(st);
                break;
            }
            case SCAN:
                c.drawCircle(cx - 1 * u, cy - 1 * u, 7 * u, p);
                c.drawLine(x0 + 16 * u, y0 + 16 * u, x0 + 21 * u, y0 + 21 * u, p);
                break;
            case PLUS:
                c.drawLine(cx, y0 + 5 * u, cx, y0 + 19 * u, p);
                c.drawLine(x0 + 5 * u, cy, x0 + 19 * u, cy, p);
                break;
            case TRASH:
                c.drawLine(x0 + 4 * u, y0 + 7 * u, x0 + 20 * u, y0 + 7 * u, p);
                c.drawLine(x0 + 9 * u, y0 + 7 * u, x0 + 9 * u, y0 + 4 * u, p);
                c.drawLine(x0 + 9 * u, y0 + 4 * u, x0 + 15 * u, y0 + 4 * u, p);
                c.drawLine(x0 + 15 * u, y0 + 4 * u, x0 + 15 * u, y0 + 7 * u, p);
                r.set(x0 + 6 * u, y0 + 7 * u, x0 + 18 * u, y0 + 21 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                break;
            case MOON: {
                Path q = new Path();
                q.addArc(new RectF(x0 + 4 * u, y0 + 3 * u, x0 + 21 * u, y0 + 20 * u), 40, 270);
                q.quadTo(x0 + 11 * u, y0 + 10 * u, x0 + 17 * u, y0 + 17.5f * u);
                c.drawPath(q, p);
                break;
            }
            case CLIP:
                r.set(x0 + 6 * u, y0 + 5 * u, x0 + 18 * u, y0 + 21 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                r.set(x0 + 9 * u, y0 + 3 * u, x0 + 15 * u, y0 + 7 * u);
                c.drawRoundRect(r, 1.5f * u, 1.5f * u, p);
                break;
            case DOWN:
                c.drawLine(cx, y0 + 4 * u, cx, y0 + 17 * u, p);
                c.drawLine(cx - 5 * u, y0 + 12 * u, cx, y0 + 17 * u, p); c.drawLine(cx + 5 * u, y0 + 12 * u, cx, y0 + 17 * u, p);
                c.drawLine(x0 + 5 * u, y0 + 21 * u, x0 + 19 * u, y0 + 21 * u, p);
                break;
            case UP:
                c.drawLine(cx, y0 + 17 * u, cx, y0 + 4 * u, p);
                c.drawLine(cx - 5 * u, y0 + 9 * u, cx, y0 + 4 * u, p); c.drawLine(cx + 5 * u, y0 + 9 * u, cx, y0 + 4 * u, p);
                c.drawLine(x0 + 5 * u, y0 + 21 * u, x0 + 19 * u, y0 + 21 * u, p);
                break;
            case PLAY: {
                Path q = new Path();
                q.moveTo(x0 + 8 * u, y0 + 5 * u); q.lineTo(x0 + 19 * u, y0 + 12 * u); q.lineTo(x0 + 8 * u, y0 + 19 * u); q.close();
                c.drawPath(q, p);
                break;
            }
            default:
                break;
        }
        p.setStyle(old);
        p.setStrokeWidth(oldW);
    }
}
