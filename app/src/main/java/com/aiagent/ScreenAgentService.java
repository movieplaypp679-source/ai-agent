package com.aiagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// Agent ka "screen control": Android ki Accessibility API use karke screenshot leta hai
// aur taps/typing/swipe kar sakta hai, taaki agent kisi bhi app (jaise Godot editor) ko
// khud operate kar sake. User ko Settings > Accessibility mein isko ek baar manually ON
// karna padega - Android security ki wajah se koi app ye permission khud nahi le sakta.
public class ScreenAgentService extends AccessibilityService {

    public static volatile ScreenAgentService instance;
    private final Executor exec = Executors.newSingleThreadExecutor();

    public interface ShotCb {
        void ok(String base64Jpeg);
        void err(String msg);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Hume continuous events nahi chahiye, sirf on-demand screenshot/actions
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /** Current screen ka screenshot leta hai, JPEG base64 (max side 1024px) ke roop mein. */
    public void shoot(final ShotCb cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.err("Screenshot ke liye Android 11 ya usse naya chahiye.");
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        if (hw == null) {
                            cb.err("Screenshot bitmap nahi bana.");
                            return;
                        }
                        Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                        result.getHardwareBuffer().close();
                        hw.recycle();
                        int w = soft.getWidth(), h = soft.getHeight();
                        float scale = Math.min(1f, 1024f / (float) Math.max(w, h));
                        if (scale < 1f) {
                            Bitmap scaled = Bitmap.createScaledBitmap(soft, Math.round(w * scale), Math.round(h * scale), true);
                            soft.recycle();
                            soft = scaled;
                        }
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        soft.compress(Bitmap.CompressFormat.JPEG, 65, bos);
                        soft.recycle();
                        cb.ok(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                    } catch (Exception e) {
                        cb.err("Screenshot process fail: " + e.getMessage());
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    cb.err("Screenshot fail hua (code " + errorCode + "). Dubara try karo.");
                }
            });
        } catch (Exception e) {
            cb.err("Screenshot exception: " + e.getMessage());
        }
    }

    /** Diye gaye point par ek tap gesture bhejta hai. */
    public boolean tapAt(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 80));
        return dispatchGesture(b.build(), null, null);
    }

    /** Ek point se doosre point tak swipe/drag gesture bhejta hai. */
    public boolean swipe(int x1, int y1, int x2, int y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 300));
        return dispatchGesture(b.build(), null, null);
    }

    /** Jis bhi text field par currently focus hai, usme text set karta hai. */
    public boolean typeText(String text) {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) node = findEditable(getRootInActiveWindow());
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        return ok;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
