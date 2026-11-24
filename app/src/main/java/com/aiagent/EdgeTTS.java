package com.aiagent;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class EdgeTTS {
    public interface Callback {
        void onSuccess(File mp3File);
        void onError(String error);
    }

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private static String getTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    public static void synthesize(final File cacheDir, final String voiceName, final String text, final int pitchPercent, final int ratePercent, final Callback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String connId = UUID.randomUUID().toString().replace("-", "");
                    String secMsGec = EdgeDrm.generateSecMsGec();
                    String url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
                            + "&ConnectionId=" + connId
                            + "&Sec-MS-GEC=" + secMsGec
                            + "&Sec-MS-GEC-Version=2-143.0.3650.75";

                    Request request = new Request.Builder()
                            .url(url)
                            .addHeader("Pragma", "no-cache")
                            .addHeader("Cache-Control", "no-cache")
                            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                            .addHeader("Sec-WebSocket-Version", "13")
                            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                            .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
                            .addHeader("Accept-Language", "en-US,en;q=0.9")
                            .addHeader("Cookie", "muid=" + EdgeDrm.generateMuid() + ";")
                            .build();

                    final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
                    final boolean[] finished = new boolean[1];

                    client.newWebSocket(request, new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            String ts = getTimestamp();
                            String cfg = "X-Timestamp:" + ts + "\r\n"
                                    + "Content-Type:application/json; charset=utf-8\r\n"
                                    + "Path:speech.config\r\n\r\n"
                                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
                            webSocket.send(cfg);

                            String pitchStr = (pitchPercent >= 0 ? "+" : "") + pitchPercent + "%";
                            String rateStr = (ratePercent >= 0 ? "+" : "") + ratePercent + "%";
                            String ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                                    + "<voice name='" + voiceName + "'>"
                                    + "<prosody pitch='" + pitchStr + "' rate='" + rateStr + "' volume='+0%'>"
                                    + escapeXml(text)
                                    + "</prosody></voice></speak>";

                            String ssmlMsg = "X-RequestId:" + connId + "\r\n"
                                    + "Content-Type:application/ssml+xml\r\n"
                                    + "X-Timestamp:" + ts + "Z\r\n"
                                    + "Path:ssml\r\n\r\n"
                                    + ssmlBody;
                            webSocket.send(ssmlMsg);
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, ByteString bytes) {
                            byte[] b = bytes.toByteArray();
                            if (b.length >= 2) {
                                int headerLen = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                                if (b.length >= 2 + headerLen) {
                                    String headers = new String(b, 2, headerLen, StandardCharsets.UTF_8);
                                    if (headers.contains("Path:audio")) {
                                        int audioOffset = 2 + headerLen;
                                        int audioLen = b.length - audioOffset;
                                        if (audioLen > 0) {
                                            synchronized (audioBuffer) {
                                                audioBuffer.write(b, audioOffset, audioLen);
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, String textMsg) {
                            if (textMsg.contains("Path:turn.end")) {
                                synchronized (finished) {
                                    if (finished[0]) return;
                                    finished[0] = true;
                                }
                                webSocket.close(1000, "Done");
                                try {
                                    byte[] audioData;
                                    synchronized (audioBuffer) {
                                        audioData = audioBuffer.toByteArray();
                                    }
                                    if (audioData.length == 0) {
                                        fallbackToHttp(cacheDir, text, callback, handler);
                                        return;
                                    }
                                    File outFile = new File(cacheDir, "edge_voice_" + connId + ".mp3");
                                    FileOutputStream fos = new FileOutputStream(outFile);
                                    fos.write(audioData);
                                    fos.close();
                                    postSuccess(outFile);
                                } catch (Exception e) {
                                    fallbackToHttp(cacheDir, text, callback, handler);
                                }
                            }
                        }

                        @Override
                        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                            synchronized (finished) {
                                if (finished[0]) return;
                                finished[0] = true;
                            }
                            fallbackToHttp(cacheDir, text, callback, handler);
                        }

                        private void postSuccess(final File file) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onSuccess(file);
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    fallbackToHttp(cacheDir, text, callback, handler);
                }
            }
        }).start();
    }

    private static void fallbackToHttp(final File cacheDir, final String text, final Callback callback, final Handler handler) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String clean = text != null ? text.trim() : "";
                    if (clean.length() > 200) clean = clean.substring(0, 200);
                    String urlStr = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=hi&q=" + URLEncoder.encode(clean, "UTF-8");
                    URL u = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    if (conn.getResponseCode() == 200) {
                        File outFile = new File(cacheDir, "tts_fb_" + System.currentTimeMillis() + ".mp3");
                        InputStream is = conn.getInputStream();
                        FileOutputStream fos = new FileOutputStream(outFile);
                        byte[] buf = new byte[4096];
                        int r;
                        while ((r = is.read(buf)) != -1) {
                            fos.write(buf, 0, r);
                        }
                        fos.close();
                        is.close();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(outFile);
                            }
                        });
                        return;
                    }
                } catch (Exception ignored) {
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError("Voice generation failed");
                    }
                });
            }
        }).start();
    }
}
