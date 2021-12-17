package io.flutter.plugins.webviewflutter;

import android.content.Context;
import android.webkit.WebView;

class ScrollListenerWebView extends WebView {
    public ScrollListenerWebView(Context context) {
        super(context);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (onScrollChangeListener != null) {
            onScrollChangeListener.onDidScrollCallback(t);
        }
    }

    private OnScrollChangeListener onScrollChangeListener;

    public void setOnScrollChangeListener(OnScrollChangeListener listener) {
        this.onScrollChangeListener = listener;
    }

    public interface OnScrollChangeListener {
        void onDidScrollCallback(int offset);
    }
}
