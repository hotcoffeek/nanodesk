package com.nanodesk.mobile.net;

/** Типы сообщений протокола NanoDesk (совпадают с ПК-версией). */
public final class Msg {
    private Msg() {}

    public static final int INFO = 1, SCREEN = 2, MOUSE = 3, KEY = 4, CLIP = 5, FILE_BEGIN = 6, FILE_DATA = 7, FILE_END = 8,
            QUALITY = 9, MONITOR = 10, LIST_REQ = 11, LIST_RESP = 12, GET_FILE = 13, BYE = 14, CLIP_FILES = 15, CLIP_ACCEPT = 16,
            CLIP_FILE = 17, CLIP_DATA = 18, CLIP_FILE_END = 19, CLIP_DONE = 20, DENIED = 21, PERMS = 22, NOTE = 23, ACK = 24,
            STATS = 25, POWER = 26, DROP_AT = 27, VIDEO = 28, VIDEO_SETUP = 29, KEY_REQ = 30, DRAW = 31, WALLPAPER = 32,
            CODEC_WHY = 34, CURSOR = 35, RESUME = 36, XFER_CANCEL = 37;

    /** Права, выданные владельцем ПК. */
    public static final int PERM_CONTROL = 1, PERM_CLIP = 2, PERM_FILES = 4;

    /** Куда сложить присланные файлы на удалённом ПК (специальные значения «x» в сообщении DropAt). */
    public static final int DEST_DESKTOP = -2000001, DEST_DOWNLOADS = -2000002, DEST_DOCUMENTS = -2000003;

    /** Приоритеты очередей отправки: управление важнее видео, видео важнее файлов. */
    public static int priority(int type) {
        switch (type) {
            case SCREEN:
            case VIDEO:
                return 1;
            case FILE_BEGIN:
            case FILE_DATA:
            case FILE_END:
            case LIST_REQ:
            case LIST_RESP:
            case GET_FILE:
            case CLIP_FILES:
            case CLIP_ACCEPT:
            case CLIP_FILE:
            case CLIP_DATA:
            case CLIP_FILE_END:
            case CLIP_DONE:
                return 2;
            default:
                return 0;
        }
    }
}
