package com.aiagent;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

// Agent ka khud ka real browser: user yahan se kisi bhi site par ja ke manually login
// kar sakta hai. Login ka session (cookies) MainActivity ke headless "browse" tool ke
// saath share hota hai, kyunki dono ek hi app ke andar ek hi CookieManager use karte hain.
public class BrowserActivity extends Activity {

	private static final String UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

	private WebView web;
	private EditText addr;
	private ProgressBar bar;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setStatusBarColor(0xFF05060F);
		getWindow().setNavigationBarColor(0xFF05060F);
		setContentView(R.layout.browser);

		web = findViewById(R.id.bweb);
		addr = findViewById(R.id.baddr);
		bar = findViewById(R.id.bbar);
		Button go = findViewById(R.id.bgo);
		Button done = findViewById(R.id.bdone);

		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setUserAgentString(UA);
		s.setSupportZoom(true);
		s.setBuiltInZoomControls(true);
		s.setDisplayZoomControls(false);
		s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

		CookieManager.getInstance().setAcceptCookie(true);
		CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

		web.setWebChromeClient(new WebChromeClient() {
			@Override
			public void onProgressChanged(WebView v, int newProgress) {
				if (bar == null) return;
				bar.setProgress(newProgress);
				bar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
			}
		});
		web.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageFinished(WebView v, String url) {
				addr.setText(url);
				CookieManager.getInstance().flush();
			}
		});

		go.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				load(addr.getText().toString());
			}
		});
		addr.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				load(addr.getText().toString());
				return true;
			}
		});
		done.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				CookieManager.getInstance().flush();
				Toast.makeText(BrowserActivity.this, "Session save ho gaya. Agent ab isi login se pages padh sakta hai.", Toast.LENGTH_LONG).show();
				finish();
			}
		});

		String start = getIntent().getStringExtra("url");
		load(start != null && start.trim().length() > 0 ? start.trim() : "https://www.google.com");
	}

	private void load(String u) {
		u = u == null ? "" : u.trim();
		if (u.length() == 0) return;
		if (u.matches("(?i)^https?://.*")) {
			// already a full URL
		} else if (!u.contains(" ") && u.contains(".") && !u.contains("/")) {
			u = "https://" + u;
		} else if (!u.matches("(?i)^https?://.*")) {
			u = "https://www.google.com/search?q=" + Uri.encode(u);
		}
		addr.setText(u);
		web.loadUrl(u);
	}

	@Override
	public void onBackPressed() {
		if (web != null && web.canGoBack()) web.goBack();
		else super.onBackPressed();
	}

	@Override
	protected void onPause() {
		super.onPause();
		CookieManager.getInstance().flush();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		CookieManager.getInstance().flush();
		if (web != null) web.destroy();
	}
}
