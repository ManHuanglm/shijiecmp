package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;

/**
 * 新版爬虫 jar 桥基类（spider 包变体）：新版 XBPQ 等爬虫 extends com.github.catvod.spider.Spider
 * （2026-10-13 easy 系 jar 实例化报 NoClassDefFoundError 后补）。继承 crawler.Spider 保持
 * 运行时反射调用兼容，随生态新成员出现时按 NoSuchMethodError 报错逐步补齐。
 */
public class Spider extends com.github.catvod.crawler.Spider {

    public Spider() {
    }

    public Spider(String siteKey) {
        this.siteKey = siteKey;
    }

    public void log(String message) {
        SpiderDebug.log(message);
    }
}
