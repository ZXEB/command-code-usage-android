"""极简 Android binary XML (AXML) -> 文本 转换器。

用来确定性检查已打包 APK 的 AndroidManifest.xml：
二进制清单里 receiver 有没有被合并掉、meta-data 在不在。
不依赖任何第三方库。
"""
import struct
import sys
import zipfile

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180

TYPE_ATTR = {
    0x01: "reference", 0x02: "attribute", 0x03: "string", 0x04: "float",
    0x05: "dimension", 0x06: "fraction", 0x07: "dynamic_reference",
    0x08: "int_dec", 0x10: "int_hex", 0x11: "int_boolean", 0x12: "int_color_arsc",
    0x1C: "int_color_rgb", 0x1D: "int_color_argb8",
}


def parse_string_pool(data, offset):
    (_type, header_size, size) = struct.unpack_from("<HHI", data, offset)
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", data, offset + 8
    )
    utf8 = (flags & (1 << 8)) != 0
    offsets = struct.unpack_from("<%dI" % string_count, data, offset + 28)
    out = []
    for off in offsets:
        pos = offset + strings_start + off
        if utf8:
            # 两段式长度（每段 1 或 2 字节）
            n = data[pos]
            pos += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[pos]
                pos += 1
            n2 = data[pos]
            pos += 1
            if n2 & 0x80:
                n2 = ((n2 & 0x7F) << 8) | data[pos]
                pos += 1
            out.append(data[pos:pos + n2].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, pos)[0]
            pos += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                pos += 2
            out.append(data[pos:pos + n * 2].decode("utf-16-le", "replace"))
    return out


def to_text(data):
    magic, size = struct.unpack_from("<HI", data, 0)
    if magic != RES_XML_TYPE:
        raise ValueError("not AXML")
    pos = 8
    strings = []
    res_map = []
    lines = []
    depth = 0
    total = len(data)
    while pos + 8 <= total:
        ctype, hsize, csize = struct.unpack_from("<HHI", data, pos)
        if csize == 0 or pos + csize > total:
            break
        if ctype == RES_STRING_POOL_TYPE:
            strings = parse_string_pool(data, pos)
        elif ctype == RES_XML_RESOURCE_MAP_TYPE:
            n = (csize - hsize) // 4
            res_map = list(struct.unpack_from("<%dI" % n, data, pos + hsize))
        elif ctype == RES_XML_START_ELEMENT_TYPE:
            ns, name_idx = struct.unpack_from("<II", data, pos + hsize)
            attr_start, attr_size, attr_count = struct.unpack_from(
                "<HHH", data, pos + hsize + 8
            )
            name = strings[name_idx] if name_idx < len(strings) else "?"
            attrs = []
            for i in range(attr_count):
                aoff = pos + hsize + attr_start + i * attr_size
                ans, aname, araw, atyp, adata = struct.unpack_from("<IIIiI", data, aoff)
                an = strings[aname] if aname < len(strings) else "?"
                if araw != 0xFFFFFFFF and araw < len(strings):
                    val = strings[araw]
                elif atyp == 0x11:
                    val = "true" if adata != 0 else "false"
                elif atyp == 0x10:
                    val = hex(adata)
                elif atyp in (0x01, 0x07):
                    val = "@ref/0x%08x" % adata
                else:
                    val = str(adata)
                attrs.append("%s=%s" % (an, val))
            pad = "  " * depth
            if attrs:
                lines.append("%s<%s %s>" % (pad, name, " ".join(attrs)))
            else:
                lines.append("%s<%s>" % (pad, name))
            depth += 1
        elif ctype == RES_XML_END_ELEMENT_TYPE:
            depth -= 1
            ns, name_idx = struct.unpack_from("<II", data, pos + hsize)
            name = strings[name_idx] if name_idx < len(strings) else "?"
            lines.append("%s</%s>" % ("  " * depth, name))
        pos += csize
    return "\n".join(lines)


if __name__ == "__main__":
    apk = sys.argv[1]
    with zipfile.ZipFile(apk) as z:
        raw = z.read("AndroidManifest.xml")
    print(to_text(raw))
