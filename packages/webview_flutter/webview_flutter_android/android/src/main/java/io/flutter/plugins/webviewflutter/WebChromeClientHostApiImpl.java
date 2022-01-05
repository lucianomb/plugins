// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.webviewflutter;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import io.flutter.plugins.webviewflutter.GeneratedAndroidWebView.WebChromeClientHostApi;

/**
 * Host api implementation for {@link WebChromeClient}.
 *
 * <p>Handles creating {@link WebChromeClient}s that intercommunicate with a paired Dart object.
 */
public class WebChromeClientHostApiImpl implements WebChromeClientHostApi {
  private final InstanceManager instanceManager;
  private final WebChromeClientCreator webChromeClientCreator;
  private final WebChromeClientFlutterApiImpl flutterApi;
  private WebChromeClientImpl webChromeClient;
  private Activity activity;

  /**
   * Implementation of {@link WebChromeClient} that passes arguments of callback methods to Dart.
   */
  public static class WebChromeClientImpl extends WebChromeClient implements Releasable {
    @Nullable private WebChromeClientFlutterApiImpl flutterApi;
    private WebViewClient webViewClient;
    private Activity activity;

    private ValueCallback<Uri> uploadMessage;
    private ValueCallback<Uri[]> uploadMessageAboveL;
    private final static int FILE_CHOOSER_RESULT_CODE = 10000;
    public static final int RESULT_OK = -1;

    /**
     * Creates a {@link WebChromeClient} that passes arguments of callbacks methods to Dart.
     *
     * @param flutterApi handles sending messages to Dart
     * @param webViewClient receives forwarded calls from {@link WebChromeClient#onCreateWindow}
     */
    public WebChromeClientImpl(@NonNull WebChromeClientFlutterApiImpl flutterApi,
                               WebViewClient webViewClient) {
      this.flutterApi = flutterApi;
      this.webViewClient = webViewClient;
    }

    @Override
    public boolean onCreateWindow(
        final WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
      return onCreateWindow(view, resultMsg, new WebView(view.getContext()));
    }

    /**
     * Verifies that a url opened by `Window.open` has a secure url.
     *
     * @param view the WebView from which the request for a new window originated.
     * @param resultMsg the message to send when once a new WebView has been created. resultMsg.obj
     *     is a {@link WebView.WebViewTransport} object. This should be used to transport the new
     *     WebView, by calling WebView.WebViewTransport.setWebView(WebView)
     * @param onCreateWindowWebView the temporary WebView used to verify the url is secure
     * @return this method should return true if the host application will create a new window, in
     *     which case resultMsg should be sent to its target. Otherwise, this method should return
     *     false. Returning false from this method but also sending resultMsg will result in
     *     undefined behavior
     */
    @VisibleForTesting
    boolean onCreateWindow(
        final WebView view, Message resultMsg, @Nullable WebView onCreateWindowWebView) {
      final WebViewClient windowWebViewClient =
          new WebViewClient() {
            @RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public boolean shouldOverrideUrlLoading(
                @NonNull WebView windowWebView, @NonNull WebResourceRequest request) {
              if (!webViewClient.shouldOverrideUrlLoading(view, request)) {
                view.loadUrl(request.getUrl().toString());
              }
              return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView windowWebView, String url) {
              if (!webViewClient.shouldOverrideUrlLoading(view, url)) {
                view.loadUrl(url);
              }
              return true;
            }
          };

      if (onCreateWindowWebView == null) {
        onCreateWindowWebView = new WebView(view.getContext());
      }
      onCreateWindowWebView.setWebViewClient(windowWebViewClient);

      final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
      transport.setWebView(onCreateWindowWebView);
      resultMsg.sendToTarget();

      return true;
    }

    @Override
    public void onProgressChanged(WebView view, int progress) {
      if (flutterApi != null) {
        flutterApi.onProgressChanged(this, view, (long) progress, reply -> {});
      }
    }

    @Override
    public void onReceivedTitle(WebView view, String title) {
      if (flutterApi != null) {
        flutterApi.onReceivedTitle(this, view, title, reply -> {});
      }
    }

    // For Android  >= 4.1
    public void openFileChooser(ValueCallback<Uri> valueCallback, String acceptType, String capture) {
      Log.d("Image Chooser", "openFileChooser Android  >= 4.1");
      uploadMessage = valueCallback;
      openImageChooserActivity(true);
    }

    // For Android >= 5.0
    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
      boolean allowMultiple = true;
      if (Build.VERSION.SDK_INT >= 21) {
        if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN) {
          allowMultiple = false;
        }
      }
      Log.d("Image Chooser", "openFileChooser Android >= 5.0");
      uploadMessageAboveL = filePathCallback;
      openImageChooserActivity(allowMultiple);
      return true;
    }

    private void openImageChooserActivity(boolean allowMultiple) {
      Log.d("Image Chooser", "openImageChooserActivity");
      Intent intent = new Intent(Intent.ACTION_PICK, null);
      intent.setDataAndType(
              MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
      if (allowMultiple && Build.VERSION.SDK_INT >= 21) {
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      }
      Intent chooser = new Intent(Intent.ACTION_CHOOSER);
      chooser.putExtra(Intent.EXTRA_TITLE, "选择图片");
      chooser.putExtra(Intent.EXTRA_INTENT, intent);

      if (activity != null) {
        activity.startActivityForResult(chooser, FILE_CHOOSER_RESULT_CODE);
      } else {
        Log.d("Image Chooser", "activity is null");
      }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
      Log.d("Image Chooser", "onActivityResult requestCode " + requestCode + "resultCode " + resultCode);
      if (requestCode == FILE_CHOOSER_RESULT_CODE) {
        if (null == uploadMessage && null == uploadMessageAboveL) {
          return false;
        }
        Uri result = data == null || resultCode != RESULT_OK ? null : data.getData();
        if (uploadMessageAboveL != null) {
          onActivityResultAboveL(requestCode, resultCode, data);
        } else if (uploadMessage != null && result != null) {
          uploadMessage.onReceiveValue(result);
          uploadMessage = null;
        }
      }
      return false;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void onActivityResultAboveL(int requestCode, int resultCode, Intent intent) {
      if (requestCode != FILE_CHOOSER_RESULT_CODE || uploadMessageAboveL == null) {
        return;
      }
      Uri[] results = null;
      if (resultCode == Activity.RESULT_OK) {
        if (intent != null) {
          String dataString = intent.getDataString();
          ClipData clipData = intent.getClipData();
          if (clipData != null) {
            results = new Uri[clipData.getItemCount()];
            for (int i = 0; i < clipData.getItemCount(); i++) {
              ClipData.Item item = clipData.getItemAt(i);
              results[i] = item.getUri();
            }
          }
          if (dataString != null)
          {
            results = new Uri[]{Uri.parse(dataString)};
          }
        }
      }
//      Log.d("Image Chooser", "onActivityResultAboveL: " + results.length);
      uploadMessageAboveL.onReceiveValue(results);
      uploadMessageAboveL = null;
    }

    /**
     * Set the {@link WebViewClient} that calls to {@link WebChromeClient#onCreateWindow} are passed
     * to.
     *
     * @param webViewClient the forwarding {@link WebViewClient}
     */
    public void setWebViewClient(WebViewClient webViewClient) {
      this.webViewClient = webViewClient;
    }

    @Override
    public void release() {
      if (flutterApi != null) {
        flutterApi.dispose(this, reply -> {});
      }
      flutterApi = null;
    }
  }

  /** Handles creating {@link WebChromeClient}s for a {@link WebChromeClientHostApiImpl}. */
  public static class WebChromeClientCreator {
    /**
     * Creates a {@link DownloadListenerHostApiImpl.DownloadListenerImpl}.
     *
     * @param flutterApi handles sending messages to Dart
     * @param webViewClient receives forwarded calls from {@link WebChromeClient#onCreateWindow}
     * @return the created {@link DownloadListenerHostApiImpl.DownloadListenerImpl}
     */
    public WebChromeClientImpl createWebChromeClient(
        WebChromeClientFlutterApiImpl flutterApi, WebViewClient webViewClient) {
      return new WebChromeClientImpl(flutterApi, webViewClient);
    }
  }

  /**
   * Creates a host API that handles creating {@link WebChromeClient}s.
   *
   * @param instanceManager maintains instances stored to communicate with Dart objects
   * @param webChromeClientCreator handles creating {@link WebChromeClient}s
   * @param flutterApi handles sending messages to Dart
   */
  public WebChromeClientHostApiImpl(
      InstanceManager instanceManager,
      WebChromeClientCreator webChromeClientCreator,
      WebChromeClientFlutterApiImpl flutterApi) {
    this.instanceManager = instanceManager;
    this.webChromeClientCreator = webChromeClientCreator;
    this.flutterApi = flutterApi;
  }

  @Override
  public void create(Long instanceId, Long webViewClientInstanceId) {
    final WebViewClient webViewClient =
        (WebViewClient) instanceManager.getInstance(webViewClientInstanceId);
    webChromeClient =
        webChromeClientCreator.createWebChromeClient(flutterApi, webViewClient);
    webChromeClient.activity = activity;
    instanceManager.addInstance(webChromeClient, instanceId);
  }

  /**
   * Sets the activity to construct
   *
   * @param activity the new activity.
   */
  public void setActivity(Activity activity) {
    this.activity = activity;
  }

  public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
    Log.e("webview_flutter", "onActivityResult webChromeClient ");
    if (webChromeClient != null) {
      return webChromeClient.onActivityResult(requestCode, resultCode, data);
    }
    return true;
  }
}
