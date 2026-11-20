package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.model.Game;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AssetService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> cache = new LinkedHashMap<>();
    private final List<Game> games = new ArrayList<>();

    @PostConstruct
    public void init() throws Exception {
        String manifest = read("games/manifest.json");
        List<Map<String, String>> entries = mapper.readValue(manifest, new TypeReference<List<Map<String, String>>>() {});
        for (Map<String, String> entry : entries) {
            String html = read("games/" + entry.get("id") + ".html");
            games.add(new Game(entry.get("id"), entry.get("title"), html));
        }
    }

    public String prompt(String name) {
        return read("prompts/" + name + ".txt");
    }

    public String screen(String name) {
        return read("screens/" + name + ".html");
    }

    public String work() {
        return screen("work");
    }

    public List<Game> games() {
        return games;
    }

    public Game pickGame() {
        return games.get(ThreadLocalRandom.current().nextInt(games.size()));
    }

    private String read(String path) {
        synchronized (cache) {
            String value = cache.get(path);
            if (value != null) return value;
        }
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            String value = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            synchronized (cache) {
                cache.put(path, value);
            }
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("资源不存在: " + path, e);
        }
    }
}
