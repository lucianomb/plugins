package io.flutter.plugins.webviewflutter;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import io.flutter.Log;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugins.webviewflutter.GeneratedAndroidWebView.WebViewFlutterApi;

public class WebViewFlutterApiImpl extends WebViewFlutterApi {

    private final InstanceManager instanceManager;

    public WebViewFlutterApiImpl(
            BinaryMessenger binaryMessenger, InstanceManager instanceManager) {
        super(binaryMessenger);
        this.instanceManager = instanceManager;
    }

    public void onPageDidScroll(WebView webView, Long offsetArg, Reply<Void> callback) {
        onPageDidScroll(
            instanceManager.getInstanceId(webView),
            offsetArg.doubleValue(),
            callback);
    }

    @Override
    public void dispose(Long instanceIdArg, Reply<Void> callback) {
        final Long instanceId = instanceManager.removeInstance(instanceIdArg);
        if (instanceId != null) {
            dispose(instanceId, callback);
        } else {
            callback.reply(null);
        }
    }
}
