# Logo

`fireside.svg` is the Fireside logo: a flame above two crossed logs, white
strokes on a rounded square. It was drawn for the project and is not based on
any Jagex artwork.

It is a copy of `assets/logo/fireside.svg` in the
[fireside](https://github.com/enemyrpg/fireside) repo, where the drawing is
maintained (MIT there; same author, BSD-2 here like the rest of this repo).
If the logo changes there, copy the new file here and render the PNGs again.

## PNGs
The PNGs are generated from `fireside.svg` and committed:

| File                                            | Size  | Used for              |
| ----------------------------------------------- | ----- | --------------------- |
| `icon.png` (repo root)                          | 48×48 | Plugin Hub icon       |
| `src/main/resources/com/fireside/panel-icon.png` | 16×16 | Side panel (sidebar)  |

Render them with:

```sh
./scripts/render-icons.sh
```

It uses `rsvg-convert` from librsvg (Arch: `sudo pacman -S librsvg`).
