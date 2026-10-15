package com.github.catvod.crawler;

/**
 * 新版爬虫 jar 桥基类：XBPQ 等爬虫持有 SpiderApi 字段并调用 log(String) 输出调试日志
 * （常量池扫描实证：jar 内不含该类，宿主必须提供）。随生态新成员出现时按
 * NoSuchMethodError 报错逐步补齐。
 */
public class SpiderApi extends Spider {

    public SpiderApi() {
    }

    public SpiderApi(String siteKey) {
        this.siteKey = siteKey;
    }

    public void log(String message) {
        SpiderDebug.log(message);
    }
}
