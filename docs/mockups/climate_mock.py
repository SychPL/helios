"""Mockups for SPEC 0.19 (climate card): the 1x1 tile on the dashboard and the full-screen control panel, drawn at 800x480."""
from PIL import Image, ImageDraw, ImageFont

A = 'I:/Projekty/lenovo_clock/dash/.claude/worktrees/weather-details/app/src/main/assets/'
OUT = 'I:/Projekty/lenovo_clock/dash/.claude/worktrees/weather-details/docs/mockups/'
BG, CARD, TEXT, MUTED, ACCENT = (0x18, 0x1C, 0x24), (0x26, 0x2D, 0x38), (0xF7, 0xF4, 0xEE), (0xC1, 0xC7, 0xD0), (0xED, 0xBE, 0x83)
CHIP, CHIP_ON_TEXT = (0x33, 0x3B, 0x48), (0x18, 0x1C, 0x24)
CODES = {}
for line in open(A + 'mdi-codepoints.txt', encoding='utf-8'):
    parts = line.split()
    if len(parts) == 2:
        CODES[parts[0]] = chr(int(parts[1], 16))


def font(size, mono=False):
    return ImageFont.truetype(A + ('GeistMono.ttf' if mono else 'Geist.ttf'), size)


def mdi(size):
    return ImageFont.truetype(A + 'materialdesignicons-webfont.ttf', size)


def icon(d, name, x, y, size, color):
    d.text((x, y), CODES[name], font=mdi(size), fill=color)


def rr(d, box, fill, r=18):
    d.rounded_rectangle(box, radius=r, fill=fill)


def bar(d):
    d.text((24, 18), 'H E L I O S', font=font(17), fill=MUTED)
    icon(d, 'home-assistant', 744, 12, 30, (0x18, 0xBC, 0xF2))


def tile(d, x, y, w, h, name, current, target, action_icon, active, off=False):
    rr(d, (x, y, x + w, y + h), CARD)
    icon(d, action_icon, x + 14, y + 12, 24, ACCENT if active else MUTED)
    d.text((x + 46, y + 14), name, font=font(17), fill=MUTED)
    d.text((x + 14, y + 44), current, font=font(40), fill=TEXT)
    d.text((x + 14, y + h - 34), 'Wyłączony' if off else f'Nastawa {target}', font=font(17), fill=MUTED)


def dashboard(path):
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    bar(d)
    rr(d, (8, 60, 396, 332), CARD)
    d.text((28, 84), 'Dom', font=font(20), fill=MUTED)
    d.text((24, 120), '18:40', font=font(110, True), fill=TEXT)
    rr(d, (404, 60, 792, 192), CARD)
    d.text((424, 100), '15°C  ·  jutro 17°C', font=font(30), fill=TEXT)
    tile(d, 404, 200, 188, 132, 'Salon', '23,0°', '20,5°', 'fire', True)
    tile(d, 602, 200, 190, 132, 'gree', '22,0°', '24°', 'thermostat', False, off=True)
    tile(d, 8, 340, 188, 132, 'Biuro', '—', '21°', 'thermostat', False)
    d.text((212, 360), '← kafelek 1x1: obecna duża, nastawa pod nią;', font=font(15), fill=MUTED)
    d.text((212, 382), '   płomień w kolorze akcentu = grzeje, śnieżynka = chłodzi,', font=font(15), fill=MUTED)
    d.text((212, 404), '   szary termostat = bezczynny; "—" gdy brak pomiaru', font=font(15), fill=MUTED)
    im.save(path)


def chip(d, box, label, on):
    rr(d, box, ACCENT if on else CHIP, 14)
    f = font(19)
    w = d.textlength(label, font=f)
    d.text(((box[0] + box[2] - w) / 2, (box[1] + box[3]) / 2 - 12), label, font=f, fill=CHIP_ON_TEXT if on else TEXT)


def picker(d, box, label, value):
    rr(d, box, CHIP, 14)
    d.text((box[0] + 16, box[1] + 10), label, font=font(15), fill=MUTED)
    d.text((box[0] + 16, box[1] + 32), value, font=font(20), fill=TEXT)
    icon(d, 'chevron-down', box[2] - 40, box[1] + 22, 26, MUTED)


def panel(path, name, status, current, action, target, modes, mode_on, pickers, action_icon, active):
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    bar(d)
    oy = 52  # everything below the bar, 800x428
    icon(d, action_icon, 16, oy + 14, 34, ACCENT if active else MUTED)
    d.text((60, oy + 16), name, font=font(26), fill=TEXT)
    d.text((60 + d.textlength(name, font=font(26)) + 16, oy + 22), status, font=font(18), fill=MUTED)
    rr(d, (728, oy, 800 - 8, oy + 64), CHIP, 14)
    icon(d, 'close', 744, oy + 14, 34, TEXT)
    # left: now
    rr(d, (8, oy + 72, 380, oy + 248), CARD)
    d.text((28, oy + 88), 'Teraz', font=font(17), fill=MUTED)
    d.text((24, oy + 110), current, font=font(84), fill=TEXT)
    d.text((28, oy + 212), action, font=font(18), fill=ACCENT if active else MUTED)
    # right: setpoint
    rr(d, (388, oy + 72, 792, oy + 248), CARD)
    d.text((408, oy + 88), 'Nastawa', font=font(17), fill=MUTED)
    for bx, sym in ((404, 'minus'), (696, 'plus')):
        rr(d, (bx, oy + 124, bx + 88, oy + 212), CHIP, 18)
        icon(d, sym, bx + 22, oy + 146, 44, TEXT)
    f = font(64)
    w = d.textlength(target, font=f)
    d.text((590 - w / 2, oy + 130), target, font=f, fill=TEXT)
    # mode row
    d.text((16, oy + 266), 'Tryb', font=font(17), fill=MUTED)
    n = len(modes)
    x0, x1, gap = 96, 792, 8
    cw = (x1 - x0 - gap * (n - 1)) / n
    for i, m in enumerate(modes):
        bx = x0 + i * (cw + gap)
        chip(d, (bx, oy + 252, bx + cw, oy + 316), m, m == mode_on)
    # picker row
    if pickers:
        n = len(pickers)
        pw = (784 - 8 * (n - 1)) / n
        for i, (label, value) in enumerate(pickers):
            bx = 8 + i * (pw + 8)
            picker(d, (bx, oy + 328, bx + pw, oy + 420), label, value)
    im.save(path)


def picker_open(path):
    im = Image.open(OUT + 'climate-panel-gree.png').convert('RGB')
    shade = Image.new('RGB', im.size, (0, 0, 0))
    im = Image.blend(im, shade, 0.55)
    d = ImageDraw.Draw(im)
    rr(d, (176, 60, 624, 472), CARD)
    d.text((200, 76), 'Nawiew', font=font(22), fill=TEXT)
    rr(d, (544, 60, 624, 124), CHIP, 12)          # 80x64 close target
    icon(d, 'close', 566, 74, 34, TEXT)
    opts = ['Domyślny', 'Pełny zakres', 'Stały: góra', 'Stały: środek', 'Stały: dół']
    for i, o in enumerate(opts):
        y = 128 + i * 68
        on = o == 'Pełny zakres'
        rr(d, (192, y, 592, y + 64), ACCENT if on else CHIP, 12)
        d.text((212, y + 20), o, font=font(20), fill=CHIP_ON_TEXT if on else TEXT)
    rr(d, (600, 128, 606, 468), CHIP, 3)          # scroll track
    rr(d, (600, 128, 606, 268), MUTED, 3)         # 5 of 12 visible
    im.save(path)


import os
os.makedirs(OUT, exist_ok=True)
dashboard(OUT + 'climate-tile.png')
panel(OUT + 'climate-panel-salon.png', 'Salon - Termostat', 'grzanie · preset: brak', '23,0°', 'Bezczynny', '20,5°',
      ['Grzanie', 'Wyłączony'], 'Grzanie', [('Preset', 'Brak')], 'thermostat', False)
panel(OUT + 'climate-panel-gree.png', 'gree', 'chłodzenie', '22,0°', 'Chłodzi', '24°',
      ['Auto', 'Chłodzenie', 'Osuszanie', 'Wentylator', 'Grzanie', 'Wył.'], 'Chłodzenie',
      [('Preset', 'Brak'), ('Wentylator', 'Niski'), ('Nawiew', 'Pełny zakres'), ('Nawiew poziomy', 'Domyślny')], 'snowflake', True)
picker_open(OUT + 'climate-picker-gree.png')
print('ok')
