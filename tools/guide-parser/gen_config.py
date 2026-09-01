# -*- coding: utf-8 -*-
"""Конфиг генерации wiki patterns из гайдов korenevskiy. Импортируется gen_patterns.py."""
from gen_patterns import ch_fill, feature_fill, fi_fill, write_file, cat

K = {
    'bayonet': '★ Bayonet', 'bowie': '★ Bowie Knife', 'butterfly': '★ Butterfly Knife',
    'classic': '★ Classic Knife', 'falchion': '★ Falchion Knife', 'flip': '★ Flip Knife',
    'gut': '★ Gut Knife', 'huntsman': '★ Huntsman Knife', 'karambit': '★ Karambit',
    'kukri': '★ Kukri Knife', 'm9-bayonet': '★ M9 Bayonet', 'navaja': '★ Navaja Knife',
    'nomad': '★ Nomad Knife', 'paracord': '★ Paracord Knife', 'shadow-daggers': '★ Shadow Daggers',
    'skeleton': '★ Skeleton Knife', 'stiletto': '★ Stiletto Knife', 'survival': '★ Survival Knife',
    'talon': '★ Talon Knife', 'ursus': '★ Ursus Knife',
}

def W(fname, skin, pids, cats, note=''):
    write_file(fname, skin, pids, cats, note)

# ---------------- Case Hardened ----------------
CH = {
    'bayonet': 3087156204, 'bowie': 3234415580, 'butterfly': 3087097250, 'classic': 3234771078,
    'falchion': 3233766815, 'flip': 3088588867, 'gut': 3234526881, 'huntsman': 3234614725,
    'karambit': 2948564018, 'kukri': 3229119705, 'm9-bayonet': 2948578790, 'navaja': 3234656579,
    'nomad': 3089706915, 'paracord': 3089711147, 'shadow-daggers': 3234477590, 'skeleton': 3087138888,
    'stiletto': 3089691516, 'survival': 3089705604, 'talon': 3089709897, 'ursus': 3090046949,
}
CH_BACKSIDE = {'karambit': 3419446951, 'skeleton': 3415171994, 'talon': 3416782704}

for knife, pid in CH.items():
    cats = {}
    ch_fill(cats, [pid])
    pids = [pid]
    if knife in CH_BACKSIDE:
        bp = CH_BACKSIDE[knife]
        ch_fill(cats, [bp], 'blue_gem_backside', 'Blue Gem Backside', 'Синий гем (оборотная сторона)')
        pids.append(bp)
    W(f'{knife}-case-hardened', f'{K[knife]} | Case Hardened', pids, cats)

for fname, skin, pid in [
    ('ak-47-case-hardened', 'AK-47 | Case Hardened', 2941982575),
    ('five-seven-case-hardened', 'Five-SeveN | Case Hardened', 2948645553),
    ('mac-10-case-hardened', 'MAC-10 | Case Hardened', 3087121918),
]:
    cats = {}
    ch_fill(cats, [pid])
    W(fname, skin, [pid], cats)

# ---------------- Heat Treated ----------------
for fname, skin, pid in [
    ('desert-eagle-heat-treated', 'Desert Eagle | Heat Treated', 3343006125),
    ('five-seven-heat-treated', 'Five-SeveN | Heat Treated', 3343658990),
]:
    cats = {}
    feature_fill(cats, [pid])
    W(fname, skin, [pid], cats)

# ---------------- Crimson Web ----------------
for fname, skin, pid in [
    ('karambit-crimson-web', '★ Karambit | Crimson Web', 3235445605),
    ('m9-bayonet-crimson-web', '★ M9 Bayonet | Crimson Web', 3235479245),
]:
    cats = {}
    feature_fill(cats, [pid])
    W(fname, skin, [pid], cats)

# ---------------- Marble Fade ----------------
# F&I-ножи: свои F&I-гайды; karambit ещё + per-knife MF гайд
for knife, fi_pid, mf_pid in [
    ('bayonet', 3137149490, None), ('flip', 3137162447, None), ('gut', 3137163506, None),
    ('karambit', 2948050515, 2946977801), ('talon', 3137093565, None),
]:
    cats = {}
    pids = [fi_pid]
    fi_fill(cats, [fi_pid])
    if mf_pid:
        feature_fill(cats, [mf_pid])
        pids.append(mf_pid)
    W(f'{knife}-marble-fade', f'{K[knife]} | Marble Fade', pids, cats)

# per-knife MF гайды
MF = {
    'bowie': 3339251715, 'butterfly': 3338810055, 'falchion': 3339279216, 'huntsman': 3339203605,
    'm9-bayonet': 2948091105, 'navaja': 3339537774, 'nomad': 3489265494, 'paracord': 3493235268,
    'skeleton': 3464071563, 'stiletto': 3338888996, 'survival': 3493122922, 'ursus': 3338853291,
}
for knife, pid in MF.items():
    cats = {}
    feature_fill(cats, [pid])
    W(f'{knife}-marble-fade', f'{K[knife]} | Marble Fade', [pid], cats)

# ---------------- Doppler ----------------
# knife: (p1, p2, p3, p4, ruby, sapphire, black_pearl)
DOPPLER = {
    'bayonet':        (3243610461, 3317257914, 3338285640, 3246715647, 3590481218, 3590468086, 3671979590),
    'bowie':          (3729041147, 3412464042, 3721167065, 3719151675, 3789352740, 3789344203, 3789359360),
    'butterfly':      (3459724094, 3260663518, 3282682960, 3246704692, 3447694633, 3447693446, 3671538059),
    'falchion':       (3728986949, None,       3720627537, 3546096899, 3788350879, 3788344329, 3788360574),
    'flip':           (3241766965, 3318858354, 3338741600, 3247275425, 3786531393, 3786522369, 3786539119),
    'gut':            (3243706593, 3318900562, 3338797690, 3247301036, 3790093917, 3790088596, 3790097459),
    'huntsman':       (3728549140, 3412476635, 3720617302, 3719192390, 3787480274, 3787476724, 3787484620),
    'karambit':       (3241154866, 3260656134, 3265260886, 3244213994, 3439445531, 3439432594, 3329944825),
    'm9-bayonet':     (3415760920, 3323322399, 3337656739, 3246692346, 3558224308, 3558211331, 3671566764),
    'navaja':         (3729163302, 3727270306, 3721176965, 3719276565, 3791124837, 3791120607, 3791133936),
    'nomad':          (3531930706, 3517408099, 3516167610, 3480984728, 3720375750, 3720371360, 3720377862),
    'paracord':       (3532319350, 3528680696, 3517332131, 3489209078, None,       None,       None),
    'shadow-daggers': (3729171470, 3728061195, 3721183786, 3460814633, 3791787751, 3791783120, 3791790827),
    'skeleton':       (3531070271, 3517375101, 3516193087, 3488162681, 3456146270, 3456142297, 3678065018),
    'stiletto':       (3728457356, 3423585443, 3720524233, 3390916351, 3462429738, 3462427452, 3672119197),
    'survival':       (3532303208, 3521040284, 3517322947, 3489182519, None,       None,       None),
    'talon':          (3243798020, 3430272012, 3338267344, 3247329129, 3626059588, 3618374876, 3671612235),
    'ursus':          (3459952384, 3409010049, 3720590069, 3416342159, 3787042028, 3787033362, 3787048063),
}
GEM_META = [('ruby', 'Ruby', 'Рубин'), ('sapphire', 'Sapphire', 'Сапфир'),
            ('black_pearl', 'Black Pearl', 'Черная жемчужина')]
for knife, (p1, p2, p3, p4, rb, sp, bp) in DOPPLER.items():
    cats = {}
    pids = []
    for i, pid in enumerate([p1, p2, p3, p4], start=1):
        if pid:
            feature_fill(cats, [pid], suffix=f'_p{i}')
            pids.append(pid)
    for (key, label, ru), pid in zip(GEM_META, [rb, sp, bp]):
        if pid:
            ch_fill(cats, [pid], key, label, ru)
            pids.append(pid)
    W(f'{knife}-doppler', f'{K[knife]} | Doppler', pids, cats)

# ---------------- Gamma Doppler ----------------
GAMMA = {
    'bayonet':        (3260297444, 3317223855, 3241087868, 3237684411, 3590456700),
    'bowie':          (3328795583, 3412453723, 3730337876, 3329916074, 3789334854),
    'butterfly':      (3328751139, 3257814074, 3320998627, 3239033089, 3447692923),
    'falchion':       (3735016193, 3436309100, 3730323980, 3544687354, 3788336566),
    'flip':           (3260302147, 3318850792, 3387765712, 3237686295, 3786511183),
    'gut':            (3328731046, 3318885833, 3241119411, 3237687334, 3790084195),
    'huntsman':       (3436447655, 3320328350, 3322619692, 3238257426, 3787493346),
    'karambit':       (3257082211, 3251767409, 3239013869, 3237679658, 3439399642),
    'm9-bayonet':     (3328771870, 3320281182, 3321033049, 3237682787, 3558199636),
    'shadow-daggers': (3735064511, 3734987360, 3731614222, 3460790082, 3791782298),
}
for knife, (p1, p2, p3, p4, em) in GAMMA.items():
    cats = {}
    pids = []
    for i, pid in enumerate([p1, p2, p3, p4], start=1):
        if pid:
            feature_fill(cats, [pid], suffix=f'_p{i}')
            pids.append(pid)
    if em:
        ch_fill(cats, [em], 'emerald', 'Emerald', 'Изумруд')
        pids.append(em)
    W(f'{knife}-gamma-doppler', f'{K[knife]} | Gamma Doppler', pids, cats)

# Glock-18 Gamma Doppler (не нож)
cats = {}
gpids = []
for i, pid in enumerate([3422879993, 3404412396, 3404171137, 3402574591], start=1):
    feature_fill(cats, [pid], suffix=f'_p{i}')
    gpids.append(pid)
ch_fill(cats, [3534967014], 'emerald', 'Emerald', 'Изумруд')
gpids.append(3534967014)
W('glock-18-gamma-doppler', 'Glock-18 | Gamma Doppler', gpids, cats)

# ---------------- Перчатки ----------------
GLOVES = [
    ('driver-gloves-snow-leopard', '★ Driver Gloves | Snow Leopard', [3338824509]),
    ('driver-gloves-queen-jaguar', '★ Driver Gloves | Queen Jaguar', [3482066304]),
    ('driver-gloves-wave-chaser', '★ Driver Gloves | Wave Chaser', [3683443266]),
    ('driver-gloves-brocade-flowers', '★ Driver Gloves | Brocade Flowers', [3683445315]),
    ('sport-gloves-amphibious', '★ Sport Gloves | Amphibious', [3647511755]),
    ('sport-gloves-arid', '★ Sport Gloves | Arid', [3793355476]),
    ('sport-gloves-bronze-morph', '★ Sport Gloves | Bronze Morph', [3793373241]),
    ('sport-gloves-hedge-maze', '★ Sport Gloves | Hedge Maze', [3793085677]),
    ('sport-gloves-nocts', '★ Sport Gloves | Nocts', [3212041872]),
    ('sport-gloves-occult', '★ Sport Gloves | Occult', [3683448300]),
    ('sport-gloves-omega', '★ Sport Gloves | Omega', [3604322920]),
    ('sport-gloves-pandoras-box', "★ Sport Gloves | Pandora's Box", [3655078148, 3715993411]),
    ('sport-gloves-ultra-violet', '★ Sport Gloves | Ultra Violet', [3683442055]),
    ('sport-gloves-vice', '★ Sport Gloves | Vice', [3468987193]),
    ('sport-gloves-violet-beadwork', '★ Sport Gloves | Violet Beadwork', [3747895736]),
    ('moto-gloves-spearmint', '★ Moto Gloves | Spearmint', [3750499964]),
    ('specialist-gloves-crimson-kimono', '★ Specialist Gloves | Crimson Kimono', [3128984318]),
    ('specialist-gloves-lime-polycam', '★ Specialist Gloves | Lime Polycam', [3683436127]),
    ('specialist-gloves-pillow-punchers', '★ Specialist Gloves | Pillow Punchers', [3692388912]),
    ('specialist-gloves-fade', '★ Specialist Gloves | Fade', [3304802458]),
]
for fname, skin, pids in GLOVES:
    cats = {}
    feature_fill(cats, pids)
    W(fname, skin, pids, cats)

# ---------------- Прочие скины ----------------
MISC = [
    ('m4a1s-solitude', 'M4A1-S | Solitude', [3549003998]),
    ('m4a1s-party-animal', 'M4A1-S | Party Animal', [3654838611]),
    ('m4a1s-blue-phosphor', 'M4A1-S | Blue Phosphor', [3404466869]),
    ('m4a1s-icarus-fell', 'M4A1-S | Icarus Fell', [3238535951]),
    ('mp7-amberline', 'MP7 | Amberline', [3685336438]),
    ('mac-10-arabeque-mosaic', 'MAC-10 | Arabeque Mosaic', [3761794943]),
    ('skeleton-urban-masked', '★ Skeleton Knife | Urban Masked', [3791794693]),
    ('galil-rainbow-spoon', 'Galil AR | Rainbow Spoon', [3401440144]),
    ('ssg08-acid-fade', 'SSG 08 | Acid Fade', [3304751006]),
]
for fname, skin, pids in MISC:
    cats = {}
    feature_fill(cats, pids)
    W(fname, skin, pids, cats)
