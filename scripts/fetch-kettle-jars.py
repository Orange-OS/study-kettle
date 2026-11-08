# -*- coding: utf-8 -*-
"""
远程按需提取 PDI 发行包 zip 中的 jar（HTTP Range 请求，无需下载整个 zip）。
用法:
    python fetch-kettle-jars.py list                 # 列出 zip 里 lib/ 下的所有 jar
    python fetch-kettle-jars.py fetch <名字1> ...     # 提取指定名字(前缀匹配)的 jar 到 downloads/extracted/
"""
import struct
import sys
import zlib
import os
import time
import urllib.request

URL = "https://gh-proxy.com/https://github.com/ambientelivre/legacy-pentaho-ce/releases/download/pdi-ce-9.4.0.0-343/pdi-ce-9.4.0.0-343.zip"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "downloads", "extracted")


def http_get_range(start, end, retries=6):
    """下载 [start, end] 闭区间字节，带重试"""
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(URL, headers={"Range": "bytes=%d-%d" % (start, end)})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read()
                if data:
                    return data
        except Exception as e:
            sys.stderr.write("range %d-%d attempt %d failed: %s\n" % (start, end, attempt, e))
            time.sleep(attempt * 2)
    raise RuntimeError("range download failed: %d-%d" % (start, end))


def get_total_size():
    for attempt in range(5):
        try:
            req = urllib.request.Request(URL, method="HEAD")
            with urllib.request.urlopen(req, timeout=60) as resp:
                return int(resp.headers["Content-Length"])
        except Exception as e:
            sys.stderr.write("HEAD attempt %d failed: %s\n" % (attempt + 1, e))
            time.sleep(attempt + 1)
    raise RuntimeError("HEAD failed")


def read_eocd(total):
    """读文件尾部，解析 EOCD，返回 (cd_offset, cd_size)"""
    tail_len = 65536 + 22
    tail = http_get_range(total - tail_len, total - 1)
    pos = tail.rfind(b"PK\x05\x06")
    if pos < 0:
        raise RuntimeError("EOCD not found")
    eocd = tail[pos:pos + 22]
    (this_disk, cd_disk, n_disk, n_total, cd_size, cd_offset, comment_len) = struct.unpack("<HHHHIIH", eocd[4:22])
    return cd_offset, cd_size, n_total


def read_central_directory(total):
    cd_offset, cd_size, n_total = read_eocd(total)
    # 增大 tail 尝试：若 cd 起点在已取范围外，单独再取
    tail_len = 65536 + 22
    tail = http_get_range(total - tail_len, total - 1)
    if cd_offset >= total - tail_len:
        cd = tail[cd_offset - (total - tail_len): cd_offset - (total - tail_len) + cd_size]
    else:
        cd = http_get_range(cd_offset, cd_offset + cd_size - 1)
    entries = []
    pos = 0
    while pos + 46 <= len(cd):
        if cd[pos:pos + 4] != b"PK\x01\x02":
            break
        (method,) = struct.unpack("<H", cd[pos + 10:pos + 12])
        (csize,) = struct.unpack("<I", cd[pos + 20:pos + 24])
        (usize,) = struct.unpack("<I", cd[pos + 24:pos + 28])
        (name_len, extra_len, comment_len) = struct.unpack("<HHH", cd[pos + 28:pos + 34])
        (lho,) = struct.unpack("<I", cd[pos + 42:pos + 46])
        name = cd[pos + 46:pos + 46 + name_len].decode("utf-8", "replace")
        entries.append({"name": name, "method": method, "csize": csize, "usize": usize, "lho": lho})
        pos += 46 + name_len + extra_len + comment_len
    return entries


def extract_entry(entry):
    """按本地头定位数据起点，取压缩数据并解压"""
    lh = http_get_range(entry["lho"], entry["lho"] + 29)
    if lh[0:4] != b"PK\x03\x04":
        raise RuntimeError("local header not found for " + entry["name"])
    (name_len, extra_len) = struct.unpack("<HH", lh[26:30])
    data_start = entry["lho"] + 30 + name_len + extra_len
    comp = http_get_range(data_start, data_start + entry["csize"] - 1)
    if entry["method"] == 0:
        return comp
    if entry["method"] == 8:
        return zlib.decompressobj(-15).decompress(comp)
    raise RuntimeError("unsupported compression method %d" % entry["method"])


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "list"
    total = get_total_size()
    print("zip total size: %d bytes" % total)
    entries = read_central_directory(total)
    print("central directory entries: %d" % len(entries))
    if cmd == "list":
        for e in sorted(entries, key=lambda x: x["name"]):
            if "/lib/" in e["name"] or e["name"].startswith("lib/"):
                print("%10d  %s" % (e["usize"], e["name"]))
        return
    if cmd == "fetch":
        os.makedirs(OUT_DIR, exist_ok=True)
        wanted = sys.argv[2:]
        for e in entries:
            base = os.path.basename(e["name"])
            if not any(base.startswith(w) or w == base for w in wanted):
                continue
            print("extracting %s (%d bytes)..." % (e["name"], e["usize"]))
            data = extract_entry(e)
            out = os.path.join(OUT_DIR, base)
            with open(out, "wb") as f:
                f.write(data)
            print("  -> %s" % out)
        return
    print(__doc__)


if __name__ == "__main__":
    main()
