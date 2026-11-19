import { monthNames } from "../utils/dateUtils.js";

const FULL_WEEKDAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const DAY_LETTERS = ["S", "M", "T", "W", "T", "F", "S"];

// Set when a day is picked while its button has keyboard focus. Picking a day redraws the
// whole Day view, so the new strip hands focus to the day that was just picked.
let refocusAfterDraw = false;

// The week strip at the top of the phone Day view: the seven days of the selected date's
// week, Sunday first. Tapping a day opens it. The open day is filled Mesa Blue and today is
// ringed, as in the Month view. CSS shows it on phones only; wider screens have the Week view.
export function renderWeekStrip({ container, selectedDate, today, onSelectDate }) {
    const strip = document.createElement("div");
    const weekStart = new Date(
        selectedDate.getFullYear(),
        selectedDate.getMonth(),
        selectedDate.getDate() - selectedDate.getDay()
    );

    strip.classList.add("day-week-strip");
    strip.setAttribute("role", "group");
    strip.setAttribute("aria-label", `Week of ${monthNames[weekStart.getMonth()]} ${weekStart.getDate()}`);

    for (let offset = 0; offset < 7; offset++) {
        // new Date() rolls a day past the end of the month into the next month for us.
        const date = new Date(weekStart.getFullYear(), weekStart.getMonth(), weekStart.getDate() + offset);

        strip.appendChild(createStripDay({
            date,
            isSelected: isSameDay(date, selectedDate),
            isToday: isSameDay(date, today),
            onSelectDate
        }));
    }

    container.appendChild(strip);

    if (refocusAfterDraw) {
        refocusAfterDraw = false;
        strip.querySelector('[aria-pressed="true"]')?.focus();
    }
}

// One day: its letter above its date. Screen readers skip the letter and hear the whole date,
// e.g. "Wednesday, November 18" (plus "current date" for today, from aria-current). That name
// still contains the visible "18", so a speech user can say "click 18" (WCAG 2.5.3 Label in Name).
function createStripDay({ date, isSelected, isToday, onSelectDate }) {
    const button = document.createElement("button");
    const letter = document.createElement("span");
    const number = document.createElement("span");

    button.type = "button";
    button.classList.add("day-week-strip-day");
    button.classList.toggle("is-selected", isSelected);
    button.classList.toggle("is-today", isToday);
    button.setAttribute("aria-pressed", String(isSelected));

    if (isToday) {
        button.setAttribute("aria-current", "date");
    }

    letter.classList.add("day-week-strip-letter");
    letter.setAttribute("aria-hidden", "true");
    letter.textContent = DAY_LETTERS[date.getDay()];
    number.classList.add("day-week-strip-number");
    number.append(
        createHiddenText(`${FULL_WEEKDAY_NAMES[date.getDay()]}, ${monthNames[date.getMonth()]} `),
        String(date.getDate())
    );

    button.append(letter, number);
    button.addEventListener("click", () => {
        refocusAfterDraw = document.activeElement === button;
        onSelectDate(date);
    });

    return button;
}

// Words that screen readers say but the strip doesn't show.
function createHiddenText(text) {
    const span = document.createElement("span");

    span.classList.add("day-week-strip-hidden-text");
    span.textContent = text;

    return span;
}

function isSameDay(a, b) {
    return a.getFullYear() === b.getFullYear() &&
        a.getMonth() === b.getMonth() &&
        a.getDate() === b.getDate();
}
