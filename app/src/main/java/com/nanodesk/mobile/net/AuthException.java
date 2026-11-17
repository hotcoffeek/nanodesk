package com.nanodesk.mobile.net;

import java.io.IOException;

/** Пароль не подошёл или хост не подтвердил знание пароля. */
public class AuthException extends IOException {
    public AuthException(String m) { super(m); }
}
