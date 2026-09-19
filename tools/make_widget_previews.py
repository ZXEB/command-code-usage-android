"""重画小组件的 previewImage 缩略图（drawable-nodpi/widget_preview_*.png）。

为什么需要这个脚本：本项目的构建全部在 GitHub Actions 上跑，本地没有 JDK / Android SDK，
拿不到真机渲染出来的缩略图；Android 12+ 的桌面读 `previewLayout`（真布局），
只有旧版 / 部分第三方桌面才回退到 `previewImage`。所以这里按布局的字号与配色
**手绘**一张示意图，和 XML 布局保持同一套比例与文案。

口径与应用内窗口卡片一致：显示**已用**百分比，进度条同色，明细行是「剩余 $x / $y」
与「N后重置（时刻）」。

用法（工作区根目录或任意目录都可以）：
    python command-code-usage-android/tools/make_widget_previews.py

注意：这是示意图，不是真机渲染 —— 字体度量与 Android 有细微差异，
看的是「内容与配色对不对」，不要拿它做像素级断言。
"""

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable-nodpi")

SCALE = 3.06  # px per dp，与旧图接近（300dp -> 918px）

CARD = (255, 255, 255, 255)
STROKE = (224, 224, 224, 255)
CARD_INNER = (246, 246, 246, 255)  # widget_card_inner（浅色）
TEXT_PRIMARY = (26, 26, 26, 255)
TEXT_SECONDARY = (140, 140, 140, 255)
TEXT_TERTIARY = (170, 170, 170, 255)
TRACK = (227, 227, 227, 255)
OK = (47, 168, 79, 255)  # widget_ok  #2FA84F
WARN = (240, 160, 32, 255)  # widget_warn
ERROR = (229, 57, 53, 255)  # widget_error

REGULAR = "C:/Windows/Fonts/msyh.ttc"
BOLD = "C:/Windows/Fonts/msyhbd.ttc"


def font(path, sp):
    return ImageFont.truetype(path, int(round(sp * SCALE)))


def dp(value):
    return int(round(value * SCALE))


def utilization_color(used_percent):
    """与应用内 utilizationColor 同阈值：<70 绿 / ≥70 黄 / ≥90 红。"""
    if used_percent >= 90.0:
        return ERROR
    if used_percent >= 70.0:
        return WARN
    return OK


def new_card(width_dp, height_dp):
    img = Image.new("RGBA", (dp(width_dp), dp(height_dp)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(
        [(0, 0), (img.width - 1, img.height - 1)],
        radius=dp(22),
        fill=CARD,
        outline=STROKE,
        width=2,
    )
    return img, draw


def text_width(draw, text, f):
    box = draw.textbbox((0, 0), text, font=f)
    return box[2] - box[0], box[3] - box[1]


def draw_centered(draw, cx, top, text, f, fill):
    w, _ = text_width(draw, text, f)
    draw.text((cx - w / 2, top), text, font=f, fill=fill)


def draw_bar(draw, left, top, width, height, used_percent, color):
    """进度条画的是**已用**比例（与应用内一致：用得越多条越长）。"""
    radius = height // 2
    draw.rounded_rectangle(
        [(left, top), (left + width, top + height)], radius=radius, fill=TRACK
    )
    filled = int(width * used_percent / 100.0)
    if filled > 0:
        draw.rounded_rectangle(
            [(left, top), (left + max(filled, height), top + height)],
            radius=radius,
            fill=color,
        )


def make_4x4():
    """三个窗口卡片：标题(+推算标记) / 已用% / 进度条 / 剩余 / 重置。"""
    img, draw = new_card(250, 250)
    pad = dp(12)

    f_plan = font(BOLD, 12)
    f_stamp = font(REGULAR, 9)
    f_title = font(BOLD, 13)
    f_note = font(REGULAR, 10)
    f_pct = font(BOLD, 18)
    f_line = font(REGULAR, 10)

    # 顶部：套餐名 + 更新时间
    draw.text((pad, pad), "Go", font=f_plan, fill=TEXT_PRIMARY)
    stamp = "22:41"
    w, _ = text_width(draw, stamp, f_stamp)
    draw.text((img.width - pad - w, pad + dp(2)), stamp, font=f_stamp, fill=TEXT_TERTIARY)

    # 三张卡片。数值直接对应用户提供的参考图，方便肉眼比对。
    windows = [
        ("5 小时", None, 7, "剩余 $2.77 / $3.00", "2小时50分后重置（2026/9/15 00:08）"),
        ("每周", None, 31, "剩余 $4.09 / $6.00", "4天18小时后重置（2026/9/19 15:48）"),
        ("每月", "按周期推算", 77, "剩余 $2.21 / $10.00", "10天16小时后重置（2026/9/25 14:03）"),
    ]

    top = pad + dp(20)
    card_h = dp(66)
    gap = dp(8)
    inner_pad = dp(10)

    for index, (title, note, used, remaining, reset) in enumerate(windows):
        card_top = top + index * (card_h + gap)
        color = utilization_color(used)
        # 内层卡片背景
        draw.rounded_rectangle(
            [(pad, card_top), (img.width - pad, card_top + card_h)],
            radius=dp(14),
            fill=CARD_INNER,
        )
        left = pad + inner_pad

        # 标题 + 可选「按周期推算」
        y = card_top + dp(9)
        draw.text((left, y), title, font=f_title, fill=TEXT_PRIMARY)
        if note:
            tw, _ = text_width(draw, title, f_title)
            draw.text((left + tw + dp(6), y + dp(2)), note, font=f_note, fill=TEXT_SECONDARY)

        # 右上角已用百分比（按档位着色，与应用内一致）
        pct = f"{used}%"
        pw, _ = text_width(draw, pct, f_pct)
        draw.text((img.width - pad - inner_pad - pw, y - dp(4)), pct, font=f_pct, fill=color)

        # 进度条（同色）
        draw_bar(draw, left, y + dp(21), img.width - pad * 2 - inner_pad * 2, dp(5), used, color)

        # 明细两行
        draw.text((left, y + dp(32)), remaining, font=f_line, fill=TEXT_SECONDARY)
        draw.text((left, y + dp(45)), reset, font=f_line, fill=TEXT_SECONDARY)

    return img


def make_4x2():
    """三个窗口**竖排**（一行一个）：名称在左、已用% 在右，下面一条通栏进度条。

    与布局 widget_usage_4x2.xml 一一对应：行1 = 套餐名 + tokens + 更新时间（共用一行），
    行2/3/4 = 5 小时 / 每周 / 每月，三行按 layout_weight=1 均分剩余高度。
    """
    img, draw = new_card(300, 110)
    pad = dp(12)

    f_plan = font(BOLD, 11)
    f_stamp = font(REGULAR, 9)
    f_tokens = font(BOLD, 12)
    f_caption = font(REGULAR, 8)
    f_label = font(REGULAR, 10)
    f_value = font(BOLD, 13)

    # 行1：套餐名（左）；tokens 数值 + 口径说明 + 更新时间（右）
    draw.text((pad, pad), "Go", font=f_plan, fill=TEXT_PRIMARY)

    stamp = "08:29"
    sw, _ = text_width(draw, stamp, f_stamp)
    draw.text((img.width - pad - sw, pad + dp(2)), stamp, font=f_stamp, fill=TEXT_TERTIARY)

    caption = "本期 tokens"
    cw, _ = text_width(draw, caption, f_caption)
    caption_left = img.width - pad - sw - dp(6) - cw
    draw.text((caption_left, pad + dp(3)), caption, font=f_caption, fill=TEXT_SECONDARY)

    tokens = "607.8M"
    tw, _ = text_width(draw, tokens, f_tokens)
    draw.text((caption_left - dp(3) - tw, pad + dp(1)), tokens, font=f_tokens, fill=TEXT_PRIMARY)

    # 行2-4：三个窗口竖排，均分剩余高度（对应 layout_weight=1）
    rows_top = pad + dp(15) + dp(4)
    row_h = (img.height - pad - rows_top) // 3

    # 取值与 make_4x4 保持一致，便于对照。刻意**不用 0%**：0% 画出来就是一条空轨道，
    # 在缩略图里会被误读成「进度条坏了」——正是这一版要修的那个毛病。
    windows = [("5 小时", 7), ("每周", 31), ("每月", 77)]
    for index, (label, used) in enumerate(windows):
        text_top = rows_top + index * row_h + dp(3)
        color = utilization_color(used)

        # 名称（左，次要色）
        draw.text((pad, text_top), label, font=f_label, fill=TEXT_SECONDARY)

        # 已用%（右，按档位着色）
        pct = f"{used}%"
        pw, _ = text_width(draw, pct, f_value)
        draw.text((img.width - pad - pw, text_top - dp(1)), pct, font=f_value, fill=color)

        # 通栏进度条（同色）
        draw_bar(draw, pad, text_top + dp(16), img.width - pad * 2, dp(3), used, color)

    return img


def make_2x2():
    img, draw = new_card(110, 110)
    pad = dp(12)
    content_w = img.width - pad * 2

    f_line = font(REGULAR, 9)
    f_big = font(BOLD, 28)
    f_tokens = font(BOLD, 13)

    # 行1：5时 / 周（短名，见 strings.xml 里的说明）
    draw.text((pad, pad), "5时 7% · 周 31%", font=f_line, fill=TEXT_SECONDARY)

    # 行2：月度已用（大字，按档位着色）
    draw.text((pad, pad + dp(14)), "77%", font=f_big, fill=utilization_color(77))

    # 行3：月度进度条（同色）
    draw_bar(draw, pad, pad + dp(49), content_w, dp(5), 77, utilization_color(77))

    # 行4：token 行带口径前缀
    draw.text((pad, pad + dp(59)), "本期 233.4M", font=f_tokens, fill=TEXT_PRIMARY)
    return img


def main():
    out = os.path.normpath(OUT_DIR)
    makers = (
        ("widget_preview_4x4.png", make_4x4),
        ("widget_preview_4x2.png", make_4x2),
        ("widget_preview_2x2.png", make_2x2),
    )
    for name, maker in makers:
        path = os.path.join(out, name)
        maker().save(path)
        print("wrote %s" % path)


if __name__ == "__main__":
    main()
