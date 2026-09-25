"""SPEC 0.19 mockups, version 2 - after the design discussion with Codex (2026-09-24): amber only for real activity,
neutral selection with a border, title-only header, "W pokoju"/"Zadana", agreed 800x480 geometry."""
from PIL import Image, ImageDraw, ImageFont

A = 'I:/Projekty/lenovo_clock/dash/.claude/worktrees/weather-details/app/src/main/assets/'
OUT = 'I:/Projekty/lenovo_clock/dash/.claude/worktrees/weather-details/docs/mockups/'
BG, CARD, TEXT, MUTED, ACCENT = (0x18, 0x1C, 0x24), (0x26, 0x2D, 0x38), (0xF7, 0xF4, 0xEE), (0xC1, 0xC7, 0xD0), (0xED, 0xBE, 0x83)
RAISED, SELECTED, BORDER = (0x33, 0x3B, 0x48), (0x3A, 0x43, 0x52), (0xB0, 0xAF, 0xAC)  # border = text at ~60 % over the selected fill
CODES = {}
for line in open(A + 'mdi-codepoints.txt', encoding='utf-8'):
    p = line.split()
    if len(p) == 2:
        CODES[p[0]] = chr(int(p[1], 16))


def font(size, mono=False):
    return ImageFont.truetype(A + ('GeistMono.ttf' if mono else 'Geist.ttf'), size)


def icon(d, name, x, y, size, color):
    d.text((x, y), CODES[name], font=ImageFont.truetype(A + 'materialdesignicons-webfont.ttf', size), fill=color)


def rr(d, box, fill, r=18, outline=None, width=0):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def centred(d, box, text, f, fill):
    w = d.textlength(text, font=f)
    bb = f.getbbox(text)
    d.text(((box[0] + box[2] - w) / 2, (box[1] + box[3]) / 2 - (bb[1] + bb[3]) / 2), text, font=f, fill=fill)


def bar(d):
    d.text((24, 18), 'H E L I O S', font=font(17), fill=MUTED)
    icon(d, 'home-assistant', 744, 12, 30, (0x18, 0xBC, 0xF2))


def tile(d, x, y, name, value, line, ic, active):
    w, h = 190, 132
    rr(d, (x, y, x + w, y + h), CARD)
    icon(d, ic, x + 14, y + 12, 24, ACCENT if active else MUTED)
    d.text((x + 46, y + 14), name, font=font(17), fill=MUTED)
    d.text((x + 14, y + 42), value, font=font(44), fill=TEXT)
    d.text((x + 14, y + 100), line, font=font(17), fill=MUTED)


def dashboard(path):
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    bar(d)
    tile(d, 8, 60, 'Salon', '23,0°', 'Zadana 20,5°', 'fire', True)
    tile(d, 206, 60, 'Sypialnia', '21,0°', 'Zadana 22°', 'thermostat', False)
    tile(d, 404, 60, 'gree', '22,0°', 'Wyłączony', 'power', False)
    tile(d, 602, 60, 'Biuro', '—', 'Zadana 21°', 'thermostat', False)
    tile(d, 8, 200, 'Łazienka', '—', 'Brak połączenia', 'thermostat', False)
    notes = ['grzeje: płomień w akcencie', 'włączony, bezczynny: szary termostat', 'wyłączony: ikona zasilania',
             'brak pomiaru: kreska, nastawa zostaje', 'niedostępny: "Brak połączenia", bez starej wartości']
    for i, n in enumerate(notes):
        d.text((214, 212 + i * 22), n, font=font(15), fill=MUTED)
    im.save(path)


def button(d, box, label, selected, f=19):
    rr(d, box, SELECTED if selected else RAISED, 14, outline=BORDER if selected else None, width=2 if selected else 0)
    centred(d, box, label, font(f), TEXT)


def selector(d, box, label, value):
    rr(d, box, RAISED, 14)
    d.text((box[0] + 16, box[1] + 14), label, font=font(16), fill=MUTED)
    d.text((box[0] + 16, box[1] + 42), value, font=font(21), fill=TEXT)
    icon(d, 'chevron-down', box[2] - 40, box[1] + 30, 26, MUTED)


def panel(path, title, now, activity, activity_on, ic, target, status, modes, mode_on, selectors):
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    bar(d)
    icon(d, ic, 16, 67, 34, ACCENT if activity_on else MUTED)
    d.text((60, 70), title, font=font(26), fill=TEXT)
    rr(d, (728, 52, 792, 116), RAISED, 14)
    icon(d, 'close', 743, 67, 34, TEXT)
    rr(d, (8, 124, 381, 280), CARD)
    d.text((28, 136), 'W pokoju', font=font(18), fill=MUTED)
    d.text((24, 156), now, font=font(76), fill=TEXT)
    d.text((28, 248), activity, font=font(20), fill=ACCENT if activity_on else MUTED)
    rr(d, (389, 124, 792, 280), CARD)
    d.text((409, 136), 'Zadana', font=font(18), fill=MUTED)
    for bx, sym in ((405, 'minus'), (696, 'plus')):
        rr(d, (bx, 160, bx + 80, 240), RAISED, 18)
        icon(d, sym, bx + 18, 178, 44, TEXT)
    centred(d, (493, 160, 688, 240), target, font(64), TEXT)
    if status:
        d.text((409, 248), status, font=font(18), fill=MUTED)
    d.text((16, 286), 'Tryb', font=font(16), fill=MUTED)
    n = len(modes)
    w = (784 - 8 * (n - 1)) / n
    for i, m in enumerate(modes):
        x = 8 + i * (w + 8)
        button(d, (x, 312, x + w, 376), m, m == mode_on, 19 if n > 3 else 21)
    if selectors:
        n = len(selectors)
        w = (784 - 8 * (n - 1)) / n
        for i, (label, value) in enumerate(selectors):
            x = 8 + i * (w + 8)
            selector(d, (x, 384, x + w, 472), label, value)
    im.save(path)


def picker(path):
    im = Image.open(OUT + 'climate-panel-gree.png').convert('RGB')
    im = Image.blend(im, Image.new('RGB', im.size, (0, 0, 0)), 0.55)
    d = ImageDraw.Draw(im)
    rr(d, (176, 52, 624, 472), CARD)
    d.text((200, 72), 'Kierunek pionowy', font=font(22), fill=TEXT)
    rr(d, (548, 60, 612, 124), RAISED, 14)
    icon(d, 'close', 563, 75, 34, TEXT)
    opts = ['Domyślny', 'Ruch: pełny', 'Stały: góra', 'Stały: góra-środek', 'Stały: środek']
    d.rectangle((188, 132, 604, 472), fill=CARD)
    for i, o in enumerate(opts):
        y = 136 + i * 72
        sel = o == 'Ruch: pełny'
        box = (192, y, 592, y + 64)
        rr(d, box, SELECTED if sel else RAISED, 12, outline=BORDER if sel else None, width=2 if sel else 0)
        d.text((212, y + 20), o, font=font(21), fill=TEXT)
        if sel:
            icon(d, 'check', 548, y + 16, 30, TEXT)
    rr(d, (176, 452, 624, 472), CARD, 18)   # the fifth row runs under the edge: the list goes on
    rr(d, (600, 136, 606, 468), RAISED, 3)
    rr(d, (600, 136, 606, 250), MUTED, 3)
    im.save(path)


dashboard(OUT + 'climate-tile.png')
panel(OUT + 'climate-panel-salon.png', 'Salon', '23,0°', 'Bezczynny', False, 'thermostat', '20,5°', None,
      ['Grzanie', 'Wyłączony'], 'Grzanie', [('Profil', 'Standardowy')])
panel(OUT + 'climate-panel-gree.png', 'gree', '22,0°', 'Chłodzi', True, 'snowflake', '24°', 'Ustawianie…',
      ['Auto', 'Chłodzenie', 'Osuszanie', 'Wentylator', 'Grzanie', 'Wył.'], 'Chłodzenie',
      [('Profil', 'Standardowy'), ('Siła nawiewu', 'Niska'), ('Kierunek pionowy', 'Ruch: pełny'), ('Kierunek poziomy', 'Domyślny')])
picker(OUT + 'climate-picker-gree.png')
print('ok')
