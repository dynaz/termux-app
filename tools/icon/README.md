# TermiNow launcher icon

Static PNG icons (no generator plugin). The source of truth is `master-logo.png` plus
`generate_launcher_icon.py`; re-running the script overwrites the mipmaps so a future change does not
need hand editing.

## Insets (why the logo is not cropped)
- Master: full-bleed shield on a navy background; the shield is lifted off the navy into a transparent
  RGBA layer (flood fill from the border, `thresh=72`, + 1px defringe) so no navy edge remains.
- Adaptive foreground (`mipmap-*/ic_launcher_foreground.png`, 108..432 px): shield **height = 51.9%**
  of the 108dp layer (~56dp), centred, transparent margin. Fits inside the 66dp safe circle, so Circle
  and Squircle masks never clip the shield or the AI spark.
- Adaptive background: solid `#0B1428` via `@color/terminow_launcher_background` (no PNG, no frame in
  the foreground).
- Legacy `ic_launcher.png` / round `ic_launcher_round.png` (48..192 px): same shield on navy, height 60%.
- Play Store 512 icon: a **separate** render (shield on navy, height 62%) — never the adaptive
  foreground. Upload with `play.py icon`.

## Regenerate
    python3 tools/icon/generate_launcher_icon.py
Run from the repo root. Requires Pillow.
