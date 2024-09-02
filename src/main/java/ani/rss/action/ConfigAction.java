package ani.rss.action;

import ani.rss.annotation.Path;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.entity.Result;
import ani.rss.util.ConfigUtil;
import ani.rss.util.TaskUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;

import java.io.IOException;
import java.util.Objects;

@Path("/config")
public class ConfigAction implements BaseAction {

    @Override
    public void doAction(HttpServerRequest req, HttpServerResponse res) throws IOException {
        String method = req.getMethod();
        res.setContentType("application/json; charset=utf-8");
        if (method.equals("GET")) {
            Config clone = ObjectUtil.clone(ConfigUtil.getCONFIG());
            clone.getLogin().setPassword("");
            resultSuccess(clone);
            return;
        }

        if (!method.equals("POST")) {
            return;
        }
        Config config = ConfigUtil.getCONFIG();
        Login login = config.getLogin();
        String password = login.getPassword();
        Integer renameSleep = config.getRenameSleep();
        Integer sleep = config.getSleep();
        BeanUtil.copyProperties(gson.fromJson(req.getBody(), Config.class), config);
        String host = config.getHost();
        if (!ReUtil.contains("http(s*)://", host)) {
            host = "http://" + host;
        }
        config.setHost(host);

        Boolean proxy = ObjectUtil.defaultIfNull(config.getProxy(), false);
        if (proxy) {
            String proxyHost = config.getProxyHost();
            Integer proxyPort = config.getProxyPort();
            if (StrUtil.isBlank(proxyHost) || Objects.isNull(proxyPort)) {
                result(Result.error().setMessage("代理参数不完整"));
                return;
            }
        }
        String loginPassword = config.getLogin().getPassword();
        // 密码未发生修改
        if (StrUtil.isBlank(loginPassword)) {
            config.getLogin().setPassword(password);
        }
        ConfigUtil.sync();
        Integer newRenameSleep = config.getRenameSleep();
        Integer newSleep = config.getSleep();

        // 时间间隔发生改变，重启任务
        if (!Objects.equals(newSleep, sleep) ||
                !Objects.equals(newRenameSleep, renameSleep)) {
            TaskUtil.restart();
        }


        result(Result.success().setMessage("修改成功"));
    }
}
