package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.util.ConfigUtil;
import ani.rss.util.HttpReq;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class Transmission implements BaseDownload {
    private String host = "";
    private String authorization = "";
    private String sessionId = "";
    private Config config;

    @Override
    public Boolean login(Config config) {
        this.config = config;
        String username = config.getUsername();
        String password = config.getPassword();
        host = config.getHost();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(username)
                || StrUtil.isBlank(password)) {
            log.warn("Transmission 未配置完成");
            return false;
        }

        authorization = StrFormatter.format("Basic {}", Base64.encode(username + ":" + password));
        Boolean isOk = HttpReq.get(host, false)
                .header(Header.AUTHORIZATION, authorization)
                .thenFunction(HttpResponse::isOk);
        if (!isOk) {
            log.error("登录 Transmission 失败");
            return false;
        }
        try {
            getTorrentsInfos();
        } catch (Exception e) {
            log.error("登录 Transmission 失败");
            return false;
        }
        return true;
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        String body = ResourceUtil.readUtf8Str("transmission/torrent-get.json");

        return HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body)
                .thenFunction(res -> {
                    String id = res.header("X-Transmission-Session-Id");
                    if (StrUtil.isNotBlank(id)) {
                        sessionId = id;
                        return getTorrentsInfos();
                    }
                    List<TorrentsInfo> torrentsInfos = new ArrayList<>();
                    JsonObject jsonObject = gson.fromJson(res.body(), JsonObject.class);
                    JsonArray torrents = jsonObject.get("arguments")
                            .getAsJsonObject()
                            .get("torrents")
                            .getAsJsonArray();
                    for (JsonElement jsonElement : torrents.asList()) {
                        JsonObject asJsonObject = jsonElement.getAsJsonObject();
                        List<String> tags = asJsonObject.get("labels").getAsJsonArray()
                                .asList().stream().map(JsonElement::getAsString).collect(Collectors.toList());
                        if (!tags.contains(tag)) {
                            continue;
                        }
                        List<String> files = asJsonObject.get("files").getAsJsonArray().asList()
                                .stream().map(JsonElement::getAsJsonObject)
                                .map(o -> o.get("name").getAsString())
                                .collect(Collectors.toList());

                        TorrentsInfo.State state = asJsonObject.get("isFinished").getAsBoolean() ?
                                TorrentsInfo.State.pausedUP : TorrentsInfo.State.downloading;

                        TorrentsInfo torrentsInfo = new TorrentsInfo();
                        torrentsInfo.setName(asJsonObject.get("name").getAsString());
                        torrentsInfo.setTags(CollUtil.join(tags, ","));
                        torrentsInfo.setHash(asJsonObject.get("hashString").getAsString());
                        torrentsInfo.setState(state);
                        torrentsInfo.setId(asJsonObject.get("id").getAsString());
                        torrentsInfo.setDownloadDir(asJsonObject.get("downloadDir").getAsString());
                        torrentsInfo.setFiles(files);
                        torrentsInfos.add(torrentsInfo);
                    }
                    return torrentsInfos;
                });
    }

    @Override
    public Boolean download(String name, String savePath, File torrentFile, Boolean ova) {
        String body = ResourceUtil.readUtf8Str("transmission/torrent-add.json");
        body = StrFormatter.format(body, tag, savePath, Base64.encode(torrentFile));
        String hash = FileUtil.mainName(torrentFile);

        HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body)
                .then(HttpResponse::isOk);

        Integer renameSleep = config.getRenameSleep();

        List<TorrentsInfo> torrentsInfos = getTorrentsInfos();
        for (int i = 0; i < 10; i++) {
            ThreadUtil.sleep(3000);
            Optional<TorrentsInfo> optionalTorrentsInfo = torrentsInfos
                    .stream()
                    .filter(torrentsInfo -> torrentsInfo.getHash().equals(hash))
                    .findFirst();
            if (optionalTorrentsInfo.isEmpty()) {
                continue;
            }
            if (!ova) {
                renameCache.put(hash, name, renameSleep * (1000 * 60) * 3);
            }
            return true;
        }

        return false;
    }

    @Override
    public void delete(TorrentsInfo torrentsInfo) {
        String body = ResourceUtil.readUtf8Str("transmission/torrent-remove.json");
        body = StrFormatter.format(body, torrentsInfo.getId());
        HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body)
                .then(HttpResponse::isOk);
    }

    @Override
    public void rename(TorrentsInfo torrentsInfo) {
        String id = torrentsInfo.getId();
        String name = torrentsInfo.getName();
        String hash = torrentsInfo.getHash();

        String mainName = FileUtil.mainName(name);

        if (ReUtil.contains("S\\d+E\\d+$", mainName)) {
            return;
        }

        String reName = renameCache.get(hash);
        if (StrUtil.isBlank(reName)) {
            return;
        }

        String extName = FileUtil.extName(name);
        if (StrUtil.isNotBlank(extName)) {
            reName = reName + "." + extName;
        }

        String body = ResourceUtil.readUtf8Str("transmission/torrent-rename-path.json");
        body = StrFormatter.format(body, id, name, reName);

        log.info("重命名 {} ==> {}", name, reName);

        Boolean ok = HttpReq.post(host + "/transmission/rpc", false)
                .header(Header.AUTHORIZATION, authorization)
                .header("X-Transmission-Session-Id", sessionId)
                .body(body)
                .thenFunction(HttpResponse::isOk);
        if (ok) {
            renameCache.remove(hash);
            return;
        }
        log.error("重命名失败 {} ==> {}", name, reName);
    }
}
