"""SPEC 0.20 mockups: the "Uwagi" tile in 1x1 / 1x2 / 2x1 and its states, and the slide-in list, at 800x480
with the app's own fonts, MDI glyphs and warm-graphite colours. Run: python docs/mockups/alerts_mock.py"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
A = ROOT / 'app/src/main/assets'
OUT = Path(__file__).resolve().parent
BG, CARD, TEXT, MUTED, ACCENT = (0x18, 0x1C, 0x24), (0x26, 0x2D, 0x38), (0xF7, 0xF4, 0xEE), (0xC1, 0xC7, 0xD0), (0xED, 0xBE, 0x83)
RAISED, ATT_SURF = (0x33, 0x3B, 0x48), (0x3A, 0x33, 0x2A)
CODES = {}
for line in open(A / 'mdi-codepoints.txt', encoding='utf-8'):
    p = line.split()
    if len(p) == 2:
        CODES[p[0]] = chr(int(p[1], 16))


def font(size, mono=False):
    return ImageFont.truetype(str(A / ('GeistMono.ttf' if mono else 'Geist.ttf')), size)


def icon(d, name, x, y, size, color):
    d.text((x, y), CODES[name], font=ImageFont.truetype(str(A / 'materialdesignicons-webfont.ttf'), size), fill=color)


def rr(d, box, fill, r=18, outline=None, width=0):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def bar(d):
    d.text((24, 18), 'H E L I O S', font=font(17), fill=MUTED)
    icon(d, 'home-assistant', 744, 12, 30, (0x18, 0xBC, 0xF2))


def cell(col, row, w=1, h=1):
    cw, ch = 190, 132
    x, y = 8 + (col - 1) * (cw + 8), 60 + (row - 1) * (ch + 8)
    return x, y, x + cw * w + 8 * (w - 1), y + ch * h + 8 * (h - 1)


def ellipsize(d, text, f, width):
    if d.textlength(text, font=f) <= width:
        return text
    while text and d.textlength(text + '…', font=f) > width:
        text = text[:-1]
    return text + '…'


def alerts_tile(d, box, state, items=(), unknown=0, title='Uwagi'):
    """SPEC 0.20 pkt 3 geometry. state: active / empty_text / nodata; items: [(icon, title)] newest first."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    active = state == 'active'
    rr(d, box, ATT_SURF if active else CARD, outline=ACCENT if active else None, width=2 if active else 0)
    ink = ACCENT if active else TEXT
    icon(d, 'bell-alert' if active else 'bell', x0 + 12, y0 + 10, 22, ACCENT if active else MUTED)
    d.text((x0 + 40, y0 + 12), title, font=font(17), fill=ACCENT if active else MUTED)
    n = len(items)
    big = str(n) if active else ('-' if state == 'nodata' else '0')
    word = {'nodata': 'Brak danych', 'empty_text': 'Brak uwag'}.get(state)
    foot = f'Brak danych: {unknown}' if active and unknown else None
    if w > 300:   # 2x1
        d.text((x0 + 12, y0 + 40), big, font=font(44), fill=ink)
        slots = list(items) if n <= 2 else [items[0], ('dots-horizontal', f'+{n - 1} pozostałych')]
        if word:
            slots = [(None, word)]
        f = font(19)
        for i, (ic, t) in enumerate(slots[:2]):
            yy = y0 + 40 + i * 32
            if ic:
                icon(d, ic, x0 + 92, yy + 2, 20, ink)
            tx = x0 + (120 if ic else 92)
            d.text((tx, yy + 4), ellipsize(d, t, f, x1 - 12 - tx), font=f, fill=TEXT if active else MUTED)
        if foot:
            d.text((x0 + 92, y0 + 106), foot, font=font(13), fill=MUTED)
    elif h > 200:  # 1x2
        d.text((x0 + 12, y0 + 32), big, font=font(44), fill=ink)
        slots = list(items) if n <= 3 else list(items[:2]) + [('dots-horizontal', f'+{n - 2} pozostałych')]
        if word:
            slots = [(None, word)]
        f = font(17)
        for i, (ic, t) in enumerate(slots[:3]):
            yy = y0 + 92 + i * 44
            if ic:
                icon(d, ic, x0 + 12, yy + 2, 20, ink)
            tx = x0 + (40 if ic else 12)
            words, lines = t.split(' '), ['']
            for wd in words:
                if lines[-1] and d.textlength(lines[-1] + ' ' + wd, font=f) > x1 - 12 - tx:
                    lines.append(wd)
                else:
                    lines[-1] = (lines[-1] + ' ' + wd).strip()
            if len(lines) > 2:
                lines = [lines[0], ellipsize(d, lines[1] + ' ' + ' '.join(lines[2:]) + '\u2026', f, x1 - 12 - tx)]
            for k, ln in enumerate(lines[:2]):
                d.text((tx, yy + k * 20), ellipsize(d, ln, f, x1 - 12 - tx), font=f, fill=TEXT if active else MUTED)
        if foot:
            d.text((x0 + 12, y0 + 228), foot, font=font(13), fill=MUTED)
    else:          # 1x1
        d.text((x0 + 12, y0 + 32), big, font=font(44), fill=ink)
        f = font(17)
        if active and items:
            suffix = f' +{n - 1}' if n > 1 else ''
            line = ellipsize(d, items[0][1], f, w - 24 - d.textlength(suffix, font=f)) + suffix
        else:
            line = word or ''
        d.text((x0 + 12, y0 + 84), line, font=f, fill=TEXT if active else MUTED)
        if foot:
            d.text((x0 + 12, y0 + 106), foot, font=font(13), fill=MUTED)


def energy_tile(d, box):
    x0, y0, x1, y1 = box
    rr(d, box, CARD)
    icon(d, 'solar-power', x0 + 14, y0 + 12, 22, MUTED)
    d.text((x0 + 44, y0 + 14), '1824 / 831 W', font=font(17), fill=MUTED)
    icon(d, 'battery-70', x0 + 14, y0 + 52, 40, MUTED)
    d.text((x0 + 60, y0 + 44), '70 %', font=font(44), fill=TEXT)


def base(d):
    bar(d)
    x0, y0, x1, y1 = cell(1, 1, 2, 2)
    rr(d, (x0, y0, x1, y1), CARD)
    d.text((x0 + 20, y0 + 24), 'Dom', font=font(20), fill=MUTED)
    d.text((x0 + 16, y0 + 60), '21:40', font=font(110, True), fill=TEXT)
    x0, y0, x1, y1 = cell(3, 1, 2, 1)
    rr(d, (x0, y0, x1, y1), CARD)
    icon(d, 'weather-cloudy', x0 + 16, y0 + 26, 50, MUTED)
    d.text((x0 + 76, y0 + 26), '12°C', font=font(44), fill=TEXT)
    for c, (ic, t, v) in ((3, ('window-shutter', 'Rolety', 'Zamknięta')), (4, ('desk-lamp', 'Lampka', 'Wyłączone'))):
        x0, y0, x1, y1 = cell(c, 2)
        rr(d, (x0, y0, x1, y1), CARD)
        icon(d, ic, x0 + 14, y0 + 14, 22, MUTED)
        d.text((x0 + 44, y0 + 16), t, font=font(17), fill=MUTED)
        d.text((x0 + 14, y0 + 50), v, font=font(26), fill=TEXT)


ITEMS = [('garage-open', 'Garaż otwarty'), ('trash-can', 'Śmieci jutro'), ('lightbulb', 'Światła')]


def board(path, state, items, unknown=0, empty_card=False, note=''):
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    base(d)
    if empty_card:
        energy_tile(d, cell(1, 3, 2, 1))
    else:
        alerts_tile(d, cell(1, 3, 2, 1), state, items, unknown)
    for c, ic, t, v in ((3, 'thermostat', 'Sypialnia', '21,0°'), (4, 'music', 'Muzyka', '-')):
        x0, y0, x1, y1 = cell(c, 3)
        rr(d, (x0, y0, x1, y1), CARD)
        icon(d, ic, x0 + 14, y0 + 14, 22, MUTED)
        d.text((x0 + 44, y0 + 16), t, font=font(17), fill=MUTED)
        d.text((x0 + 14, y0 + 48), v, font=font(40), fill=TEXT)
    if note:
        d.text((404, 196 - 60), '', font=font(12), fill=MUTED)
    im.save(path)


LONG = [('garage-open', 'Garaż otwarty od wieczora'), ('trash-can', 'Śmieci jutro'), ('lightbulb', 'Światła'), ('information', 'Wiking był')]


def cellbox(x, y, w, h):
    return x, y, x + w, y + h


def sizes(path):
    """1x1 (190x132) and 2x1 (388x132) in every state; exact SPEC 0.20 pkt 3 sizes."""
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    d.text((16, 14), '1x1 i 2x1: aktywne (+ brak danych), brak danych, 0 / Brak uwag (także w czasie 2 s opóźnienia)', font=font(14), fill=MUTED)
    alerts_tile(d, cellbox(8, 48, 190, 132), 'active', LONG[:3], 1)
    alerts_tile(d, cellbox(8, 188, 190, 132), 'nodata')
    alerts_tile(d, cellbox(8, 328, 190, 132), 'empty_text')
    alerts_tile(d, cellbox(206, 48, 190, 132), 'active', LONG[:1])
    alerts_tile(d, cellbox(206, 188, 190, 132), 'active', LONG[1:2], 2)
    alerts_tile(d, cellbox(404, 48, 388, 132), 'active', LONG[:3], 1)
    alerts_tile(d, cellbox(404, 188, 388, 132), 'empty_text')
    alerts_tile(d, cellbox(404, 328, 388, 132), 'nodata')
    alerts_tile(d, cellbox(206, 328, 190, 132), 'active', LONG[:4])
    im.save(path)


def sizes_tall(path):
    """1x2 (190x272) in every state."""
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    d.text((16, 14), '1x2: przepełnienie (4), aktywne + brak danych, brak danych, brak uwag', font=font(14), fill=MUTED)
    alerts_tile(d, cellbox(8, 48, 190, 272), 'active', LONG)
    alerts_tile(d, cellbox(206, 48, 190, 272), 'active', [('garage-open', 'Garaż otwarty od wieczora, brama boczna też'), LONG[1]], 1)
    alerts_tile(d, cellbox(404, 48, 190, 272), 'nodata')
    alerts_tile(d, cellbox(602, 48, 190, 272), 'empty_text')
    d.text((16, 340), 'Tytuły zawijają się do 2 linii, potem wielokropek; sloty co 44, stopka y=228.', font=font(14), fill=MUTED)
    im.save(path)


def drawer(path):
    im = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(im)
    bar(d)
    icon(d, 'bell-alert', 16, 67, 34, ACCENT)
    d.text((60, 70), 'Uwagi', font=font(26), fill=TEXT)
    d.text((150, 76), '3 aktywne', font=font(18), fill=MUTED)
    rr(d, (728, 52, 792, 116), RAISED, 14)
    icon(d, 'close', 743, 67, 34, TEXT)
    rows = [('trash-can', 'Śmieci jutro', 'Aktywne od 19:00', 'Jutro: bio, zmieszane', False),
            ('garage-open', 'Garaż otwarty', 'Aktywne od 18:42', 'Brama garażowa otwarta', False),
            ('lightbulb', 'Światła', 'Aktywne od wczoraj 22:10', 'Brak treści', True)]
    y = 124
    for ic, t, since, txt, off in rows:
        rr(d, (8, y, 792, y + 100), CARD, 16)
        icon(d, ic, 24, y + 16, 28, ACCENT)
        d.text((64, y + 18), t, font=font(22), fill=TEXT)
        sw = d.textlength(since, font=font(16))
        right = 680 if off else 776
        d.text((right - sw, y + 22), since, font=font(16), fill=MUTED)
        d.text((64, y + 56), txt, font=font(19), fill=MUTED)
        if off:
            rr(d, (688, y + 18, 784, y + 82), RAISED, 14)
            f = font(19)
            d.text((736 - d.textlength('Zgaś', font=f) / 2, y + 38), 'Zgaś', font=f, fill=TEXT)
        y += 108
    # unknown row peeking at the bottom (scroll cue)
    rr(d, (8, y, 792, 480 + 40), CARD, 16)
    icon(d, 'garage-open', 24, y + 16, 28, MUTED)
    d.text((64, y + 18), 'Blaszak - brak danych', font=font(22), fill=MUTED)
    im.save(path)



def drow(d, y, ic, t, since, txt, btn=None, h=100, txt_color=MUTED):
    """One drawer row; btn: None / 'on' / 'off' (greyed) / 'busy'."""
    rr(d, (8, y, 792, y + h), CARD, 16)
    icon(d, ic, 24, y + 16, 28, ACCENT)
    d.text((64, y + 18), t, font=font(22), fill=TEXT)
    right = 680 if btn else 776
    if since:
        sw = d.textlength(since, font=font(16))
        d.text((right - sw, y + 22), since, font=font(16), fill=MUTED)
    f = font(19)
    width = right - 16 - 64
    lines, cur = [], ''
    for wd in txt.split(' '):
        if cur and d.textlength(cur + ' ' + wd, font=f) > width:
            lines.append(cur)
            cur = wd
        else:
            cur = (cur + ' ' + wd).strip()
    lines.append(cur)
    if len(lines) > 3:
        lines = lines[:2] + [ellipsize(d, ' '.join(lines[2:]) + '\u2026', f, width)]
    for k, ln in enumerate(lines[:3]):
        d.text((64, y + 52 + k * 24), ln, font=f, fill=txt_color)
    if btn:
        rr(d, (688, y + 18, 784, y + 82), RAISED if btn != 'off' else CARD, 14,
           outline=MUTED if btn == 'off' else None, width=1 if btn == 'off' else 0)
        if btn == 'busy':
            d.arc((722, y + 36, 750, y + 64), 30, 300, fill=MUTED, width=3)
        else:
            d.text((736 - d.textlength('Zgaś', font=f) / 2, y + 38), 'Zgaś', font=f, fill=TEXT if btn == 'on' else MUTED)


def drawer_head(d, n):
    bar(d)
    icon(d, 'bell-alert' if n else 'bell', 16, 67, 34, ACCENT if n else MUTED)
    d.text((60, 70), 'Uwagi', font=font(26), fill=TEXT)
    d.text((150, 76), f'{n} aktywne' if n else '0 aktywnych', font=font(18), fill=MUTED)
    rr(d, (728, 52, 792, 116), RAISED, 14)
    icon(d, 'close', 743, 67, 34, TEXT)


def drawer_states(path):
    """2x2 sheet of 800x480 screens: empty drawer; long text + pending; error + unavailable; confirmation."""
    im = Image.new('RGB', (1600, 960), BG)
    a = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(a)
    drawer_head(d, 0)
    d.text((400 - d.textlength('Brak uwag', font=font(22)) / 2, 280), 'Brak uwag', font=font(22), fill=MUTED)
    im.paste(a, (0, 0))
    b = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(b)
    drawer_head(d, 2)
    drow(d, 124, 'trash-can', 'Śmieci jutro', 'Aktywne od 19:00',
         'Jutro: bio, zmieszane, papier, szkło, tworzywa sztuczne i metale, gabaryty z ulicy Długiej oraz elektroodpady, baterie, opony i zużyte meble z piwnicy, a także odpady zielone z ogrodu przy bramie wjazdowej od strony sąsiadów i lasu', h=132)
    drow(d, 264, 'lightbulb', 'Światła', 'Aktywne od wczoraj 22:10', '3 - kuchnia, salon, wiatrołap', 'busy')
    im.paste(b, (800, 0))
    c = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(c)
    drawer_head(d, 1)
    drow(d, 124, 'lightbulb', 'Światła', 'Aktywne od wczoraj 22:10', 'Nie udało się zgasić', 'on', txt_color=ACCENT)
    d.text((24, 240), 'Niżej: Zgaś niedostępny (brak połączenia / encja nieznana)', font=font(15), fill=MUTED)
    drow(d, 268, 'lightbulb', 'Światła', 'Aktywne od wczoraj 22:10', '3 - kuchnia, salon, wiatrołap', 'off')
    im.paste(c, (0, 480))
    e = Image.new('RGB', (800, 480), BG)
    d = ImageDraw.Draw(e)
    drawer_head(d, 1)
    drow(d, 124, 'lightbulb', 'Światła', 'Aktywne od wczoraj 22:10', '3 - kuchnia, salon, wiatrołap', 'on')
    ov = Image.new('RGBA', (800, 480), (0, 0, 0, 150))
    e.paste(ov, (0, 0), ov)
    d = ImageDraw.Draw(e)
    rr(d, (220, 150, 580, 318), RAISED, 18)
    d.text((240, 170), 'Zgasić wszystkie obserwowane', font=font(18), fill=TEXT)
    d.text((240, 194), 'światła?', font=font(18), fill=TEXT)
    rr(d, (240, 242, 396, 298), CARD, 14)
    rr(d, (404, 242, 560, 298), ACCENT, 14)
    d.text((318 - d.textlength('Anuluj', font=font(17)) / 2, 260), 'Anuluj', font=font(17), fill=TEXT)
    d.text((482 - d.textlength('Potwierdź', font=font(17)) / 2, 260), 'Potwierdź', font=font(17), fill=BG)
    im.paste(e, (800, 480))
    im.save(path)


board(OUT / 'alerts-board-active.png', 'active', ITEMS[:2], 0)
board(OUT / 'alerts-board-empty.png', 'empty_text', [], 0, empty_card=True)
sizes(OUT / 'alerts-sizes.png')
sizes_tall(OUT / 'alerts-sizes-tall.png')
drawer(OUT / 'alerts-drawer.png')
drawer_states(OUT / 'alerts-drawer-states.png')
print('ok')
