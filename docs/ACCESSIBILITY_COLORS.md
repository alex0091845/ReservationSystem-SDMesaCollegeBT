# Accessible colors for the zoom calendar

Why the focus-ring, "today" and muted-text colors in `frontend/styles/zoom-calendar.css` have the values they do, and how to reuse them.

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
| `--a11y-focus-ring` | `#023867` (Mesa blue, `var(--mesa-blue)`) | Keyboard focus outline on buttons, breadcrumb, day numbers, year cards | the zoom bar, `#eef2f7` | 10.59 : 1 | 3 : 1 | District cyan `#2fa9e0` was 2.37 : 1 (fail) |
| `--a11y-today-ring` | `#c3540b` | Ring around today in the Month and Year views | white `#ffffff` | 4.57 : 1 | 3 : 1 | Mesa orange `#f37725` was 2.81 : 1 (fail) |
| `--a11y-text-muted` | `#4b5563` | Day numbers of days outside the shown month | the grey cell, `#f3f4f6` | 6.87 : 1 | 4.5 : 1 | `#6b7280` was 4.39 : 1 (fail) |

Other measurements: the focus ring is 11.90 : 1 on white; the today ring is 4.15 : 1 on the grey outside-month cell; the muted text is 7.56 : 1 on white.

### Why these particular values

- **Focus ring: Mesa blue.** It is already a brand token, so nothing new is introduced. It clears 3 : 1 by a wide margin on both white and the pale-blue zoom bar, which cyan could not do.
- **Today ring: a darker orange.** Brand orange is 2.81 : 1 on white, just under the 3 : 1 line for a thin ring. `#c3540b` keeps the orange look and reaches 4.57 : 1. Brand orange is still fine for large filled areas; this darker value is only for thin lines and text on light backgrounds.
- **Muted text: Tailwind gray 600 (`#4b5563`).** It stays visibly quieter than the dark day numbers but passes 4.5 : 1 on every light background used here.
- **Month event chips: `min-height: 24px`.** They were 20 px tall, under the WCAG 2.2 target size of 24 px.

## Reusing these in other CSS

Copy the three lines into your stylesheet, or reference the tokens if `zoom-calendar.css` is loaded:

```css
:root {
    --a11y-focus-ring: var(--mesa-blue);
    --a11y-today-ring: #c3540b;
    --a11y-text-muted: #4b5563;
}

.my-button:focus-visible {
    outline: 3px solid var(--a11y-focus-ring);
    outline-offset: 2px;
}
```

Keep `outline-offset` at 2 px or more so the ring is not hidden by the element's own border or background. Never remove the outline without replacing it.

## Checking a new color

1. Put the foreground and background hex codes into the [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/).
2. Compare to the table above: 4.5 : 1 for text, 3 : 1 for rings, borders and icons.
3. Check against every background it can sit on (white, grey cells, the zoom bar), not just one.

The ratio is (L1 + 0.05) / (L2 + 0.05), where L1 is the lighter color's relative luminance and L2 the darker one's. The WCAG definition of relative luminance is at https://www.w3.org/TR/WCAG21/#dfn-relative-luminance.

## Not covered here

- **Event card colors** (white text on Mesa orange and green) also fail contrast. They are defined in `week-view.css` and are a team branding decision, so they are not changed in this file.
- This file covers only contrast. Keyboard access and screen-reader behavior are separate checks.

## Sources

- W3C, [Understanding 1.4.3 Contrast (Minimum)](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum)
- W3C, [Understanding 1.4.11 Non-text Contrast](https://www.w3.org/WAI/WCAG21/Understanding/non-text-contrast)
- W3C, [Understanding 2.5.8 Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum)
- ADA.gov, [First Steps Toward Complying with the Title II Web Rule](https://www.ada.gov/resources/web-rule-first-steps/)
