# Accessible colors for the zoom calendar and event cards

Why the focus-ring, "today", muted-text and event-card colors in `zoom-calendar.css`, `week-view.css` and `upcoming-events.css` have the values they do, and how to reuse them.

**Standard:** WCAG 2.1 Level AA, the level the ADA Title II web rule requires of public colleges (compliance date April 26, 2027).

## The three rules behind the numbers

| Rule | What it asks | Minimum ratio |
|---|---|---|
| WCAG 1.4.3 Contrast (Minimum) | Normal-size text against its background | 4.5 : 1 |
| WCAG 1.4.11 Non-text Contrast | Focus rings, borders and state markers against what is next to them | 3 : 1 |
| WCAG 2.5.8 Target Size (Minimum), WCAG 2.2 | Click and tap targets | 24 x 24 CSS px |

A ratio is a threshold: 4.49 fails 4.5.

## The colors

The values live in one place, the `:root` block at the top of `zoom-calendar.css`. Change them there and every use follows.

| Token | Value | Used for | Measured against | Ratio | Needs | Before |
|---|---|---|---|---|---|---|
| `--a11y-focus-ring` | `#0054a4` (Mesa Blue, `var(--mesa-blue)`) | Keyboard focus outline on buttons, breadcrumb, day numbers, year cards | the zoom bar, `#eef2f7` | 6.66 : 1 | 3 : 1 | District Cyan `#31a9e0` is 2.37 : 1 (fail) |
| `--a11y-today-ring` | `#c3540b` | Ring around today in the Month and Year views | white `#ffffff` | 4.57 : 1 | 3 : 1 | Mesa orange `#f37725` was 2.81 : 1 (fail) |
| `--a11y-text-muted` | `#435660` (Mesa Grey) | Day numbers of days outside the shown month; weekday names in the Week header | the grey cell, `#f3f4f6` | 6.97 : 1 | 4.5 : 1 | `#6b7280` was 4.39 : 1 (fail) |
| `--a11y-text-on-fill` | `#111827` | Text on Mesa orange and Mesa green event cards | Mesa orange `#f37725` and Mesa green `#699446` | 6.32 : 1 and 5.01 : 1 | 4.5 : 1 | White was 2.81 : 1 and 3.54 : 1 (fail) |

Other measurements: the focus ring is 7.49 : 1 on white; the today ring is 4.15 : 1 on the grey outside-month cell; the muted text is 7.67 : 1 on white and 5.64 : 1 on the highlighted "today" column of the Week header (`#d7dded`, where `#6b7280` was 3.55 : 1).

### Why these particular values

- **Focus ring: Mesa Blue.** It is the college color in the SDCCD brand guide (see "Where the brand colors come from" below), so nothing new is introduced. It clears 3 : 1 by a wide margin on both white and the pale-blue zoom bar, which cyan could not do.
- **Today ring: a darker orange.** Brand orange is 2.81 : 1 on white, just under the 3 : 1 line for a thin ring. `#c3540b` keeps the orange look and reaches 4.57 : 1. Brand orange is still fine for large filled areas; this darker value is only for thin lines and text on light backgrounds.
- **Muted text: Mesa Grey (`#435660`).** It is the Grey in the Mesa style guide. It stays visibly quieter than the dark day numbers but passes 4.5 : 1 on every light background used here. (It replaced Tailwind gray 600, `#4b5563`, which had almost the same ratios.)
- **Month event chips: `min-height: 24px`.** They were 20 px tall, under the WCAG 2.2 target size of 24 px.
- **Faded chips for days outside the month: opacity 0.94.** At 0.6 a purple chip was 3.24 : 1. With the dark grey text, 0.8 left a green chip at 3.60 : 1 and an orange chip at 4.15 : 1. At 0.94 every chip type is 4.70 : 1 or better (green is the lowest), and the chips still look slightly faded.

## Event card text

Event cards (Week, Month and Day views, Upcoming Events, Check-In) use the Mesa brand colors as fills. Only the blue fill changed (to the current Mesa Blue); for orange and green only the text on them changed.

| Fill | Text | Ratio | Before |
|---|---|---|---|
| Mesa orange `#f37725` | dark grey `#111827` | 6.32 : 1 | White was 2.81 : 1 (fail); grey date text `#dadada` was 2.01 : 1 |
| Mesa green `#699446` | dark grey `#111827` | 5.01 : 1 | White was 3.54 : 1 (fail); grey date text `#dadada` was 2.54 : 1 |
| Private card grey `#9ca3af` | dark grey `#111827` | 6.99 : 1 | `#374151` was 4.05 : 1 (fail) |
| Mesa Blue `#0054a4`, purple `#523993`, maroon `#643c48` | white (unchanged) | 7.49 : 1, 8.88 : 1, 9.20 : 1 | Already passing |

- **Where:** the `.green` and `.orange` rules at the end of `week-view.css`, and the private-card rules in `upcoming-events.css`.
- **Why this dark grey:** `#111827` is the main text color of the pop-ups and the admin page and is already used on private cards, so event cards now match them. It clears 4.5 : 1 on both fills. Green is the tight one: any lighter grey fails there (`#1f2937` is 4.14 : 1, the login label grey `#333333` is 3.56 : 1), and the Day view draws its detail lines slightly see-through (opacity 0.92), which leaves 4.56 : 1.
- **On orange and green cards, the date, time and attendee badge** follow the card's text color instead of a fixed grey or white, and the badge tint is translucent white, which keeps the dark text above 4.5 : 1 (a translucent black tint dropped it to 3.99 : 1 on green).
- **If the fills change** (the code notes a possible move to department colors), check every fill and text pair again with the steps below.

## Where the brand colors come from

The color tokens in `base.css` come from two official guides. The Mesa guide is older and Mesa-specific; the district guide is newer.

| Token | Value | Source |
|---|---|---|
| `--mesa-blue` | `#0054a4` | SDCCD brand guide (board adopted May 16, 2024; updated 6.5.2025), p. 38, "Mesa Blue" |
| `--district-cyan` | `#31a9e0` | SDCCD brand guide, p. 38, "District Cyan" |
| `--mesa-blue-dark` | `#0e223f` | Mesa College style guide 2023, p. 6, "Dark Blue". The guide prints the hex as `#f0d223f`, an apparent typo; its RGB values (14, 34, 63) are `#0e223f` |
| `--mesa-orange`, `--mesa-green`, `--mesa-purple`, `--mesa-maroon`, `--mesa-maroon-dark` | `#f37725`, `#699446`, `#523993`, `#643c48`, `#48182c` | Mesa College style guide 2023, p. 6 ("Dark Orange", "Green", "Purple", "Maroon", "Dark Maroon") |
| `--a11y-text-muted` | `#435660` | Mesa College style guide 2023, p. 6, "Grey" |

- The earlier blue, `#023867`, appears in neither guide. The 2024 Mesa Blue is close to the 2023 guide's Light Blue (`#2253a3`).
- The district guide has its own orange (`#f47c2a`) and green (`#9acb45`) as accent colors, but they are district colors, not Mesa colors, so the event cards keep the Mesa orange and green. Whether the 2023 Mesa secondary colors (orange, green, purple, maroon) still apply under the 2024 branding is not stated in either guide.
- The Mesa guide prints its Dark Orange as hex `#f37725` and as RGB 244, 119, 37 (`#f47725`), a one-unit difference. The code uses the printed hex.

## Reusing these in other CSS

Copy the four lines into your stylesheet, or reference the tokens if `zoom-calendar.css` is loaded:

```css
:root {
    --a11y-focus-ring: var(--mesa-blue);
    --a11y-today-ring: #c3540b;
    --a11y-text-muted: #435660;
    --a11y-text-on-fill: #111827;
}

.my-button:focus-visible {
    outline: 3px solid var(--a11y-focus-ring);
    outline-offset: 2px;
}

/* Text on a Mesa orange or green fill */
.my-card.orange,
.my-card.green {
    color: var(--a11y-text-on-fill);
}
```

Keep `outline-offset` at 2 px or more so the ring is not hidden by the element's own border or background. Never remove the outline without replacing it.

## Checking a new color

1. Put the foreground and background hex codes into the [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/).
2. Compare to the table above: 4.5 : 1 for text, 3 : 1 for rings, borders and icons.
3. Check against every background it can sit on (white, grey cells, the zoom bar, the highlighted today column), not just one.
4. If the element is partly see-through (opacity below 1), mix the colors with what is behind it before measuring.

The ratio is (L1 + 0.05) / (L2 + 0.05), where L1 is the lighter color's relative luminance and L2 the darker one's. The WCAG definition of relative luminance is at https://www.w3.org/TR/WCAG21/#dfn-relative-luminance.

## Not covered here

- This file covers only contrast. Keyboard access and screen-reader behavior are separate checks.
- Pop-up windows, the login page and the admin page have their own colors and have not been measured for this file.

## Sources

- W3C, [Understanding 1.4.3 Contrast (Minimum)](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum)
- W3C, [Understanding 1.4.11 Non-text Contrast](https://www.w3.org/WAI/WCAG21/Understanding/non-text-contrast)
- W3C, [Understanding 2.5.8 Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum)
- ADA.gov, [First Steps Toward Complying with the Title II Web Rule](https://www.ada.gov/resources/web-rule-first-steps/)
- San Diego Mesa College, [Branding & Graphics Standards, Style Guide 2023](https://www.sdmesa.edu/about-mesa/office-of-communications/_assets/documents/NEW_style-guide-mesa-2023.pdf)
- San Diego Community College District, [District Logo and Brand Style Guide](https://www.sdccd.edu/docs/District/cpr/district_logo_style_guide.pdf)
