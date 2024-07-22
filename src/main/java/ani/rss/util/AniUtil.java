package ani.rss.util;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AniUtil {

    public static Ani getAni(String url) {
        int season = 1;
        String title = "";

        String s = HttpRequest.get(url)
                .thenFunction(HttpResponse::body);
        Document document = XmlUtil.readXML(s);
        Node channel = document.getElementsByTagName("channel").item(0);
        NodeList childNodes = channel.getChildNodes();

        for (int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node item = childNodes.item(i);
            String nodeName = item.getNodeName();
            if (nodeName.equals("title")) {
                title = ReUtil.replaceAll(item.getTextContent(), "^Mikan Project - ", "");
            }
        }

        String seasonReg = "第(.+)季";
        if (ReUtil.contains(seasonReg, title)) {
            season = Convert.chineseToNumber(ReUtil.get(seasonReg, title, 1));
            title = ReUtil.replaceAll(title, seasonReg, "").trim();
        }

        String bangumiId = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8)
                .get("bangumiId");


        String cover = HttpRequest.get("https://mikanime.tv/Home/Bangumi/" + bangumiId)
                .thenFunction(res -> {
                    org.jsoup.nodes.Document html = Jsoup.parse(res.body());
                    Elements elementsByClass = html.getElementsByClass("bangumi-poster");
                    Element element = elementsByClass.get(0);
                    String style = element.attr("style");
                    String image = style.replace("background-image: url('", "").replace("');", "");
                    return "https://mikanime.tv" + image;
                });

        Ani ani = new Ani();
        ani.setOff(0)
                .setUrl(url.trim())
                .setSeason(season)
                .setTitle(title.trim())
                .setCover(cover)
                .setExclude(List.of("720", "繁"));
        return ani;
    }

    public static List<Item> getItems(Ani ani) {
        String title = ani.getTitle();
        String url = ani.getUrl();
        List<String> exclude = ani.getExclude();

        int off = ani.getOff();
        int season = ani.getSeason();
        List<Item> items = new ArrayList<>();

        String s = HttpRequest.get(url)
                .thenFunction(HttpResponse::body);
        Document document = XmlUtil.readXML(s);
        Node channel = document.getElementsByTagName("channel").item(0);
        NodeList childNodes = channel.getChildNodes();

        int collect = 1 + off;
        for (int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node item = childNodes.item(i);
            String nodeName = item.getNodeName();
            if (!nodeName.equals("item")) {
                continue;
            }
            String itemTitle = "";
            String torrent = "";
            int length = 0;

            NodeList itemChildNodes = item.getChildNodes();
            for (int j = 0; j < itemChildNodes.getLength(); j++) {
                Node itemChild = itemChildNodes.item(j);
                String itemChildNodeName = itemChild.getNodeName();
                if (itemChildNodeName.equals("title")) {
                    itemTitle = itemChild.getTextContent();
                }
                if (itemChildNodeName.equals("enclosure")) {
                    NamedNodeMap attributes = itemChild.getAttributes();
                    torrent = attributes.getNamedItem("url").getNodeValue();
                    length = Integer.parseInt(attributes.getNamedItem("length").getNodeValue());
                }
            }

            // 进行过滤
            String finalItemTitle = itemTitle;
            if (exclude.stream().anyMatch(finalItemTitle::contains)) {
                continue;
            }

            if (!itemTitle.contains(String.valueOf(collect))) {
                collect++;
                continue;
            }
            items.add(
                    new Item()
                            .setTitle(itemTitle)
                            .setTorrent(torrent)
                            .setLength(length)
                            .setCollect(collect)
            );
            collect++;
        }

        for (Item item : items) {
            item.setReName(StrFormatter.format("{} S{}E{}", title, String.format("%02d", season), String.format("%02d", item.getCollect())));
        }

        return items;
    }

}
