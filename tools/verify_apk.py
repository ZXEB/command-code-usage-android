"""对已签名 APK 做一遍独立校验（不依赖 Android SDK / apksigner）。

为什么需要它：本项目只在 GitHub Actions 上构建，本地没有 JDK / Android SDK，
所以「包里到底是哪一版、小组件布局有没有真的打进去」要在制品**下载回来之后**
再独立验证一遍，而不是只看 CI 的绿勾。

校验项：
1. 二进制 AndroidManifest 里的 versionName / versionCode；
2. 解析 resources.arsc，拿到 `id` 类型资源的「名字 -> 资源 id」表；
3. 把包内每个 res/*.xml 解出来，取出它引用的所有 id，据此认出两个小组件布局，
   并断言渲染器要用的 id 一个不少、已删除的 id 一个不留；
4. resources.arsc 里能否找到新加的中文串；
5. APK Signing Block（v2/v3 签名）是否存在。

注意：release 构建会把资源路径缩短（res/layout/widget_usage_4x2.xml -> res/0K.xml），
布局里的 id/text 也都编译成了资源引用，所以这里一律**按内容与 id 数值**判断，
不看文件名、也不看字面量。

用法：python tools/verify_apk.py <apk> [期望的 versionName]
"""

import os
import re
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml  # noqa: E402

SIG_BLOCK_MAGIC = b"APK Sig Block 42"
EOCD_SIG = b"PK\x05\x06"

RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC_TYPE = 0x0202
RES_STRING_POOL_TYPE = 0x0001

FULL_4X2_IDS = [
    "widget_plan", "widget_updated",
    "widget_fivehour", "widget_bar_fivehour",
    "widget_weekly", "widget_bar_weekly",
    "widget_monthly", "widget_bar_monthly",
    "widget_tokens", "widget_tokens_label",
]
COMPACT_2X2_IDS = ["widget_windows", "widget_percent", "widget_monthly_progress", "widget_tokens"]

# 4×4 详细版：三个窗口卡片，每个一套「标题/推算标记/已用%/进度条/剩余/重置」
DETAILED_4X4_IDS = ["widget_plan", "widget_updated"] + [
    "widget_%s_%s" % (prefix, suffix)
    for prefix in ("fivehour", "weekly", "monthly")
    for suffix in ("card", "title", "note", "percent", "bar", "remaining", "reset")
]

REMOVED_IDS = ["widget_remaining", "widget_percent_caption"]


# ----------------------------------------------------------------------
# resources.arsc：取 id 类型资源的名字 -> 资源 id
# ----------------------------------------------------------------------

def parse_id_names(arsc):
    magic, header_size, total_size = struct.unpack_from("<HHI", arsc, 0)
    if magic != RES_TABLE_TYPE:
        raise ValueError("not a resources.arsc")

    id_names = {}
    pos = header_size
    while pos + 8 <= total_size:
        ctype, hsize, csize = struct.unpack_from("<HHI", arsc, pos)
        if csize == 0 or pos + csize > len(arsc):
            break
        if ctype == RES_TABLE_PACKAGE_TYPE:
            pkg_id = struct.unpack_from("<I", arsc, pos + 8)[0]
            type_strings_off, _last_pub_type, key_strings_off = struct.unpack_from(
                "<III", arsc, pos + 268
            )
            type_strings = axml.parse_string_pool(arsc, pos + type_strings_off)
            key_strings = axml.parse_string_pool(arsc, pos + key_strings_off)

            sub = pos + hsize
            end = pos + csize
            while sub + 8 <= end:
                stype, shsize, ssize = struct.unpack_from("<HHI", arsc, sub)
                if ssize == 0 or sub + ssize > end:
                    break
                if stype == RES_TABLE_TYPE_TYPE:
                    type_id = arsc[sub + 8]
                    flags = arsc[sub + 9]
                    entry_count, entries_start = struct.unpack_from("<II", arsc, sub + 12)
                    type_name = (
                        type_strings[type_id - 1]
                        if 0 < type_id <= len(type_strings)
                        else "?"
                    )
                    if type_name == "id":
                        for index in range(entry_count):
                            if flags & 0x01:  # sparse：uint16 (idx, offset/4) 对
                                idx, off16 = struct.unpack_from(
                                    "<HH", arsc, sub + shsize + index * 4
                                )
                                if idx != index:
                                    continue
                                offset = off16 * 4
                            elif flags & 0x02:  # offset16：uint16，0xFFFF 表示空
                                off16 = struct.unpack_from(
                                    "<H", arsc, sub + shsize + index * 2
                                )[0]
                                if off16 == 0xFFFF:
                                    continue
                                offset = off16 * 4
                            else:  # 常规：uint32
                                offset = struct.unpack_from(
                                    "<I", arsc, sub + shsize + index * 4
                                )[0]
                                if offset == 0xFFFFFFFF:
                                    continue
                            entry_pos = sub + entries_start + offset
                            if entry_pos + 8 > len(arsc):
                                continue
                            key_index = struct.unpack_from("<H", arsc, entry_pos + 4)[0]
                            name = (
                                key_strings[key_index]
                                if key_index < len(key_strings)
                                else "?"
                            )
                            res_id = (pkg_id << 24) | (type_id << 16) | index
                            id_names.setdefault(name, res_id)
                sub += ssize
        pos += csize
    return id_names


def referenced_ids(text):
    """布局里引用到的所有资源 id。

    axml.py 对引用型属性有时打成 `@ref/0x7f080086`、有时直接给十进制（取决于
    dataType 与 raw 值字段的组合），两种写法都要认。
    """
    ids = set()
    for hex_part, dec_part in re.findall(r"\bid=(?:@ref/0x([0-9a-fA-F]+)|(\d+))", text):
        ids.add(int(hex_part, 16) if hex_part else int(dec_part))
    return ids


def decode_res_xml(z):
    out = {}
    for name in z.namelist():
        if name.startswith("res/") and name.endswith(".xml"):
            try:
                out[name] = axml.to_text(z.read(name))
            except Exception:
                pass
    return out


# ----------------------------------------------------------------------
# 各项检查
# ----------------------------------------------------------------------

def check_manifest(text, expected_version):
    version_name = re.search(r"\bversionName=(\S+)", text)
    version_code = re.search(r"\bversionCode=(\S+)", text)
    version_name = version_name.group(1) if version_name else None
    version_code = version_code.group(1) if version_code else None
    print("  versionName=%s  versionCode=%s" % (version_name, version_code))
    if not version_name:
        print("  FAIL: 清单里没解析出 versionName")
        return False
    if expected_version and version_name != expected_version:
        print("  FAIL: 期望 versionName=%s" % expected_version)
        return False
    return True


def check_widget_layouts(texts, id_names):
    ok = True
    # 每个尺寸：期望的 id 集合 + 应有的进度条数量
    wanted = {
        "4x4": (DETAILED_4X4_IDS, 3),
        "4x2": (FULL_4X2_IDS, 3),
        "2x2": (COMPACT_2X2_IDS, 1),
    }
    for label, (names, expected_bars) in wanted.items():
        ids = [id_names.get(n) for n in names]
        missing_names = [n for n, i in zip(names, ids) if i is None]
        if missing_names:
            print("  FAIL: %s 的 id 没在 resources.arsc 里定义: %s" % (label, missing_names))
            ok = False
            continue
        needle = set(ids)
        # 按「引用了这些 id」认出布局（路径会被缩短，不能靠文件名）
        hit = [(n, t) for n, t in texts.items() if needle <= referenced_ids(t)]
        if not hit:
            print("  FAIL: %s 找不到引用这些 id 的布局" % label)
            ok = False
            continue
        name, text = hit[0]
        bars = text.count("<ProgressBar")
        print("  %s -> %s（引用 id 全部命中，ProgressBar x%d）" % (label, name, bars))
        if bars != expected_bars:
            print("  FAIL: %s 应有 %d 条进度条" % (label, expected_bars))
            ok = False

    for name in REMOVED_IDS:
        if name in id_names:
            print("  FAIL: 已删除的 id 仍在资源表里: %s" % name)
            ok = False
    return ok


def check_strings(z, needles):
    blobs = []
    for name in z.namelist():
        if name == "resources.arsc" or name.startswith("res/"):
            blobs.append((name, z.read(name)))
    ok = True
    for needle in needles:
        hit = None
        for enc in ("utf-8", "utf-16-le"):
            raw = needle.encode(enc)
            for name, blob in blobs:
                if raw in blob:
                    hit = "%s(%s)" % (name, enc)
                    break
            if hit:
                break
        print("  %s: %s" % (needle, hit or "NOT FOUND"))
        if not hit:
            ok = False
    return ok


def check_signature(path):
    """ZIP 中央目录紧跟在 APK Signing Block 之后，块尾 16 字节是魔数。"""
    with open(path, "rb") as f:
        data = f.read()
    eocd = data.rfind(EOCD_SIG)
    if eocd < 0:
        print("  FAIL: 找不到 ZIP 中央目录结尾")
        return False
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    ok = data[max(0, cd_offset - 16):cd_offset] == SIG_BLOCK_MAGIC
    print("  APK Signing Block @%d: %s" % (cd_offset, "OK (v2/v3)" if ok else "未找到"))
    return ok


def main():
    apk = sys.argv[1]
    expected = sys.argv[2] if len(sys.argv) > 2 else None
    print("=== %s (%d bytes) ===" % (os.path.basename(apk), os.path.getsize(apk)))
    with zipfile.ZipFile(apk) as z:
        manifest = axml.to_text(z.read("AndroidManifest.xml"))
        id_names = parse_id_names(z.read("resources.arsc"))
        texts = decode_res_xml(z)
        print("  resources.arsc 里 id 资源 %d 个，包内 res/*.xml %d 个" % (len(id_names), len(texts)))
        results = {
            "manifest": check_manifest(manifest, expected),
            "layouts": check_widget_layouts(texts, id_names),
            "strings": check_strings(z, ["5时", "每周", "本期 tokens"]),
        }
    results["signature"] = check_signature(apk)

    print("--- 汇总 ---")
    for key, value in results.items():
        print("  %-10s %s" % (key, "OK" if value else "FAIL"))
    sys.exit(0 if all(results.values()) else 1)


if __name__ == "__main__":
    main()
