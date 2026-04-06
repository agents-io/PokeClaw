package io.agents.pokeclaw.tool.impl;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;

/**
 * 透明 Activity，用于获取前台焦点以读取剪贴板内容。
 * Android 10+ 限制后台应用读取剪贴板，需要应用处于前台。
 * 剪贴板读取在 onWindowFocusChanged(true) 中执行，确保窗口已获得焦点。
 */
public class ClipboardReaderActivity extends Activity {

    static volatile String clipboardResult;
    static volatile String clipboardError;
    static CountDownLatch latch;

    private boolean hasRead = false;

    static void prepare() {
        clipboardResult = null;
        clipboardError = null;
        latch = new CountDownLatch(1);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !hasRead) {
            hasRead = true;
            readClipboard();
            if (latch != null) {
                latch.countDown();
            }
            finish();
        }
    }

    private void readClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                clipboardError = "Unable to access clipboard service";
            } else if (!clipboard.hasPrimaryClip()) {
                clipboardError = "Clipboard is empty";
            } else {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData == null || clipData.getItemCount() == 0) {
                    clipboardError = "Clipboard is empty";
                } else {
                    CharSequence text = clipData.getItemAt(0).getText();
                    if (text == null || text.length() == 0) {
                        clipboardError = "Clipboard has no text content";
                    } else {
                        clipboardResult = text.toString();
                    }
                }
            }
        } catch (SecurityException e) {
            clipboardError = "No permission to access clipboard";
        } catch (Exception e) {
            clipboardError = "Failed to read clipboard: " + e.getMessage();
        }
    }
}
