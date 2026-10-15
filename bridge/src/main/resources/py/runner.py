# 桌面版 Python Spider 运行器：stdin/stdout JSON 行协议，与 com.fongmi.chaquo.PythonBridge 配对。
import json
import inspect
import os
import sys
import urllib.request
from importlib.machinery import SourceFileLoader

spiders = {}


def resolve_redirect(url):
    req = urllib.request.Request(url, headers={"User-Agent": "okhttp/5.5.0"})
    with urllib.request.urlopen(req) as rsp:
        if 300 <= rsp.status < 400 and "Location" in rsp.headers:
            return resolve_redirect(rsp.headers["Location"])
        return rsp.read()


def download(path, api):
    if api.startswith("http"):
        data = resolve_redirect(api)
    elif os.path.exists(api):
        with open(api, "rb") as f:
            data = f.read()
    else:
        data = str.encode(api)
    with open(path, "wb") as f:
        f.write(data)


def download_module(base_url, name):
    """补链：drpy py 爬虫依赖 base 等公共包（from base.spider import ...），
    从 api 同源目录按相对路径下载（包 __init__.py 与单模块两种形态都尝试）。"""
    rel = name.replace('.', '/')
    for suffix in ("/%s/__init__.py" % rel, "/%s.py" % rel):
        try:
            data = resolve_redirect(base_url + suffix)
            target = os.path.join(cache, *rel.split('/'), os.path.basename(suffix))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, 'wb') as f:
                f.write(data)
        except Exception:
            pass


# base 包最小 stub：源方公共包（base.spider.Spider）在线补链失败时兜底，
# 提供标准方法签名默认实现（子类覆盖后功能可用；避免 ModuleNotFoundError 整源不可用）
BASE_STUB_SPIDER = '''\
class Spider:
    def init(self, extend=""): pass

    def homeContent(self, filter): return {}

    def homeVideoContent(self): return {}

    def categoryContent(self, tid, pg, filter, extend): return {}

    def detailContent(self, ids): return {}

    def searchContent(self, key, quick, pg="1"): return {}

    def searchContentPage(self, key, quick, pg): return {}

    def playerContent(self, flag, id, vipFlags): return {}

    def isVideoFormat(self, url): return False

    def isVideoContent(self, url): return False

    def localProxy(self, param): return [{}]

    def destroy(self): pass
'''


def ensure_base_stub(cache):
    """base 包在线补链失败后的本地兜底：向 cache/base/ 写入最小 Spider 基类。"""
    try:
        __import__("base")
        return
    except ImportError:
        pass
    try:
        base_dir = os.path.join(cache, "base")
        os.makedirs(base_dir, exist_ok=True)
        init_path = os.path.join(base_dir, "__init__.py")
        if not os.path.exists(init_path):
            with open(init_path, "w", encoding="utf-8") as f:
                f.write("")
        spider_path = os.path.join(base_dir, "spider.py")
        if not os.path.exists(spider_path):
            with open(spider_path, "w", encoding="utf-8") as f:
                f.write(BASE_STUB_SPIDER)
    except Exception:
        pass


def load(api, cache, depth=0):
    name = os.path.basename(api).split(".")[0]
    path = os.path.join(cache, os.path.basename(api))
    if api.startswith("http") or not os.path.exists(path):
        download(path, api)
    if cache not in sys.path:
        sys.path.insert(0, cache)
    try:
        module = SourceFileLoader(name, path).load_module()
    except ModuleNotFoundError as e:
        # 缺公共依赖模块（如 base/spider）：从脚本同源仓库递归下载后重试；
        # 补链 404（仓库无该公共包）时用内置最小 base 包兜底
        if depth >= 4 or not api.startswith("http") or not e.name:
            raise
        download_module(api.rsplit('/', 1)[0], e.name)
        if e.name == "base":
            ensure_base_stub(cache)
        return load(api, cache, depth + 1)
    spiders[api] = module.Spider()
    return True


def fix_url(value):
    """源方常返回含中文/未编码字符的 URL，Python http.client 要求纯 ASCII——
    对含非 ASCII 字符的 http(s) 字符串做 percent-encode（保留 URL 结构与已编码 %）。"""
    if isinstance(value, str) and value.startswith(("http://", "https://")) and not value.isascii():
        from urllib.parse import quote
        return quote(value, safe=":/?#[]@!$&'()*+,;=%~-._")
    return value


# monkey-patch：源方脚本常用中文路径/查询直接构造请求（urllib 要求纯 ASCII 即炸），
# 在 Request 构造与 urlopen 入口统一做 percent-encode 兜底
_orig_request_init = urllib.request.Request.__init__
_orig_urlopen = urllib.request.urlopen


def _patched_request_init(self, url, *args, **kwargs):
    _orig_request_init(self, fix_url(url) if isinstance(url, str) else url, *args, **kwargs)


def _patched_urlopen(url, *args, **kwargs):
    return _orig_urlopen(fix_url(url) if isinstance(url, str) else url, *args, **kwargs)


urllib.request.Request.__init__ = _patched_request_init
urllib.request.urlopen = _patched_urlopen


def invoke(ru, method, args):
    """按目标方法签名裁剪多余位置参数：源方 Spider 各版本签名不一（如 searchContent
    有 2 参也有 4 参），宿主统一按 4 参调用时老签名会 TypeError。超出签名容量的
    尾部实参裁掉重试；可变参（*args）或签名不可读时不裁剪。方法体内自身抛出的
    TypeError 不受影响（签名可绑定即直接调用）。"""
    fn = getattr(ru, method, None)
    if fn is None:
        raise AttributeError(method)
    try:
        sig = inspect.signature(fn)
    except (TypeError, ValueError):
        return fn(*args)
    has_var = any(p.kind == inspect.Parameter.VAR_POSITIONAL for p in sig.parameters.values())
    if not has_var:
        positional = [p for p in sig.parameters.values()
                      if p.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD)]
        if len(args) > len(positional):
            args = args[:len(positional)]
    return fn(*args)


def call(api, method, args):
    ru = spiders[api]
    if method == "searchContent" and len(args) == 2:
        args = [args[0], args[1], "1"]
    if method == "categoryContent" and len(args) >= 4 and isinstance(args[3], str):
        args[3] = json.loads(args[3])
    if method == "detailContent" and len(args) == 1 and isinstance(args[0], str):
        args = [json.loads(args[0])]
    if method == "playerContent" and len(args) >= 3 and isinstance(args[2], str):
        args[2] = json.loads(args[2])
    args = [fix_url(a) for a in args]
    result = invoke(ru, method, args)
    if isinstance(result, (dict, list, bool, int, float)) or result is None:
        return result
    return str(result)


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            op = request.get("op")
            api = request.get("api")
            args = request.get("args", [])
            if op == "load":
                result = load(api, args[1])
            else:
                result = call(api, request.get("method"), args)
            response = {"result": result}
        except Exception as e:
            import traceback
            tb = traceback.format_exc()
            response = {"error": "{}: {}\n{}".format(type(e).__name__, e, tb[-1500:])}
        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
