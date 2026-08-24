package com.bridgelive.callassist;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.HashSet;
import java.util.Set;

public class WhatsAppCallNotificationService extends NotificationListenerService {
    private final Set<String> callKeys = new HashSet<>();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isWhatsApp(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (looksLikeCall(n)) {
            callKeys.add(sbn.getKey());
            WhatsAppOverlayManager.setExternalCallActive(this, true);
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (callKeys.remove(sbn.getKey()) && callKeys.isEmpty())
            WhatsAppOverlayManager.setExternalCallActive(this, false);
    }

    @Override public void onListenerDisconnected() {
        WhatsAppOverlayManager.setExternalCallActive(this, false);
        super.onListenerDisconnected();
    }

    private boolean isWhatsApp(String p) {
        return "com.whatsapp".equals(p) || "com.whatsapp.w4b".equals(p);
    }

    private boolean looksLikeCall(Notification n) {
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE, "");
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT, "");
            String s = (String.valueOf(title) + " " + String.valueOf(text)).toLowerCase();
            return s.contains("call") || s.contains("calling") || s.contains("voice") || s.contains("video");
        }
        return false;
    }
}
