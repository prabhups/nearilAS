package com.prabhu.nearil;
import android.content.Intent;
import android.net.Uri;

public class MyAppWebViewClient extends android.webkit.WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String url) {
        if(Uri.parse(url).getHost().endsWith("nearil.com")) {
            return false;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        view.getContext().startActivity(intent);
        return true;
    }
}
