package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.config.AppProperties;
import com.bytedance.douyinclouddemo.model.ScreenImage;
import com.bytedance.douyinclouddemo.model.Snippet;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 用容器里的 Chromium CLI 把 snippet HTML 截成 440x260 JPEG。
 * 不依赖 Node/Puppeteer；Chromium 不存在时返回 null，卡片会退回原生 Canvas 预览。
 */
@Service
public class ScreenRenderService {
    private static final String CSP = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:; media-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-src 'none'\"><meta charset=\"utf-8\">";
    private static final int MAX_CACHE = 30;

    private final AppProperties props;
    private final Semaphore lock = new Semaphore(1);
    private final Map<String, ScreenImage> cache = new LinkedHashMap<String, ScreenImage>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ScreenImage> eldest) {
            return size() > MAX_CACHE;
        }
    };

    public ScreenRenderService(AppProperties props) {
        this.props = props;
    }

    public ScreenImage render(Snippet snippet, String mode) {
        if (snippet == null || snippet.getHtml() == null || snippet.getHtml().trim().isEmpty()) return null;
        String key = snippet.getId() + "|" + mode + "|" + snippet.getAt();
        synchronized (cache) {
            ScreenImage cached = cache.get(key);
            if (cached != null) return cached;
        }
        String chrome = chromePath();
        if (chrome == null) {
            System.err.println("[screen] 未找到 Chromium，跳过截图");
            return null;
        }
        if (!lock.tryAcquire()) return null;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("kapu-screen-");
            Path html = dir.resolve("screen.html");
            Path png = dir.resolve("screen.png");
            Path log = dir.resolve("chrome.log");
            Files.write(html, buildHtml(snippet.getHtml()).getBytes(StandardCharsets.UTF_8));

            ProcessBuilder builder = new ProcessBuilder(
                    chrome,
                    "--headless",
                    "--no-sandbox",
                    "--disable-gpu",
                    "--disable-dev-shm-usage",
                    "--hide-scrollbars",
                    "--no-first-run",
                    "--disable-extensions",
                    "--force-device-scale-factor=1",
                    "--window-size=440,260",
                    "--virtual-time-budget=2200",
                    "--run-all-compositor-stages-before-draw",
                    "--user-data-dir=" + dir.resolve("profile"),
                    "--screenshot=" + png.toAbsolutePath(),
                    html.toUri().toString()
            );
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Process process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("[screen] Chromium 超时");
                return null;
            }
            if (process.exitValue() != 0 || !Files.exists(png) || Files.size(png) < 100) {
                System.err.println("[screen] Chromium 失败 exit=" + process.exitValue());
                return null;
            }
            BufferedImage pngImage = ImageIO.read(png.toFile());
            if (pngImage == null) return null;
            byte[] jpeg = toJpeg(pngImage);
            ScreenImage image = new ScreenImage(jpeg, "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
            synchronized (cache) {
                cache.put(key, image);
            }
            return image;
        } catch (Exception e) {
            System.err.println("[screen] " + e.getMessage());
            return null;
        } finally {
            lock.release();
            if (dir != null) {
                try {
                    Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String buildHtml(String markup) {
        return "<!doctype html><html><head>" + CSP
                + "<style>html,body{margin:0;width:440px;height:260px;overflow:hidden}*{box-sizing:border-box}</style>"
                + "</head><body>" + markup + "</body></html>";
    }

    private byte[] toJpeg(BufferedImage source) throws Exception {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private String chromePath() {
        String configured = props.getChromePath();
        if (configured != null && !configured.isEmpty() && Files.isExecutable(Paths.get(configured))) return configured;
        String env = System.getenv("CHROME_PATH");
        if (env != null && !env.isEmpty() && Files.isExecutable(Paths.get(env))) return env;
        String[] candidates = {
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        };
        for (String candidate : candidates) {
            if (Files.isExecutable(Paths.get(candidate))) return candidate;
        }
        return null;
    }
}
