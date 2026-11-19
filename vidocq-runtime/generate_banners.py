#!/usr/bin/env python3
#
# Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# https://www.eclipse.org/legal/epl-2.0/
#
# This Source Code may also be made available under the following Secondary
# Licenses when the conditions for such availability set forth in the Eclipse
# Public License, v. 2.0 are satisfied: GNU General Public License, version 2
# or any later version, which is available at
# https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
#
# It is also made available under the European Union Public Licence v. 1.2,
# which is available at
# https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
#
# SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
#

"""
Génère les bandeaux pour les réseaux sociaux Vidocq.
LinkedIn  : 1584 × 396 px
BlueSky   : 1500 × 500 px
"""

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageEnhance
import os

# ── Chemins ──────────────────────────────────────────────────────────────────
BASE = "/Users/antoine/dev/vidocq/vidocq"
OUT  = os.path.join(BASE, "social-banners")
os.makedirs(OUT, exist_ok=True)

LOGO_VIDOCQ  = os.path.join(BASE, "vidocq-runtime-logo.png")
LOGO_IMPLEMENTATIONS = [
    os.path.join(BASE, "../chappe/chappe-logo.png"),
    os.path.join(BASE, "../cassini/cassini-logo.png"),
    os.path.join(BASE, "../foy/foy-logo.png"),
    os.path.join(BASE, "../vauban/vauban-logo.png"),
    os.path.join(BASE, "../champollion/champollion-logo.png"),
    os.path.join(BASE, "../mansart/mansart-logo.png"),
]

# ── Palette pâle & sobre ─────────────────────────────────────────────────────
BG_TOP    = (243, 247, 252)   # bleu-gris très clair
BG_BOTTOM = (230, 238, 247)   # légèrement plus profond
ACCENT2   = (154, 177, 202)   # bleu acier pâle
TEXT_MAIN = ( 68,  88, 114)   # texte principal
TEXT_SUB  = (121, 142, 168)   # sous-titre


def gradient_background(w, h):
    """Crée un fond dégradé vertical pâle."""
    img = Image.new("RGBA", (w, h))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        r = int(BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t)
        g = int(BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t)
        b = int(BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t)
        draw.line([(0, y), (w, y)], fill=(r, g, b, 255))
    return img


def add_subtle_grid(img, spacing=66, color=(178, 197, 216, 24)):
    """Ajoute une grille très discrète en fond."""
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    w, h = img.size
    for x in range(0, w, spacing):
        draw.line([(x, 0), (x, h)], fill=color, width=1)
    for y in range(0, h, spacing):
        draw.line([(0, y), (w, y)], fill=color, width=1)
    return Image.alpha_composite(img, overlay)


def add_decorative_arc(img, color=(150, 180, 214, 26)):
    """Ajoute de grands arcs décoratifs en fond."""
    w, h = img.size
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    cx, cy = w // 2, h // 2
    for r in range(120, max(w, h), 140):
        draw.arc([cx - r, cy - r, cx + r, cy + r], start=200, end=340, fill=color, width=2)
    return Image.alpha_composite(img, overlay)


def fit_logo(logo_path, target_h, max_w=None):
    """Charge et redimensionne un logo pour une hauteur cible."""
    logo = Image.open(logo_path).convert("RGBA")
    ratio = target_h / logo.height
    new_w = int(logo.width * ratio)
    if max_w and new_w > max_w:
        ratio = max_w / logo.width
        new_w = max_w
        target_h = int(logo.height * ratio)
    logo = logo.resize((new_w, int(logo.height * ratio)), Image.LANCZOS)
    return logo


def soften_logo(logo, alpha_factor=0.86, saturation=0.76, brightness=1.05, contrast=0.92):
    """Adoucit un logo pour un rendu plus sobre et pâle."""
    rgb = logo.convert("RGB")
    rgb = ImageEnhance.Color(rgb).enhance(saturation)
    rgb = ImageEnhance.Brightness(rgb).enhance(brightness)
    rgb = ImageEnhance.Contrast(rgb).enhance(contrast)
    rgba = rgb.convert("RGBA")
    _, _, _, a = logo.split()
    a = a.point(lambda x: int(x * alpha_factor))
    r, g, b, _ = rgba.split()
    return Image.merge("RGBA", (r, g, b, a))


def get_font(size, bold=False):
    """Tente de charger une police système."""
    candidates = [
        "/System/Library/Fonts/Supplemental/Futura.ttc",
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/SFNSDisplay.ttf",
        "/System/Library/Fonts/SFNS.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                pass
    return ImageFont.load_default()


def add_central_glow(img):
    """Ajoute une lueur très légère derrière le logo Vidocq."""
    w, h = img.size
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    cx, cy = w // 2, h // 2 - int(h * 0.03)
    max_r = int(min(w, h) * 0.34)
    for i in range(8):
        radius = max_r - (i * max_r // 8)
        alpha = max(0, 22 - i * 2)
        draw.ellipse(
            [cx - radius, cy - radius, cx + radius, cy + radius],
            fill=(236, 243, 251, alpha),
        )
    return Image.alpha_composite(img, overlay)


# ── Génération d'un bandeau ───────────────────────────────────────────────────
def generate_banner(width, height, filename, logo_h_main, logo_h_side):
    img = gradient_background(width, height)
    img = add_subtle_grid(img)
    img = add_decorative_arc(img)
    img = add_central_glow(img)

    draw = ImageDraw.Draw(img)

    # ── Logos d'implémentation latéraux ───────────────────────────────────────
    margin = int(width * 0.05)
    center_x = width // 2

    implementation_paths = [path for path in LOGO_IMPLEMENTATIONS if os.path.exists(path)]
    logos = [soften_logo(fit_logo(path, logo_h_side, max_w=int(width * 0.13))) for path in implementation_paths]
    left_logos = logos[: len(logos) // 2]
    right_logos = logos[len(logos) // 2 :]

    # Logo central Vidocq
    if not os.path.exists(LOGO_VIDOCQ):
        raise FileNotFoundError(f"Missing Vidocq logo: {LOGO_VIDOCQ}")
    logo_vidocq = soften_logo(
        fit_logo(LOGO_VIDOCQ, logo_h_main, max_w=int(width * 0.22)),
        alpha_factor=0.98,
        saturation=0.84,
        brightness=1.03,
        contrast=0.95,
    )

    cy = height // 2

    # Place 3 logos à gauche + 3 logos à droite autour du centre
    side_gap = int(width * 0.018)
    left_end = center_x - logo_vidocq.width // 2 - int(width * 0.035)
    x = left_end
    for idx, logo in enumerate(reversed(left_logos)):
        x -= logo.width
        y = cy - logo.height // 2 + ((idx % 2) * int(height * 0.045) - int(height * 0.02))
        img.paste(logo, (x, y), logo)
        x -= side_gap

    right_start = center_x + logo_vidocq.width // 2 + int(width * 0.035)
    x = right_start
    for idx, logo in enumerate(right_logos):
        y = cy - logo.height // 2 + (((idx + 1) % 2) * int(height * 0.045) - int(height * 0.02))
        img.paste(logo, (x, y), logo)
        x += logo.width + side_gap

    # Colle Vidocq au centre
    x_vidocq = center_x - logo_vidocq.width // 2
    y_vidocq = cy - logo_vidocq.height // 2
    img.paste(logo_vidocq, (x_vidocq, y_vidocq), logo_vidocq)

    # ── Ligne séparatrice sous-titre ──────────────────────────────────────────
    line_y = height - int(height * 0.26)
    draw.line([(margin * 2, line_y), (width - margin * 2, line_y)],
              fill=(*ACCENT2, 80), width=1)

    # ── Texte tagline ─────────────────────────────────────────────────────────
    font_tag = get_font(int(height * 0.085), bold=False)
    font_sub = get_font(int(height * 0.052))

    tagline = "vidocq runtime"
    bbox = draw.textbbox((0, 0), tagline, font=font_tag)
    tw = bbox[2] - bbox[0]
    draw.text(((width - tw) // 2, line_y + int(height * 0.04)),
              tagline, font=font_tag, fill=(*TEXT_MAIN, 230))

    sub = "Java EE · Jakarta EE · MicroProfile"
    bbox2 = draw.textbbox((0, 0), sub, font=font_sub)
    sw = bbox2[2] - bbox2[0]
    draw.text(((width - sw) // 2, line_y + int(height * 0.15)),
              sub, font=font_sub, fill=(*TEXT_SUB, 200))

    # ── Sauvegarde ────────────────────────────────────────────────────────────
    out_path = os.path.join(OUT, filename)
    img.convert("RGB").save(out_path, "PNG", optimize=True)
    print(f"✅  {filename}  ({width}×{height})  →  {out_path}")


# ── Générer les deux formats ──────────────────────────────────────────────────
generate_banner(
    width=1584, height=396,
    filename="vidocq-banner-linkedin.png",
    logo_h_main=260, logo_h_side=190,
)

generate_banner(
    width=1500, height=500,
    filename="vidocq-banner-bluesky.png",
    logo_h_main=330, logo_h_side=240,
)
