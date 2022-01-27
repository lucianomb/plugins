// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.webviewflutter;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
    private String captureFilePath;
    private final static int FILE_CHOOSER_RESULT_CODE = 10000;
    private final static int CAPTURE_RESULT_CODE = 10001;
    private final static int REQUEST_CODE_CAMERA_VIDEO = 20000;
    private final static int REQUEST_CODE_CAMERA_IMAGE = 20001;
    public static final int RESULT_OK = -1;
    public static final int RESULT_CANCELED = 0;

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
      Log.d("File Chooser", "openFileChooser Android  >= 4.1");
      uploadMessage = valueCallback;
      if (!"camera".equals(capture) && !"*".equals(capture)) {
        showChooser(false, acceptType);
        return;
      }
      if (acceptType.startsWith("image/")) {
        takePhoto();
        return;
      }
      if (acceptType.startsWith("video/")) {
        recordVideo();
      }
    }

    // For Android >= 5.0
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
      Log.d("File Chooser", "onShowFileChooser Android >= 5.0");
      uploadMessageAboveL = filePathCallback;
      if (!fileChooserParams.isCaptureEnabled()) {
        String[] acceptTypes = fileChooserParams.getAcceptTypes();
        String acceptType = "*/*";
        if (acceptTypes.length > 0) acceptType = acceptTypes[0];
        boolean allowMultiple = fileChooserParams.getMode() == FileChooserParams.MODE_OPEN;
        showChooser(allowMultiple, acceptType);
        return true;
      }
      String[] acceptTypes = fileChooserParams.getAcceptTypes();
      if (acceptTypes.length > 0) {
        if (acceptTypes[0].startsWith("image/")) {
          takePhoto();
          return true;
        }
        if (acceptTypes[0].startsWith("video/")) {
          recordVideo();
          return true;
        }
      }
      return false;
    }

    private void showChooser(boolean allowMultiple, String acceptType) {
      Log.d("File Chooser", "showChooser");
      if ("image/*".equals(acceptType) || "video/*".equals(acceptType)) {
        showMediaChooser(allowMultiple, acceptType);
      } else {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        intent.setType(acceptType);
        Intent chooser = Intent.createChooser(intent, "File Chooser");
        if (activity != null) {
          activity.startActivityForResult(chooser, FILE_CHOOSER_RESULT_CODE);
        } else {
          Log.d("File Chooser", "activity is null");
        }
      }
    }

    private void showMediaChooser(boolean allowMultiple, String acceptType) {
      Log.d("File Chooser", "showMediaChooser");
      Intent intent = new Intent(Intent.ACTION_PICK, null);
      intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, acceptType);
      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
      Intent chooser = Intent.createChooser(intent, "File Chooser");
      if (activity != null) {
        activity.startActivityForResult(chooser, FILE_CHOOSER_RESULT_CODE);
      } else {
        Log.d("File Chooser", "activity is null");
      }
    }

    private void recordVideo() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        boolean granted = ContextCompat.checkSelfPermission(activity.getBaseContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
          startCapture(MediaStore.ACTION_VIDEO_CAPTURE, "MP4_", ".mp4");
        } else {
          String[] permissions = new String[]{Manifest.permission.CAMERA};
          ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE_CAMERA_VIDEO);
        }
      } else {
        startCapture(MediaStore.ACTION_VIDEO_CAPTURE, "MP4_", ".mp4");
      }
    }

    private void takePhoto() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        boolean granted = ContextCompat.checkSelfPermission(activity.getBaseContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
          startCapture(MediaStore.ACTION_IMAGE_CAPTURE, "JPEG", ".jpg");
        } else {
          String[] permissions = new String[]{Manifest.permission.CAMERA};
          ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE_CAMERA_IMAGE);
        }
      } else {
        startCapture(MediaStore.ACTION_IMAGE_CAPTURE, "JPEG", ".jpg");
      }
    }

    private void startCapture(String action, String prefix, String suffix) {
      Intent captureIntent = new Intent(action);
      captureIntent.putExtra("android.intent.extras.CAMERA_FACING", 0); // 调用后置摄像头
      if (captureIntent.resolveActivity(activity.getPackageManager()) == null) return;
      File captureFile = null;
      try {
        captureFile = createCaptureFile(prefix, suffix);
        captureIntent.putExtra("PhotoPath", captureFilePath);
      } catch (IOException ex) {
        Log.e("TAG", "Unable to create Image File", ex);
      }
      // 适配7.0
      if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
        if (captureFile != null) {
          String authority = activity.getPackageName() + ".webview.fileprovider";
          Uri photoURI = FileProvider.getUriForFile(activity, authority, captureFile);
          captureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        }
      } else {
        if (captureFile != null) {
          captureFilePath = "file:" + captureFile.getAbsolutePath();
          captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(captureFile));
        } else {
          captureIntent = null;
        }
      }
      activity.startActivityForResult(captureIntent, CAPTURE_RESULT_CODE);
    }

    private File createCaptureFile(String prefix, String suffix) throws IOException {
      String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
      String imageFileName = prefix + timeStamp + "_";
      File storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
      File image = File.createTempFile(
              imageFileName,  /* 前缀 */
              suffix,         /* 后缀 */
              storageDir      /* 文件夹 */
      );
      captureFilePath = image.getAbsolutePath();
      return image;
    }

    private void deleteCaptureFile() {
      if (captureFilePath != null) {
        File emptyFile = new File(captureFilePath);
        if (emptyFile.exists()) emptyFile.deleteOnExit();
      }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
      Log.d("File Chooser", "onActivityResult requestCode " + requestCode + "resultCode " + resultCode);
      if (requestCode == CAPTURE_RESULT_CODE) {
        if (resultCode == RESULT_OK) {
          if (null == uploadMessage && null == uploadMessageAboveL) {
            return true;
          }
          File file = new File(captureFilePath);
          if (uploadMessageAboveL != null) {
            Uri[] results = new Uri[1];
            results[0] = Uri.fromFile(file);
            uploadMessageAboveL.onReceiveValue(results);
            uploadMessageAboveL = null;
          } else if (uploadMessage != null) {
            Uri result = Uri.fromFile(file);
            uploadMessage.onReceiveValue(result);
            uploadMessage = null;
          }
          return false;
        } else if (resultCode == RESULT_CANCELED) {
          // 如果相机没有选择或者直接返回，需要给callback设置，否则onShowFileChoose方法不会被调用
          deleteCaptureFile();
          if (uploadMessageAboveL != null) {
            uploadMessageAboveL.onReceiveValue(null);
            uploadMessageAboveL = null;
          }
          if (uploadMessage != null) {
            uploadMessage.onReceiveValue(null);
            uploadMessage = null;
          }
        }
      } else if (requestCode == FILE_CHOOSER_RESULT_CODE) {
        if (null == uploadMessage && null == uploadMessageAboveL) {
          return true;
        }
        Uri result = data == null || resultCode != RESULT_OK ? null : data.getData();
        if (uploadMessageAboveL != null) {
          onActivityResultAboveL(requestCode, resultCode, data);
        } else if (uploadMessage != null && result != null) {
          uploadMessage.onReceiveValue(result);
          uploadMessage = null;
        }
        return false;
      }
      return true;
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
      uploadMessageAboveL.onReceiveValue(results);
      uploadMessageAboveL = null;
    }

    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
      Log.e("webview_flutter", "onRequestPermissionsResult ");
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        if (requestCode == REQUEST_CODE_CAMERA_IMAGE) {
          startCapture(MediaStore.ACTION_IMAGE_CAPTURE, "JPEG", ".jpg");
        } else if (requestCode == REQUEST_CODE_CAMERA_VIDEO) {
          startCapture(MediaStore.ACTION_VIDEO_CAPTURE, "MP4_", ".mp4");
        }
      }
      return true;
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

  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    Log.e("webview_flutter", "onRequestPermissionsResult webChromeClient ");
    if (webChromeClient != null) {
      return webChromeClient.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    return true;
  }
}
