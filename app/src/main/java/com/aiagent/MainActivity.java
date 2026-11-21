package com.aiagent;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;
import com.google.mediapipe.tasks.genai.llminference.ProgressListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

	private static final int REQ_AUDIO = 77;
	private static final int REQ_PICK = 78;
	private static final int REQ_TREE = 79;
	private static final String UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

	private WebView web;
	private final ExecutorService pool = Executors.newCachedThreadPool();
	private final Map<String, HttpURLConnection> conns = new ConcurrentHashMap<>();

	private TextToSpeech tts;
	private boolean ttsReady = false;
	private String pendingSpeech = null;
	private String pendingSpeechLang = "en-IN";

	private SpeechRecognizer recognizer;
	private String pendingListenLang = "hi-IN";

	// model downloads
	private final Map<String, Boolean> activeDls = new ConcurrentHashMap<>();
	private final Map<String, String> dlFlags = new ConcurrentHashMap<>();
	private final AtomicInteger activeDl = new AtomicInteger(0);

	// on-device LLM (MediaPipe)
	private final ExecutorService llmExec = Executors.newSingleThreadExecutor();
	private LlmInference llmEngine;
	private String llmLoadedKey = "";
	private int llmCtx = 0;
	private volatile LlmInferenceSession llmSession;
	private volatile String llmCancelId = "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setStatusBarColor(0xFF05060F);
		getWindow().setNavigationBarColor(0xFF05060F);
		setContentView(R.layout.main);

		web = (WebView) findViewById(R.id.web);
		web.setBackgroundColor(0xFF05060F);
		web.setOverScrollMode(View.OVER_SCROLL_NEVER);

		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setAllowFileAccess(true);
		s.setMediaPlaybackRequiresUserGesture(false);
		s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
		s.setTextZoom(100);
		s.setSupportZoom(false);
		s.setBuiltInZoomControls(false);

		web.addJavascriptInterface(new Bridge(), "Android");
		web.setWebChromeClient(new WebChromeClient());
		web.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
				return handleUrl(r.getUrl().toString());
			}

			@Override
			public boolean shouldOverrideUrlLoading(WebView v, String url) {
				return handleUrl(url);
			}
		});
		web.loadUrl("file:///android_asset/index.html");
	}

	private boolean handleUrl(String u) {
		if (u.startsWith("file:///android_asset")) return false;
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
		} catch (Exception ignored) {
		}
		return true;
	}

	private void js(final String code) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (web != null) web.evaluateJavascript(code, null);
			}
		});
	}

	// ---------- JS <-> Java bridge ----------
	private class Bridge {

		@JavascriptInterface
		public void http(final String id, final String method, final String url, final String headers, final String body) {
			pool.execute(new Runnable() {
				@Override
				public void run() {
					doHttp(id, method, url, headers, body);
				}
			});
		}

		@JavascriptInterface
		public void cancel(String id) {
			HttpURLConnection c = conns.remove(id);
			if (c != null) {
				try {
					c.disconnect();
				} catch (Exception ignored) {
				}
			}
		}

		@JavascriptInterface
		public String deviceInfo() {
			try {
				JSONObject o = new JSONObject();
				o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
				o.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
				Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
				if (b != null) {
					int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
					int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
					int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
					o.put("battery_percent", scale > 0 ? (lvl * 100 / scale) : lvl);
					o.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
				}
				ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo ni = cm.getActiveNetworkInfo();
				o.put("network", (ni != null && ni.isConnected()) ? ni.getTypeName() : "offline");
				StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
				long free = fs.getAvailableBytes() / (1024L * 1024L * 1024L);
				o.put("free_storage_gb", free);
				o.put("free_storage_mb", fs.getAvailableBytes() / (1024L * 1024L));
				ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
				ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
				am.getMemoryInfo(mi);
				o.put("ram_mb", mi.totalMem / (1024L * 1024L));
				o.put("locale", Locale.getDefault().toString());
				return o.toString();
			} catch (Exception e) {
				return "{\"error\":\"" + e.getMessage() + "\"}";
			}
		}

		@JavascriptInterface
		public void copy(final String text) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					cm.setPrimaryClip(ClipData.newPlainText("AI Agent", text));
				}
			});
		}

		@JavascriptInterface
		public void share(final String text) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					Intent i = new Intent(Intent.ACTION_SEND);
					i.setType("text/plain");
					i.putExtra(Intent.EXTRA_TEXT, text);
					startActivity(Intent.createChooser(i, "Share"));
				}
			});
		}

		@JavascriptInterface
		public void toast(final String msg) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
				}
			});
		}

		@JavascriptInterface
		public void speak(final String text, final String lang) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					speakNow(text, lang);
				}
			});
		}

		@JavascriptInterface
		public void stopSpeak() {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (tts != null) tts.stop();
					js("window.__tts&&window.__tts('end')");
				}
			});
		}

		@JavascriptInterface
		public void listen(final String lang) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
						pendingListenLang = lang;
						requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
						return;
					}
					startListen(lang);
				}
			});
		}


		// ----- model download manager -----
		@JavascriptInterface
		public void dlStart(final String id, final String url, final String fileName, final String token) {
			if (activeDls.putIfAbsent(id, Boolean.TRUE) != null) return;
			pool.execute(new Runnable() {
				@Override
				public void run() {
					try {
						doDownload(id, url, fileName, token);
					} finally {
						activeDls.remove(id);
					}
				}
			});
		}

		@JavascriptInterface
		public void dlStop(String id, boolean cancel) {
			if (activeDls.containsKey(id)) {
				dlFlags.put(id, cancel ? "cancel" : "pause");
			} else if (cancel) {
				new File(modelsDir(), safeName(id) + ".part").delete();
				File ld = legacyDir();
				if (ld != null) new File(ld, safeName(id) + ".part").delete();
				dl(id, "cancel", 0, 0, 0, "");
			}
		}

		@JavascriptInterface
		public String dlList() {
			JSONArray arr = new JSONArray();
			Set<String> seen = new HashSet<>();
			try {
				listInto(arr, modelsDir(), seen);
				File ld = legacyDir();
				if (ld != null) listInto(arr, ld, seen);
			} catch (Exception ignored) {
			}
			return arr.toString();
		}

		@JavascriptInterface
		public boolean dlDelete(String fileName) {
			final String n = safeName(fileName);
			if (activeDls.containsKey(n)) return false;
			// sirf tab unload karo jab yahi model loaded ho
			llmExec.execute(new Runnable() {
				@Override
				public void run() {
					if (llmLoadedKey.startsWith(n + "|")) closeLlm();
				}
			});
			boolean ok = false;
			new File(modelsDir(), n + ".part").delete();
			if (new File(modelsDir(), n).delete()) ok = true;
			File ld = legacyDir();
			if (ld != null) {
				new File(ld, n + ".part").delete();
				if (new File(ld, n).delete()) ok = true;
			}
			return ok;
		}

		// ----- on-device LLM -----
		@JavascriptInterface
		public void llmLoad(final String id, final String fileName, final String backend, final int ctx) {
			llmExec.execute(new Runnable() {
				@Override
				public void run() {
					doLlmLoad(id, fileName, backend, ctx);
				}
			});
		}

		@JavascriptInterface
		public void llmGenerate(final String id, final String prompt, final float temp, final int topK, final float topP, final int maxNew) {
			llmExec.execute(new Runnable() {
				@Override
				public void run() {
					doLlmGenerate(id, prompt, temp, topK, topP, maxNew);
				}
			});
		}

		@JavascriptInterface
		public void llmStop(String id) {
			llmCancelId = id == null ? "" : id;
			LlmInferenceSession s = llmSession;
			if (s != null) {
				try {
					s.cancelGenerateResponseAsync();
				} catch (Throwable ignored) {
				}
			}
		}

		@JavascriptInterface
		public void llmUnload() {
			llmExec.execute(new Runnable() {
				@Override
				public void run() {
					closeLlm();
				}
			});
		}

		@JavascriptInterface
		public void importModel() {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					try {
						Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
						i.addCategory(Intent.CATEGORY_OPENABLE);
						i.setType("*/*");
						startActivityForResult(i, REQ_PICK);
					} catch (Exception e) {
						Toast.makeText(MainActivity.this, "File picker nahi khula", Toast.LENGTH_SHORT).show();
					}
				}
			});
		}


		// ----- workspace folder (Storage Access Framework) -----
		@JavascriptInterface
		public void fsPick() {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					try {
						Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(i, REQ_TREE);
					} catch (Exception e) {
						Toast.makeText(MainActivity.this, "Folder picker nahi khula", Toast.LENGTH_SHORT).show();
					}
				}
			});
		}

		@JavascriptInterface
		public String fsRoot() {
			return getSharedPreferences("aiagent", MODE_PRIVATE).getString("fsName", "");
		}

		@JavascriptInterface
		public void fsClear() {
			try {
				Uri t = fsTree();
				if (t != null) {
					getContentResolver().releasePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				}
			} catch (Exception ignored) {
			}
			getSharedPreferences("aiagent", MODE_PRIVATE).edit().remove("fsRoot").remove("fsName").apply();
		}

		@JavascriptInterface
		public String fsList(String path) {
			try {
				Uri tree = fsTree();
				if (tree == null) return "ERR:NO_ROOT";
				String[] parts = splitPath(path);
				Uri dir = fsFind(tree, parts, parts.length);
				if (dir == null) return "ERR:Folder nahi mila: " + path;
				JSONArray arr = new JSONArray();
				for (Ent e : fsChildren(tree, dir)) {
					JSONObject o = new JSONObject();
					o.put("name", e.name);
					o.put("dir", e.dir);
					o.put("size", e.size);
					arr.put(o);
				}
				return arr.toString();
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public String fsRead(String path, int maxBytes) {
			InputStream in = null;
			try {
				Uri tree = fsTree();
				if (tree == null) return "ERR:NO_ROOT";
				String[] parts = splitPath(path);
				if (parts.length == 0) return "ERR:File ka path do";
				Uri doc = fsFind(tree, parts, parts.length);
				if (doc == null) return "ERR:File nahi mili: " + path;
				in = getContentResolver().openInputStream(doc);
				if (in == null) return "ERR:File khul nahi payi";
				int cap = maxBytes <= 0 ? 60000 : Math.min(maxBytes, 400000);
				java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int n;
				int zeros = 0;
				boolean trunc = false;
				while ((n = in.read(buf)) != -1) {
					for (int k = 0; k < n; k++) {
						if (buf[k] == 0) zeros++;
					}
					int room = cap - bo.size();
					if (n > room) {
						bo.write(buf, 0, room);
						trunc = true;
						break;
					}
					bo.write(buf, 0, n);
				}
				if (zeros > 8) return "ERR:Ye binary file hai (image/zip/apk jaisi), text ki tarah nahi padh sakte";
				String txt = bo.toString("UTF-8");
				return trunc ? txt + "\n...[file aage bhi hai, 400KB tak hi padhi]" : txt;
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			} finally {
				if (in != null) {
					try {
						in.close();
					} catch (Exception ignored) {
					}
				}
			}
		}

		@JavascriptInterface
		public String fsWrite(String path, String content, boolean append) {
			try {
				Uri tree = fsTree();
				if (tree == null) return "ERR:NO_ROOT";
				String[] parts = splitPath(path);
				if (parts.length == 0) return "ERR:File ka path do";
				Uri parent = fsEnsureDir(tree, parts, parts.length - 1);
				String name = parts[parts.length - 1];
				Uri doc = null;
				for (Ent e : fsChildren(tree, parent)) {
					if (e.name.equals(name)) {
						if (e.dir) return "ERR:" + name + " folder hai, file nahi";
						doc = DocumentsContract.buildDocumentUriUsingTree(tree, e.id);
						break;
					}
				}
				if (doc == null) {
					Uri made = DocumentsContract.createDocument(getContentResolver(), parent, mimeFor(name), name);
					if (made == null) return "ERR:File ban nahi payi";
					doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(made));
				}
				byte[] b = (content == null ? "" : content).getBytes("UTF-8");
				OutputStream os;
				try {
					os = getContentResolver().openOutputStream(doc, append ? "wa" : "wt");
				} catch (Exception ex) {
					os = getContentResolver().openOutputStream(doc, append ? "wa" : "w");
				}
				if (os == null) return "ERR:File likhne ke liye khul nahi payi";
				os.write(b);
				os.flush();
				os.close();
				return "OK:" + b.length;
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public String fsMkdir(String path) {
			try {
				Uri tree = fsTree();
				if (tree == null) return "ERR:NO_ROOT";
				String[] parts = splitPath(path);
				if (parts.length == 0) return "ERR:Folder ka naam do";
				fsEnsureDir(tree, parts, parts.length);
				return "OK";
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public String fsDelete(String path) {
			try {
				Uri tree = fsTree();
				if (tree == null) return "ERR:NO_ROOT";
				String[] parts = splitPath(path);
				if (parts.length == 0) return "ERR:Poora workspace delete nahi kar sakte";
				Uri doc = fsFind(tree, parts, parts.length);
				if (doc == null) return "ERR:Nahi mila: " + path;
				return DocumentsContract.deleteDocument(getContentResolver(), doc) ? "OK" : "ERR:Delete nahi hua";
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public String fsRename(String path, String newName) {
			try {
				Uri tree = fsTree();
				if (tree == null) return "ERR:NO_ROOT";
				String[] parts = splitPath(path);
				if (parts.length == 0) return "ERR:Path do";
				if (newName == null || newName.trim().length() == 0 || newName.indexOf('/') >= 0) return "ERR:Naya naam galat hai";
				Uri doc = fsFind(tree, parts, parts.length);
				if (doc == null) return "ERR:Nahi mila: " + path;
				Uri r = DocumentsContract.renameDocument(getContentResolver(), doc, newName.trim());
				return r != null ? "OK" : "ERR:Rename nahi hua";
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public void fsDownload(final String id, final String url, final String path) {
			fsCancel.remove(id);
			pool.execute(new Runnable() {
				@Override
				public void run() {
					doFsDownload(id, url, path);
				}
			});
		}

		@JavascriptInterface
		public void fsDownloadCancel(String id) {
			fsCancel.put(id, Boolean.TRUE);
		}

		@JavascriptInterface
		public void headInfo(final String id, final String url, final String token) {
			pool.execute(new Runnable() {
				@Override
				public void run() {
					doHead(id, url, token);
				}
			});
		}


		@JavascriptInterface
		public void termRun(final String id, final String cmd) {
			pool.execute(new Runnable() {
				@Override
				public void run() {
					doTerm(id, cmd);
				}
			});
		}

		@JavascriptInterface
		public void fsUnzip(final String id, final String zipPath, final String destPath) {
			pool.execute(new Runnable() {
				@Override
				public void run() {
					doUnzip(id, zipPath, destPath);
				}
			});
		}

		@JavascriptInterface
		public String fsOpen(String path) {
			try {
				Uri tree = fsTree();
				if (tree == null) return "ERR:NO_ROOT";
				String[] parts = splitPath(path);
				if (parts.length == 0) return "ERR:Path do";
				final Uri doc = fsFind(tree, parts, parts.length);
				if (doc == null) return "ERR:Nahi mila: " + path;
				final String name = parts[parts.length - 1];
				final String mime = name.toLowerCase(Locale.ROOT).endsWith(".apk") ? "application/vnd.android.package-archive" : mimeFor(name);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						try {
							Intent i = new Intent(Intent.ACTION_VIEW);
							i.setDataAndType(doc, mime);
							i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
							startActivity(i);
						} catch (Exception e) {
							Toast.makeText(MainActivity.this, "Is file ko kholne wala app nahi mila", Toast.LENGTH_LONG).show();
						}
					}
				});
				return "OK";
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public String saveDownload(String name, String text) {
			try {
				String nm = safeName(name);
				byte[] b = (text == null ? "" : text).getBytes("UTF-8");
				OutputStream os;
				String where;
				if (Build.VERSION.SDK_INT >= 29) {
					android.content.ContentValues v = new android.content.ContentValues();
					v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, nm);
					v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
					v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
					Uri u = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
					if (u == null) throw new java.io.IOException("Downloads folder nahi mila");
					os = getContentResolver().openOutputStream(u);
					where = "Downloads/" + nm;
				} else {
					File f = new File(getExternalFilesDir(null), nm);
					os = new FileOutputStream(f);
					where = f.getAbsolutePath();
				}
				if (os == null) throw new java.io.IOException("File khul nahi payi");
				os.write(b);
				os.flush();
				os.close();
				return where;
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public String saveNotebook() {
			try {
				String name = "Agent1_Builder.ipynb";
				InputStream in = getAssets().open(name);
				OutputStream os;
				String where;
				if (Build.VERSION.SDK_INT >= 29) {
					android.content.ContentValues v = new android.content.ContentValues();
					v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
					v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-ipynb+json");
					v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
					Uri u = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
					if (u == null) throw new java.io.IOException("Downloads folder nahi mila");
					os = getContentResolver().openOutputStream(u);
					where = "Downloads";
				} else {
					File f = new File(getExternalFilesDir(null), name);
					os = new FileOutputStream(f);
					where = f.getAbsolutePath();
				}
				byte[] buf = new byte[16384];
				int n;
				while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
				os.flush();
				os.close();
				in.close();
				return where;
			} catch (Exception e) {
				return "ERR:" + e.getMessage();
			}
		}

		@JavascriptInterface
		public void stopListen() {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (recognizer != null) recognizer.stopListening();
				}
			});
		}
	}

	// ---------- Workspace helpers (SAF) ----------
	private final Map<String, Boolean> fsCancel = new ConcurrentHashMap<>();

	private static class Ent {
		String id;
		String name;
		String mime;
		long size;
		boolean dir;
	}

	private Uri fsTree() {
		String s = getSharedPreferences("aiagent", MODE_PRIVATE).getString("fsRoot", null);
		return s == null ? null : Uri.parse(s);
	}

	private static String[] splitPath(String p) {
		ArrayList<String> out = new ArrayList<String>();
		if (p != null) {
			String[] seg = p.replace('\\', '/').split("/");
			for (String x : seg) {
				String t = x.trim();
				if (t.length() == 0 || t.equals(".")) continue;
				if (t.equals("..")) throw new IllegalArgumentException("Path mein '..' allowed nahi hai");
				out.add(t);
			}
		}
		return out.toArray(new String[0]);
	}

	private Uri fsRootDoc(Uri tree) {
		return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
	}

	private ArrayList<Ent> fsChildren(Uri tree, Uri parentDoc) {
		ArrayList<Ent> list = new ArrayList<Ent>();
		Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDoc));
		Cursor c = null;
		try {
			c = getContentResolver().query(kids, new String[]{
					DocumentsContract.Document.COLUMN_DOCUMENT_ID,
					DocumentsContract.Document.COLUMN_DISPLAY_NAME,
					DocumentsContract.Document.COLUMN_MIME_TYPE,
					DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
			while (c != null && c.moveToNext()) {
				Ent e = new Ent();
				e.id = c.getString(0);
				e.name = c.getString(1);
				e.mime = c.getString(2);
				e.size = c.isNull(3) ? 0 : c.getLong(3);
				e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(e.mime);
				list.add(e);
			}
		} finally {
			if (c != null) c.close();
		}
		return list;
	}

	private Uri fsFind(Uri tree, String[] parts, int upto) {
		Uri cur = fsRootDoc(tree);
		for (int i = 0; i < upto; i++) {
			Ent hit = null;
			for (Ent e : fsChildren(tree, cur)) {
				if (e.name != null && e.name.equals(parts[i])) {
					hit = e;
					break;
				}
			}
			if (hit == null) return null;
			cur = DocumentsContract.buildDocumentUriUsingTree(tree, hit.id);
		}
		return cur;
	}

	private Uri fsEnsureDir(Uri tree, String[] parts, int upto) throws java.io.IOException {
		Uri cur = fsRootDoc(tree);
		for (int i = 0; i < upto; i++) {
			Ent hit = null;
			for (Ent e : fsChildren(tree, cur)) {
				if (e.name != null && e.name.equals(parts[i])) {
					hit = e;
					break;
				}
			}
			if (hit == null) {
				Uri made = DocumentsContract.createDocument(getContentResolver(), cur, DocumentsContract.Document.MIME_TYPE_DIR, parts[i]);
				if (made == null) throw new java.io.IOException("Folder nahi ban paya: " + parts[i]);
				cur = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(made));
			} else {
				if (!hit.dir) throw new java.io.IOException(parts[i] + " file hai, folder nahi");
				cur = DocumentsContract.buildDocumentUriUsingTree(tree, hit.id);
			}
		}
		return cur;
	}

	private static String mimeFor(String name) {
		int d = name.lastIndexOf('.');
		if (d >= 0 && d < name.length() - 1) {
			String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(d + 1).toLowerCase(Locale.ROOT));
			if (m != null) return m;
		}
		return "application/octet-stream";
	}

	private static String nameFromUrl(String url, String disposition) {
		try {
			if (disposition != null) {
				int k = disposition.toLowerCase(Locale.ROOT).indexOf("filename=");
				if (k >= 0) {
					String f = disposition.substring(k + 9).trim();
					int sc = f.indexOf(';');
					if (sc >= 0) f = f.substring(0, sc);
					f = f.replace("\"", "").trim();
					if (f.length() > 0) return safeName(f);
				}
			}
			String path = new URL(url).getPath();
			int sl = path.lastIndexOf('/');
			String last = sl >= 0 ? path.substring(sl + 1) : path;
			last = java.net.URLDecoder.decode(last, "UTF-8");
			if (last.length() > 0) return safeName(last);
		} catch (Exception ignored) {
		}
		return "download.bin";
	}

	private void fsEv(String id, String type, long a, long b, String msg) {
		js("window.__fsdl&&window.__fsdl(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + a + "," + b + "," + JSONObject.quote(msg == null ? "" : msg) + ")");
	}

	private void doFsDownload(String id, String url, String path) {
		HttpURLConnection c = null;
		InputStream in = null;
		OutputStream os = null;
		try {
			Uri tree = fsTree();
			if (tree == null) {
				fsEv(id, "err", 0, 0, "NO_ROOT");
				return;
			}
			String cur = url;
			int code = 0;
			for (int hop = 0; hop < 8; hop++) {
				URL u = new URL(cur);
				String proto = u.getProtocol();
				if (!"http".equals(proto) && !"https".equals(proto)) throw new java.io.IOException("Sirf http/https link allowed hai");
				c = (HttpURLConnection) u.openConnection();
				c.setInstanceFollowRedirects(false);
				c.setConnectTimeout(20000);
				c.setReadTimeout(30000);
				c.setRequestProperty("User-Agent", UA);
				c.setRequestProperty("Accept-Encoding", "identity");
				code = c.getResponseCode();
				if (code >= 300 && code < 400 && code != 304) {
					String loc = c.getHeaderField("Location");
					c.disconnect();
					c = null;
					if (loc == null) throw new java.io.IOException("Redirect aaya par Location nahi mila");
					cur = new URL(u, loc).toString();
					continue;
				}
				break;
			}
			if (c == null) throw new java.io.IOException("Bahut zyada redirects");
			if (code >= 400) throw new java.io.IOException("HTTP " + code);
			long total = c.getContentLengthLong();
			if (total > 2000L * 1024L * 1024L) throw new java.io.IOException("File 2 GB se badi hai");
			String[] parts = splitPath(path);
			boolean dirOnly = path == null || path.trim().length() == 0 || path.trim().endsWith("/");
			String fname;
			Uri parent;
			if (dirOnly) {
				fname = nameFromUrl(cur, c.getHeaderField("Content-Disposition"));
				parent = fsEnsureDir(tree, parts, parts.length);
			} else {
				fname = parts[parts.length - 1];
				parent = fsEnsureDir(tree, parts, parts.length - 1);
			}
			Uri doc = null;
			for (Ent e : fsChildren(tree, parent)) {
				if (e.name != null && e.name.equals(fname)) {
					if (e.dir) throw new java.io.IOException(fname + " folder hai");
					doc = DocumentsContract.buildDocumentUriUsingTree(tree, e.id);
					break;
				}
			}
			if (doc == null) {
				Uri made = DocumentsContract.createDocument(getContentResolver(), parent, mimeFor(fname), fname);
				if (made == null) throw new java.io.IOException("File ban nahi payi");
				doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(made));
			}
			try {
				os = getContentResolver().openOutputStream(doc, "wt");
			} catch (Exception ex) {
				os = getContentResolver().openOutputStream(doc, "w");
			}
			if (os == null) throw new java.io.IOException("File likhne ke liye khul nahi payi");
			in = c.getInputStream();
			byte[] buf = new byte[32768];
			int n;
			long done = 0;
			long lastEv = 0;
			while ((n = in.read(buf)) != -1) {
				if (fsCancel.containsKey(id)) {
					fsCancel.remove(id);
					os.close();
					os = null;
					fsEv(id, "err", done, total, "Cancel kar diya");
					return;
				}
				os.write(buf, 0, n);
				done += n;
				long now = System.currentTimeMillis();
				if (now - lastEv > 500) {
					lastEv = now;
					fsEv(id, "prog", done, total, "");
				}
			}
			os.flush();
			os.close();
			os = null;
			fsEv(id, "done", done, total, fname);
		} catch (Exception e) {
			String m = e.getMessage();
			fsEv(id, "err", 0, 0, e.getClass().getSimpleName() + (m != null ? (": " + m) : ""));
		} finally {
			try {
				if (os != null) os.close();
			} catch (Exception ignored) {
			}
			try {
				if (in != null) in.close();
			} catch (Exception ignored) {
			}
			if (c != null) {
				try {
					c.disconnect();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private static String[] concatPath(String[] a, String[] b) {
		String[] r = new String[a.length + b.length];
		System.arraycopy(a, 0, r, 0, a.length);
		System.arraycopy(b, 0, r, a.length, b.length);
		return r;
	}

	private static String joinPath(String[] p, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append('/');
			sb.append(p[i]);
		}
		return sb.toString();
	}

	private void doTerm(String id, String cmd) {
		Process pr = null;
		try {
			ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
			pb.directory(getFilesDir());
			pb.redirectErrorStream(true);
			pb.environment().put("HOME", getFilesDir().getAbsolutePath());
			pr = pb.start();
			final InputStream is = pr.getInputStream();
			final StringBuilder sb = new StringBuilder();
			Thread rd = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						byte[] b = new byte[4096];
						int n;
						while ((n = is.read(b)) != -1) {
							synchronized (sb) {
								if (sb.length() < 20000) sb.append(new String(b, 0, n, "UTF-8"));
							}
						}
					} catch (Exception ignored) {
					}
				}
			});
			rd.start();
			long end = System.currentTimeMillis() + 60000L;
			int code = -999;
			boolean finished = false;
			while (System.currentTimeMillis() < end) {
				try {
					code = pr.exitValue();
					finished = true;
					break;
				} catch (IllegalThreadStateException e) {
					Thread.sleep(150);
				}
			}
			if (!finished) {
				pr.destroy();
				synchronized (sb) {
					sb.append("\n[60 second timeout: process roka gaya]");
				}
			}
			rd.join(1500);
			String out;
			synchronized (sb) {
				out = sb.toString();
			}
			js("window.__term&&window.__term(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(out) + ")");
		} catch (Exception e) {
			js("window.__term&&window.__term(" + JSONObject.quote(id) + ",-1," + JSONObject.quote("Error: " + e.getMessage()) + ")");
		}
	}

	private void doUnzip(String id, String zipPath, String destPath) {
		InputStream raw = null;
		java.util.zip.ZipInputStream zin = null;
		try {
			Uri tree = fsTree();
			if (tree == null) {
				fsEv(id, "err", 0, 0, "NO_ROOT");
				return;
			}
			String[] zp = splitPath(zipPath);
			Uri zdoc = zp.length == 0 ? null : fsFind(tree, zp, zp.length);
			if (zdoc == null) {
				fsEv(id, "err", 0, 0, "Zip file nahi mili: " + zipPath);
				return;
			}
			String[] dp = splitPath(destPath);
			raw = getContentResolver().openInputStream(zdoc);
			if (raw == null) {
				fsEv(id, "err", 0, 0, "Zip khul nahi payi");
				return;
			}
			zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw));
			java.util.HashMap<String, Uri> dirCache = new java.util.HashMap<String, Uri>();
			java.util.HashMap<String, java.util.HashMap<String, String>> kidCache = new java.util.HashMap<String, java.util.HashMap<String, String>>();
			byte[] buf = new byte[32768];
			int files = 0;
			long bytes = 0;
			long lastEv = 0;
			java.util.zip.ZipEntry ze;
			while ((ze = zin.getNextEntry()) != null) {
				String nm = ze.getName().replace('\\', '/');
				if (nm.startsWith("__MACOSX/") || nm.contains("/__MACOSX/")) continue;
				String[] ep;
				try {
					ep = splitPath(nm);
				} catch (IllegalArgumentException bad) {
					continue;
				}
				if (ep.length == 0) continue;
				if (files >= 4000 || bytes > 1500L * 1024L * 1024L) throw new java.io.IOException("Zip bahut badi hai (limit 4000 files / 1.5 GB)");
				String[] full = concatPath(dp, ep);
				if (ze.isDirectory()) {
					fsEnsureDir(tree, full, full.length);
					continue;
				}
				String parentKey = joinPath(full, full.length - 1);
				Uri parent = dirCache.get(parentKey);
				if (parent == null) {
					parent = fsEnsureDir(tree, full, full.length - 1);
					dirCache.put(parentKey, parent);
				}
				java.util.HashMap<String, String> kids = kidCache.get(parentKey);
				if (kids == null) {
					kids = new java.util.HashMap<String, String>();
					for (Ent en : fsChildren(tree, parent)) {
						if (!en.dir && en.name != null) kids.put(en.name, en.id);
					}
					kidCache.put(parentKey, kids);
				}
				String fname = full[full.length - 1];
				Uri doc;
				String existing = kids.get(fname);
				if (existing != null) {
					doc = DocumentsContract.buildDocumentUriUsingTree(tree, existing);
				} else {
					Uri made = DocumentsContract.createDocument(getContentResolver(), parent, mimeFor(fname), fname);
					if (made == null) continue;
					String did = DocumentsContract.getDocumentId(made);
					kids.put(fname, did);
					doc = DocumentsContract.buildDocumentUriUsingTree(tree, did);
				}
				OutputStream os;
				try {
					os = getContentResolver().openOutputStream(doc, "wt");
				} catch (Exception ex) {
					os = getContentResolver().openOutputStream(doc, "w");
				}
				if (os == null) continue;
				try {
					int n;
					while ((n = zin.read(buf)) > 0) {
						os.write(buf, 0, n);
						bytes += n;
					}
				} finally {
					os.close();
				}
				files++;
				long now = System.currentTimeMillis();
				if (now - lastEv > 600) {
					lastEv = now;
					fsEv(id, "prog", files, 0, "");
				}
			}
			fsEv(id, "done", files, 0, String.valueOf(bytes));
		} catch (Exception ex) {
			String m = ex.getMessage();
			fsEv(id, "err", 0, 0, ex.getClass().getSimpleName() + (m != null ? (": " + m) : ""));
		} finally {
			try {
				if (zin != null) zin.close();
			} catch (Exception ignored) {
			}
			try {
				if (raw != null) raw.close();
			} catch (Exception ignored) {
			}
		}
	}

	private void doHead(String id, String url, String token) {
		HttpURLConnection c = null;
		try {
			String cur = url;
			String origHost = new URL(url).getHost();
			String etag = "";
			int code = 0;
			long size = -1;
			for (int hop = 0; hop < 8; hop++) {
				URL u = new URL(cur);
				c = (HttpURLConnection) u.openConnection();
				c.setRequestMethod("HEAD");
				c.setInstanceFollowRedirects(false);
				c.setConnectTimeout(15000);
				c.setReadTimeout(15000);
				c.setRequestProperty("User-Agent", "AIAgent/2.0 (Android)");
				c.setRequestProperty("Accept-Encoding", "identity");
				if (token != null && token.length() > 0 && u.getHost().equals(origHost)) {
					c.setRequestProperty("Authorization", "Bearer " + token);
				}
				code = c.getResponseCode();
				String le = c.getHeaderField("X-Linked-Etag");
				if (le != null && le.length() > 0) etag = le;
				if (code >= 300 && code < 400 && code != 304) {
					String loc = c.getHeaderField("Location");
					c.disconnect();
					c = null;
					if (loc == null) break;
					cur = new URL(u, loc).toString();
					continue;
				}
				size = c.getContentLengthLong();
				if (etag.length() == 0) {
					String e2 = c.getHeaderField("ETag");
					if (e2 == null) e2 = c.getHeaderField("Last-Modified");
					if (e2 != null) etag = e2;
				}
				break;
			}
			js("window.__head&&window.__head(" + JSONObject.quote(id) + "," + code + "," + size + "," + JSONObject.quote(etag) + ")");
		} catch (Exception e) {
			js("window.__head&&window.__head(" + JSONObject.quote(id) + ",0,-1," + JSONObject.quote("") + ")");
		} finally {
			if (c != null) {
				try {
					c.disconnect();
				} catch (Exception ignored) {
				}
			}
		}
	}

	// ---------- Networking ----------
	private void net(String id, String type, String data) {
		js("window.__net&&window.__net(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + JSONObject.quote(data) + ")");
	}

	private void doHttp(String id, String method, String url, String headers, String body) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(url).openConnection();
			conns.put(id, c);
			c.setRequestMethod(method);
			c.setConnectTimeout(15000);
			c.setReadTimeout(300000);
			c.setInstanceFollowRedirects(true);
			JSONObject h = new JSONObject(headers == null || headers.length() == 0 ? "{}" : headers);
			Iterator<String> it = h.keys();
			while (it.hasNext()) {
				String k = it.next();
				c.setRequestProperty(k, h.getString(k));
			}
			if (c.getRequestProperty("User-Agent") == null) c.setRequestProperty("User-Agent", UA);
			if (body != null && body.length() > 0 && !"GET".equals(method)) {
				c.setDoOutput(true);
				byte[] b = body.getBytes("UTF-8");
				c.setFixedLengthStreamingMode(b.length);
				OutputStream os = c.getOutputStream();
				os.write(b);
				os.flush();
				os.close();
			}
			int code = c.getResponseCode();
			net(id, "start", String.valueOf(code));
			InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
			if (is != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"), 8192);
				String line;
				int total = 0;
				while ((line = r.readLine()) != null) {
					if (!conns.containsKey(id)) break;
					total += line.length();
					if (total > 2000000) break;
					net(id, "chunk", line);
				}
				r.close();
			}
			if (conns.containsKey(id)) net(id, "end", "");
		} catch (Exception e) {
			if (conns.containsKey(id)) {
				String m = e.getMessage();
				net(id, "err", e.getClass().getSimpleName() + (m != null ? (": " + m) : ""));
			}
		} finally {
			conns.remove(id);
			if (c != null) {
				try {
					c.disconnect();
				} catch (Exception ignored) {
				}
			}
		}
	}

	// ---------- Text to speech ----------
	private void speakNow(String text, String lang) {
		pendingSpeech = text;
		pendingSpeechLang = lang;
		if (tts == null) {
			tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
				@Override
				public void onInit(int status) {
					ttsReady = (status == TextToSpeech.SUCCESS);
					if (ttsReady) {
						tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
							@Override
							public void onStart(String utteranceId) {
								js("window.__tts&&window.__tts('start')");
							}

							@Override
							public void onDone(String utteranceId) {
								js("window.__tts&&window.__tts('end')");
							}

							@Override
							public void onError(String utteranceId) {
								js("window.__tts&&window.__tts('end')");
							}
						});
						flushSpeech();
					}
				}
			});
		} else if (ttsReady) {
			flushSpeech();
		}
	}

	private void flushSpeech() {
		if (pendingSpeech == null || tts == null) return;
		String t = pendingSpeech;
		pendingSpeech = null;
		int max = TextToSpeech.getMaxSpeechInputLength() - 100;
		if (t.length() > max) t = t.substring(0, max);
		try {
			tts.setLanguage(Locale.forLanguageTag(pendingSpeechLang));
		} catch (Exception ignored) {
		}
		tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "ai_agent_utt");
	}

	// ---------- Speech to text ----------
	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQ_AUDIO) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				startListen(pendingListenLang);
			} else {
				js("window.__sttState&&window.__sttState('err:permission')");
			}
		}
	}

	private void startListen(String lang) {
		if (!SpeechRecognizer.isRecognitionAvailable(this)) {
			js("window.__sttState&&window.__sttState('err:unavailable')");
			return;
		}
		if (recognizer == null) {
			recognizer = SpeechRecognizer.createSpeechRecognizer(this);
			recognizer.setRecognitionListener(new RecognitionListener() {
				@Override
				public void onReadyForSpeech(Bundle params) {
					js("window.__sttState&&window.__sttState('start')");
				}

				@Override
				public void onBeginningOfSpeech() {
				}

				@Override
				public void onRmsChanged(float rmsdB) {
				}

				@Override
				public void onBufferReceived(byte[] buffer) {
				}

				@Override
				public void onEndOfSpeech() {
				}

				@Override
				public void onError(int error) {
					js("window.__sttState&&window.__sttState('err:" + error + "')");
				}

				@Override
				public void onResults(Bundle results) {
					ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
					String t = (r != null && r.size() > 0) ? r.get(0) : "";
					js("window.__stt&&window.__stt(" + JSONObject.quote(t) + ",true)");
				}

				@Override
				public void onPartialResults(Bundle partial) {
					ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
					if (r != null && r.size() > 0) {
						js("window.__stt&&window.__stt(" + JSONObject.quote(r.get(0)) + ",false)");
					}
				}

				@Override
				public void onEvent(int eventType, Bundle params) {
				}
			});
		}
		Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
		i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
		recognizer.startListening(i);
	}


	// ---------- Model storage + download manager ----------
	private static class FatalDl extends Exception {
		FatalDl(String m) {
			super(m);
		}
	}

	// Models ab internal storage mein rakhe jaate hain (external/FUSE se kaafi tez load hota hai)
	private File modelsDir() {
		File d = new File(getFilesDir(), "models");
		if (!d.exists()) d.mkdirs();
		return d;
	}

	// purane version ke downloads yahan the
	private File legacyDir() {
		try {
			File d = getExternalFilesDir("models");
			return (d != null && d.exists()) ? d : null;
		} catch (Exception e) {
			return null;
		}
	}

	private File findModel(String name) {
		File f = new File(modelsDir(), name);
		if (f.exists() && f.length() > 0) return f;
		File ld = legacyDir();
		if (ld != null) {
			File g = new File(ld, name);
			if (g.exists() && g.length() > 0) return g;
		}
		return null;
	}

	private void listInto(JSONArray arr, File dir, Set<String> seen) throws Exception {
		File[] fl = dir.listFiles();
		if (fl == null) return;
		for (File f : fl) {
			if (!f.isFile()) continue;
			String n = f.getName();
			if (!seen.add(n)) continue;
			boolean part = n.endsWith(".part");
			JSONObject o = new JSONObject();
			o.put("name", part ? n.substring(0, n.length() - 5) : n);
			o.put("size", f.length());
			o.put("partial", part);
			arr.put(o);
		}
	}

	private static String safeName(String n) {
		return n == null ? "" : n.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private void dl(String id, String type, long a, long b, long c, String msg) {
		js("window.__dl&&window.__dl(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + a + "," + b + "," + c + "," + JSONObject.quote(msg == null ? "" : msg) + ")");
	}

	private void keepAwake(final boolean on) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				else if (activeDl.get() <= 0) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			}
		});
	}

	private void doDownload(String id, String url, String fileName, String token) {
		fileName = safeName(fileName);
		File dir = modelsDir();
		File have0 = findModel(fileName);
		dlFlags.remove(id);
		if (have0 != null) {
			dl(id, "done", have0.length(), have0.length(), 0, "");
			return;
		}
		// purana adhura download legacy folder mein ho to wahin se resume karo
		File ld0 = legacyDir();
		if (ld0 != null && new File(ld0, fileName + ".part").exists() && !new File(dir, fileName + ".part").exists()) dir = ld0;
		File fin = new File(dir, fileName);
		File part = new File(dir, fileName + ".part");
		String origHost;
		try {
			origHost = new URL(url).getHost();
		} catch (Exception e) {
			dl(id, "err", 0, 0, 0, "URL galat hai");
			return;
		}
		activeDl.incrementAndGet();
		keepAwake(true);
		PowerManager.WakeLock wl = null;
		try {
			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aiagent:download");
			wl.acquire(4L * 60L * 60L * 1000L);
		} catch (Exception ignored) {
		}
		long total = -1;
		int fails = 0;
		boolean finished = false;
		try {
			while (!finished) {
				if (dlFlags.get(id) != null) break;
				long have = part.exists() ? part.length() : 0;
				HttpURLConnection c = null;
				try {
					String cur = url;
					int code = 0;
					for (int hop = 0; hop < 10; hop++) {
						URL u = new URL(cur);
						c = (HttpURLConnection) u.openConnection();
						c.setInstanceFollowRedirects(false);
						c.setConnectTimeout(20000);
						c.setReadTimeout(30000);
						c.setRequestProperty("User-Agent", "AIAgent/1.0 (Android)");
						c.setRequestProperty("Accept-Encoding", "identity");
						if (token != null && token.length() > 0 && u.getHost().equals(origHost)) {
							c.setRequestProperty("Authorization", "Bearer " + token);
						}
						if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
						code = c.getResponseCode();
						if (code >= 300 && code < 400 && code != 304) {
							String loc = c.getHeaderField("Location");
							c.disconnect();
							c = null;
							if (loc == null) throw new FatalDl("Redirect aaya par Location nahi mila");
							cur = new URL(u, loc).toString();
							continue;
						}
						break;
					}
					if (c == null) throw new FatalDl("Bahut zyada redirects");
					if (code == 401 || code == 403) {
						throw new FatalDl("Access denied (" + code + "). Gated model hai: Hugging Face token daalo aur model page par license accept karo.");
					}
					if (code == 404) throw new FatalDl("File nahi mili (404). Link galat hai ya hata diya gaya.");
					if (code == 416) {
						part.delete();
						continue;
					}
					if (code != 200 && code != 206) throw new java.io.IOException("HTTP " + code);

					long len = c.getContentLengthLong();
					boolean resumed = (code == 206 && have > 0);
					if (!resumed) have = 0;
					long tot = -1;
					if (code == 206) {
						String cr = c.getHeaderField("Content-Range");
						if (cr != null && cr.indexOf('/') > 0) {
							try {
								tot = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
							} catch (Exception ignored) {
							}
						}
						if (tot < 0 && len >= 0) tot = have + len;
					} else if (len >= 0) {
						tot = len;
					}
					total = tot;
					if (total > 0) {
						StatFs fs = new StatFs(dir.getPath());
						long need = (total - have) + 64L * 1024L * 1024L;
						if (fs.getAvailableBytes() < need) {
							throw new FatalDl("Storage kam hai. Kam se kam " + (need / 1048576L) + " MB free chahiye.");
						}
					}
					dl(id, "start", have, Math.max(total, 0), 0, "");

					InputStream in = c.getInputStream();
					FileOutputStream out = new FileOutputStream(part, resumed);
					try {
						byte[] buf = new byte[65536];
						long done = have;
						long lastT = System.currentTimeMillis();
						long lastB = done;
						int n;
						while ((n = in.read(buf)) != -1) {
							out.write(buf, 0, n);
							done += n;
							fails = 0;
							if (dlFlags.get(id) != null) break;
							long now = System.currentTimeMillis();
							if (now - lastT >= 400) {
								long bps = (done - lastB) * 1000L / Math.max(1L, now - lastT);
								dl(id, "prog", done, Math.max(total, 0), bps, "");
								lastT = now;
								lastB = done;
							}
						}
					} finally {
						try {
							out.flush();
							out.close();
						} catch (Exception ignored) {
						}
						try {
							in.close();
						} catch (Exception ignored) {
						}
					}
					if (dlFlags.get(id) != null) break;
					long size = part.length();
					if (total > 0 && size < total) throw new java.io.IOException("Connection beech mein toot gaya");
					if (total > 0 && size > total) {
						part.delete();
						throw new FatalDl("Size mismatch hua, dobara download karo");
					}
					if (fin.exists()) fin.delete();
					if (!part.renameTo(fin)) throw new FatalDl("File save (rename) nahi hui");
					dl(id, "done", fin.length(), fin.length(), 0, "");
					finished = true;
				} catch (FatalDl e) {
					dl(id, "err", part.exists() ? part.length() : 0, Math.max(total, 0), 0, e.getMessage());
					finished = true;
				} catch (Exception e) {
					if (dlFlags.get(id) != null) break;
					fails++;
					long got = part.exists() ? part.length() : 0;
					if (fails > 6) {
						dl(id, "err", got, Math.max(total, 0), 0, "Network error: " + e.getMessage() + ". Resume dabao.");
						finished = true;
					} else {
						dl(id, "retry", got, Math.max(total, 0), 0, "Network issue, dobara try (" + fails + "/6)");
						try {
							Thread.sleep(Math.min(8000L, 1500L * fails));
						} catch (InterruptedException ie) {
							break;
						}
					}
				} finally {
					if (c != null) {
						try {
							c.disconnect();
						} catch (Exception ignored) {
						}
					}
				}
			}
			String flag = dlFlags.remove(id);
			if (flag != null) {
				if ("cancel".equals(flag)) {
					part.delete();
					dl(id, "cancel", 0, 0, 0, "");
				} else {
					dl(id, "pause", part.exists() ? part.length() : 0, Math.max(total, 0), 0, "");
				}
			}
		} finally {
			activeDl.decrementAndGet();
			keepAwake(false);
			if (wl != null && wl.isHeld()) {
				try {
					wl.release();
				} catch (Exception ignored) {
				}
			}
		}
	}

	// ---------- On-device LLM (MediaPipe LLM Inference) ----------
	private void llmEvt(String id, String type, String data) {
		js("window.__llm&&window.__llm(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + JSONObject.quote(data == null ? "" : data) + ")");
	}

	private void closeLlm() {
		if (llmEngine != null) {
			try {
				llmEngine.close();
			} catch (Throwable ignored) {
			}
		}
		llmEngine = null;
		llmLoadedKey = "";
		llmCtx = 0;
	}

	private void doLlmLoad(String id, String fileName, String backend, int ctx) {
		try {
			long tLoad = System.currentTimeMillis();
			File f = findModel(safeName(fileName));
			if (f == null) {
				llmEvt(id, "loaderr", "Model file nahi mili. Dobara download karo.");
				return;
			}
			String key = f.getName() + "|" + backend + "|" + ctx;
			if (llmEngine != null && key.equals(llmLoadedKey)) {
				llmEvt(id, "loaded", "0");
				return;
			}
			closeLlm();
			ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
			ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
			am.getMemoryInfo(mi);
			if (f.length() > (long) (mi.totalMem * 0.6)) {
				llmEvt(id, "loaderr", "Phone ki RAM (" + (mi.totalMem / 1048576L) + " MB) is model ke liye kam hai. Chhota model chuno.");
				return;
			}
			boolean wantGpu = "gpu".equals(backend);
			String err = "unknown error";
			for (int pass = 0; pass < (wantGpu ? 2 : 1); pass++) {
				boolean gpu = wantGpu && pass == 0;
				try {
					LlmInference.LlmInferenceOptions o = LlmInference.LlmInferenceOptions.builder()
							.setModelPath(f.getAbsolutePath())
							.setMaxTokens(ctx)
							.setMaxTopK(64)
							.setPreferredBackend(gpu ? LlmInference.Backend.GPU : LlmInference.Backend.CPU)
							.build();
					llmEngine = LlmInference.createFromOptions(MainActivity.this, o);
					llmLoadedKey = key;
					llmCtx = ctx;
					break;
				} catch (Throwable t) {
					llmEngine = null;
					err = t.getClass().getSimpleName() + ": " + t.getMessage();
				}
			}
			if (llmEngine == null) llmEvt(id, "loaderr", err);
			else llmEvt(id, "loaded", String.valueOf(System.currentTimeMillis() - tLoad));
		} catch (Throwable t) {
			llmEvt(id, "loaderr", t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	private void llmStat(String id, int cnt, long t0, long tFirst) {
		if (cnt <= 0 || tFirst <= 0) return;
		long now = System.currentTimeMillis();
		llmEvt(id, "stat", cnt + "," + (tFirst - t0) + "," + Math.max(1L, now - tFirst));
	}

	private void doLlmGenerate(final String id, String prompt, float temp, int topK, float topP, int maxNew) {
		if (id.equals(llmCancelId)) {
			llmEvt(id, "end", "");
			return;
		}
		LlmInferenceSession s = null;
		final int cap = Math.max(32, maxNew);
		final int[] cnt = {0};
		final boolean[] capped = {false};
		final long[] tFirst = {0L};
		final long t0 = System.currentTimeMillis();
		try {
			if (llmEngine == null) {
				llmEvt(id, "err", "Model load nahi hai");
				return;
			}
			int n = llmEngine.sizeInTokens(prompt);
			if (n + 16 >= llmCtx) {
				llmEvt(id, "err", "ctx:" + n + ":" + llmCtx);
				return;
			}
			LlmInferenceSession.LlmInferenceSessionOptions so = LlmInferenceSession.LlmInferenceSessionOptions.builder()
					.setTopK(Math.max(1, Math.min(topK, 64)))
					.setTopP(topP)
					.setTemperature(temp)
					.setRandomSeed((int) (System.nanoTime() & 0x7fffffffL))
					.build();
			s = LlmInferenceSession.createFromOptions(llmEngine, so);
			llmSession = s;
			s.addQueryChunk(prompt);
			ListenableFuture<String> fut = s.generateResponseAsync(new ProgressListener<String>() {
				@Override
				public void run(String part, boolean done) {
					if (part != null && part.length() > 0) {
						if (tFirst[0] == 0L) tFirst[0] = System.currentTimeMillis();
						cnt[0]++;
						llmEvt(id, "tok", part);
						// reply bahut lamba ho raha ho to rok do (speed + battery)
						if (cnt[0] >= cap && !capped[0]) {
							capped[0] = true;
							LlmInferenceSession ss = llmSession;
							if (ss != null) {
								try {
									ss.cancelGenerateResponseAsync();
								} catch (Throwable ignored) {
								}
							}
						}
					}
				}
			});
			fut.get();
			llmStat(id, cnt[0], t0, tFirst[0]);
			llmEvt(id, "end", "");
		} catch (Throwable t) {
			if (capped[0] || id.equals(llmCancelId)) {
				llmStat(id, cnt[0], t0, tFirst[0]);
				llmEvt(id, "end", "");
			} else {
				Throwable c = t.getCause() != null ? t.getCause() : t;
				llmEvt(id, "err", c.getClass().getSimpleName() + ": " + c.getMessage());
			}
		} finally {
			llmSession = null;
			if (s != null) {
				try {
					s.close();
				} catch (Throwable ignored) {
				}
			}
		}
	}

	// ---------- Lifecycle ----------
	@Override
	public void onBackPressed() {
		web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'", new android.webkit.ValueCallback<String>() {
			@Override
			public void onReceiveValue(String v) {
				if (v == null || !v.contains("1")) finish();
			}
		});
	}

	// ---------- Import own model file from phone storage ----------
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQ_TREE) {
			if (resultCode == RESULT_OK && data != null && data.getData() != null) {
				Uri t = data.getData();
				try {
					getContentResolver().takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				} catch (Exception ignored) {
				}
				String nm = "Workspace";
				try {
					String tid = DocumentsContract.getTreeDocumentId(t);
					int col = tid.indexOf(':');
					nm = col >= 0 && col < tid.length() - 1 ? tid.substring(col + 1) : tid;
				} catch (Exception ignored) {
				}
				getSharedPreferences("aiagent", MODE_PRIVATE).edit().putString("fsRoot", t.toString()).putString("fsName", nm).apply();
				js("window.__fsRoot&&window.__fsRoot(" + JSONObject.quote(nm) + ")");
			}
			return;
		}
		if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
		final Uri uri = data.getData();
		String nm = null;
		long sz = 0;
		Cursor c = null;
		try {
			c = getContentResolver().query(uri, null, null, null, null);
			if (c != null && c.moveToFirst()) {
				int a = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				int b = c.getColumnIndex(OpenableColumns.SIZE);
				if (a >= 0) nm = c.getString(a);
				if (b >= 0) sz = c.getLong(b);
			}
		} catch (Exception ignored) {
		} finally {
			if (c != null) c.close();
		}
		if (nm == null) nm = uri.getLastPathSegment() == null ? "model.task" : uri.getLastPathSegment();
		final String name = safeName(nm);
		final long size = sz;
		if (!name.toLowerCase(Locale.ROOT).endsWith(".task")) {
			Toast.makeText(this, "Sirf MediaPipe .task file chalti hai", Toast.LENGTH_LONG).show();
			return;
		}
		js("window.__importStart&&window.__importStart(" + JSONObject.quote(name) + "," + size + ")");
		if (activeDls.putIfAbsent(name, Boolean.TRUE) != null) return;
		pool.execute(new Runnable() {
			@Override
			public void run() {
				try {
					doImport(uri, name, size);
				} finally {
					activeDls.remove(name);
				}
			}
		});
	}

	private void doImport(Uri uri, String name, long size) {
		File dir = modelsDir();
		File fin = new File(dir, name);
		File part = new File(dir, name + ".part");
		dlFlags.remove(name);
		if (size > 0) {
			StatFs fs = new StatFs(dir.getPath());
			if (fs.getAvailableBytes() < size + 64L * 1024L * 1024L) {
				dl(name, "err", 0, size, 0, "Storage kam hai. " + ((size / 1048576L) + 64) + " MB free chahiye.");
				return;
			}
		}
		activeDl.incrementAndGet();
		keepAwake(true);
		InputStream in = null;
		FileOutputStream out = null;
		try {
			in = getContentResolver().openInputStream(uri);
			if (in == null) throw new java.io.IOException("File khul nahi payi");
			out = new FileOutputStream(part, false);
			byte[] buf = new byte[262144];
			long done = 0, lastT = System.currentTimeMillis(), lastB = 0;
			int n;
			dl(name, "start", 0, size, 0, "");
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
				done += n;
				if (dlFlags.get(name) != null) break;
				long now = System.currentTimeMillis();
				if (now - lastT >= 400) {
					dl(name, "prog", done, size, (done - lastB) * 1000L / Math.max(1L, now - lastT), "");
					lastT = now;
					lastB = done;
				}
			}
			out.flush();
			out.close();
			out = null;
			String flag = dlFlags.remove(name);
			if (flag != null) {
				part.delete();
				dl(name, "cancel", 0, 0, 0, "");
				return;
			}
			if (fin.exists()) fin.delete();
			if (!part.renameTo(fin)) throw new java.io.IOException("File save (rename) nahi hui");
			dl(name, "done", fin.length(), fin.length(), 0, "");
		} catch (Exception e) {
			part.delete();
			dl(name, "err", 0, size, 0, "Import fail: " + e.getMessage());
		} finally {
			try {
				if (out != null) out.close();
			} catch (Exception ignored) {
			}
			try {
				if (in != null) in.close();
			} catch (Exception ignored) {
			}
			activeDl.decrementAndGet();
			keepAwake(false);
		}
	}

	// phone ki RAM kam pade to background mein model unload (app kill hone se bachata hai)
	@Override
	public void onTrimMemory(int level) {
		super.onTrimMemory(level);
		if (level >= 40 && llmEngine != null) {
			llmExec.execute(new Runnable() {
				@Override
				public void run() {
					closeLlm();
					js("window.__llmReset&&window.__llmReset()");
				}
			});
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		for (HttpURLConnection c : conns.values()) {
			try {
				c.disconnect();
			} catch (Exception ignored) {
			}
		}
		pool.shutdownNow();
		llmExec.shutdownNow();
		closeLlm();
		if (tts != null) {
			tts.stop();
			tts.shutdown();
		}
		if (recognizer != null) recognizer.destroy();
		if (web != null) web.destroy();
	}
}
