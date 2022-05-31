package io.flutter.plugins.webviewflutter;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import java.net.URISyntaxException;

public class WebViewUrlInterceptor {

    public static boolean urlLoading(Activity activity, String url) {
        if (url.startsWith("intent://")) {
            Intent intent = new Intent();
            try {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                activity.startActivity(intent);
            } catch (Exception ex) {
                Uri uri = Uri.parse("market://details?id=" + intent.getPackage());
                intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return false;
            }
            return true;
        }
        if (url.startsWith("market://")) {
            Uri uri = Uri.parse(url);
            Intent intent;
            try {
                intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception ignored) {
            }
            return false;
        }
        return false;
    }
}
