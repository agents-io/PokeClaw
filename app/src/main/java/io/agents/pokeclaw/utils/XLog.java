// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.util.Log;

public class XLog {
    private static boolean DEBUG = true;

    public static void setDEBUG(boolean debug) {
        DEBUG = debug;
    }

    public static void i(String tag, String msg) {
        AppLogStore.log("I", tag, msg, null);
        if (DEBUG && msg != null) Log.i(tag, msg);
    }

    public static void i(String tag, String msg, Throwable tr) {
        AppLogStore.log("I", tag, msg, tr);
        if (DEBUG) Log.i(tag, msg, tr);
    }

    public static void d(String tag, String msg) {
        if (DEBUG) AppLogStore.log("D", tag, msg, null);
        if (DEBUG && msg != null) Log.d(tag, msg);
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (DEBUG) AppLogStore.log("D", tag, msg, tr);
        if (DEBUG) Log.d(tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        AppLogStore.log("E", tag, msg, null);
        if (msg != null) Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        AppLogStore.log("E", tag, msg, tr);
        Log.e(tag, msg, tr);
    }

    public static void e(String tag, Throwable tr) {
        AppLogStore.log("E", tag, "", tr);
        Log.e(tag, "", tr);
    }

    public static void w(String tag, String msg) {
        AppLogStore.log("W", tag, msg, null);
        if (DEBUG && msg != null) Log.w(tag, msg);
    }

    public static void w(String tag, String msg, Throwable tr) {
        AppLogStore.log("W", tag, msg, tr);
        if (DEBUG) Log.w(tag, msg, tr);
    }

    public static void w(String tag, Throwable tr) {
        AppLogStore.log("W", tag, "", tr);
        if (DEBUG) Log.w(tag, tr);
    }

    public static void v(String tag, String msg) {
        if (DEBUG) AppLogStore.log("V", tag, msg, null);
        if (DEBUG && msg != null) Log.v(tag, msg);
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (DEBUG) AppLogStore.log("V", tag, msg, tr);
        if (DEBUG) Log.v(tag, msg, tr);
    }

    public static void wtf(String tag, String msg) {
        AppLogStore.log("WTF", tag, msg, null);
        if (DEBUG) Log.wtf(tag, msg);
    }

    public static void wtf(String tag, String msg, Throwable tr) {
        AppLogStore.log("WTF", tag, msg, tr);
        if (DEBUG) Log.wtf(tag, msg, tr);
    }

    public static void wtf(String tag, Throwable tr) {
        AppLogStore.log("WTF", tag, "", tr);
        if (DEBUG) Log.wtf(tag, tr);
    }
}
